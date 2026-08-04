package com.docaposte.modelioalchemist.langchain.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.io.FileWriter;
import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpResource;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;

/**
 * Owns the shared/static MCP infrastructure: the singleton MCP client/transport/tool-provider,
 * the pool of {@link PooledUmlAssistant} instances, and diagnostics logging around MCP tool
 * discovery. Extracted from {@code LangchainService} to isolate this "process-wide singleton"
 * concern from requirement creation, domain-model chunking, and agent-output processing.
 */
final class McpAssistantPool {

    private McpAssistantPool() {}

    // -------------------------------------------------- Logging --------------------------------------------------
    static final boolean DEBUG = true;
    static void debug(String msg) {
        if (DEBUG) System.out.println("[LangchainService] " + msg);
    }

    // -------------------------------------------------- Configuration --------------------------------------------------
    private static final int POOL_SIZE = 2;                    // max assistants parallèles
    private static final long POOL_BORROW_TIMEOUT_MS = 5000;
    private static final String DEFAULT_MCP_URL = "http://localhost:8083/mcp";
    private static final Duration MCP_INIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MCP_TOOL_EXECUTION_TIMEOUT = Duration.ofMinutes(3);
    private static final Duration MCP_RESOURCES_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MCP_PROMPTS_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MCP_PING_TIMEOUT = Duration.ofSeconds(15);

    // -------------------------------------------------- Infrastructure partagée (construite une fois) --------------------------------------------------
    private static volatile boolean infraInitialized = false;
    private static PolicyAwareAzureChatModel sharedChatModel;
    private static McpToolProvider sharedToolProvider;        // optionnel, peut être null
    private static DefaultMcpClient sharedMcpClient;          // pour shutdown; connexion unique
    private static McpTransport sharedTransport;          // pour shutdown
    private static final ArrayBlockingQueue<PooledUmlAssistant> ASSISTANT_POOL = new ArrayBlockingQueue<>(POOL_SIZE);

    // Cache des ressources MCP
    private static List<McpResource> cachedResources = new ArrayList<>();
    private static List<String> cachedToolNames = new ArrayList<>();
    private static List<ToolSpecification> cachedToolSpecifications = new ArrayList<>();
    private static volatile String mcpInitErrorDetail;

    static DefaultMcpClient sharedMcpClient() {
        return sharedMcpClient;
    }

    /**
     * Récupère l'UUID du package racine du projet via {@code project_overview} (appel MCP
     * déterministe, sans LLM). Utilisé pour ancrer un unique package partagé (ex. "Modèle de
     * Domaine", "Cas d'Usage") au lieu de laisser chaque appel LLM en créer un nouveau.
     */
    static String getProjectRootUuid() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        String response = sharedMcpClient.executeTool(ToolExecutionRequest.builder()
                .id("project-root-lookup-" + System.nanoTime())
                .name("project_overview")
                .arguments(mapper.writeValueAsString(mapper.createObjectNode()))
                .build());
        String uuid = extractJsonFieldUuid(response, "uuid");
        if (uuid == null) {
            throw new IllegalStateException("Unable to determine project root package UUID from project_overview. Response: "
                    + (response == null ? "null" : response.substring(0, Math.min(400, response.length()))));
        }
        return uuid;
    }

    /**
     * Garantit un unique package avec ce nom exact sous {@code parentUuid}, via l'outil
     * idempotent {@code uml_findOrCreateElement} (element_type par défaut : Standard.Package —
     * même comportement que l'ancien {@code uml_findOrCreatePackage}, renommé/généralisé côté
     * serveur). Élimine la duplication observée quand plusieurs appels LLM indépendants (lots de
     * chunking, ou simplement des libellés différents comme "Cas d Usage" vs "Cas d'Usage")
     * créaient chacun leur propre package via {@code uml_createElement}.
     */
    static String findOrCreatePackage(String name, String parentUuid) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("name", name);
        arguments.put("parent_uuid", parentUuid);
        String response = sharedMcpClient.executeTool(ToolExecutionRequest.builder()
                .id("find-or-create-package-" + System.nanoTime())
                .name("uml_findOrCreateElement")
                .arguments(mapper.writeValueAsString(arguments))
                .build());
        String uuid = extractJsonFieldUuid(response, "uuid");
        if (uuid == null) {
            throw new IllegalStateException("Unable to find or create package '" + name + "'. Response: "
                    + (response == null ? "null" : response.substring(0, Math.min(400, response.length()))));
        }
        return uuid;
    }

    /**
     * Extrait la valeur d'un champ JSON précis nommé {@code fieldName} (ex. "uuid") plutôt que le
     * premier UUID trouvé dans le texte : la réponse de {@code uml_findOrCreatePackage} contient
     * aussi "parent_uuid", qui apparaît avant "uuid" et serait capturé à tort par une regex générique.
     */
    private static String extractJsonFieldUuid(String response, String fieldName) {
        if (response == null) {
            return null;
        }
        Pattern fieldPattern = Pattern.compile(
                "\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12})\"");
        var matcher = fieldPattern.matcher(response);
        return matcher.find() ? matcher.group(1) : null;
    }

    static List<McpResource> cachedResources() {
        return cachedResources;
    }

    static ArrayBlockingQueue<PooledUmlAssistant> assistantPool() {
        return ASSISTANT_POOL;
    }

    /**
     * Initialise l'infrastructure partagée (client MCP, modèle de chat, pool d'assistants)
     */
    static synchronized void ensureInfrastructureInitialized(String mcpSseUrl, PolicyAwareAzureChatModel chatModel) {
        if (infraInitialized) return;

        sharedChatModel = chatModel;
        String actualMcpUrl = (mcpSseUrl != null && !mcpSseUrl.isEmpty()) ? mcpSseUrl : DEFAULT_MCP_URL;

        // MCP
        try {
            sharedTransport = new StreamableHttpMcpTransport.Builder()
                    .url(actualMcpUrl)
                    .timeout(Duration.ofMinutes(5))
                    .logRequests(true)
                    .logResponses(false)
                    .build();

            sharedMcpClient = new DefaultMcpClient.Builder()
                    .transport(sharedTransport)
                    .initializationTimeout(MCP_INIT_TIMEOUT)
                    .toolExecutionTimeout(MCP_TOOL_EXECUTION_TIMEOUT)
                    .resourcesTimeout(MCP_RESOURCES_TIMEOUT)
                    .promptsTimeout(MCP_PROMPTS_TIMEOUT)
                    .pingTimeout(MCP_PING_TIMEOUT)
                    .toolExecutionTimeoutErrorMessage("MCP tool execution timed out. Increase timeout or reduce batch size.")
                    .build();

            try {
                // Lister les outils
                cachedToolSpecifications = sharedMcpClient.listTools();
                cachedToolNames = cachedToolSpecifications.stream()
                        .map(ts -> ts.name())
                        .collect(Collectors.toList());
                debug("Discovered " + cachedToolNames.size() + " MCP tool(s)");
                cachedToolNames.forEach(toolName -> debug("MCP Tool: " + toolName));
                logMcpToolDiagnostics(cachedToolSpecifications);

                try {
                    cachedResources = sharedMcpClient.listResources();
                    debug("Discovered " + cachedResources.size() + " MCP resource(s)");

                    // Énumérer chaque ressource pour diagnostics
                    for (McpResource r : cachedResources) {
                        try {
                            debug("  -> MCP Resource loaded: name='" + r.name() + "' uri=" + r.uri());
                        } catch (Throwable resLogEx) {
                            debug("  -> MCP Resource logging failed: " + resLogEx.getMessage());
                        }
                    }

                    if (cachedResources.isEmpty()) {
                        debug("No MCP resources returned. Verify MCP server exposes resources endpoint.");
                    }
                } catch (Throwable rt) {
                    debug("Resource listing failed: " + rt.getMessage());
                }
            } catch (Throwable t) {
                debug("Tool listing failed: " + t.getMessage());
            }

            sharedToolProvider = McpToolProvider.builder().mcpClients(sharedMcpClient).build();
            mcpInitErrorDetail = null;
        } catch (Throwable t) {
            mcpInitErrorDetail = describeThrowable(t);
            debug("MCP init failed: " + mcpInitErrorDetail);
            throw new IllegalStateException("MCP initialization failed: " + mcpInitErrorDetail, t);
        }

        // Pré-chauffer le pool
        for (int i = 0; i < POOL_SIZE; i++) {
            ASSISTANT_POOL.offer(newAssistant());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            debug("Shutdown hook: closing MCP resources");
            try {
                if (sharedMcpClient != null) sharedMcpClient.close();
            } catch (Exception e) {
                debug("mcpClient close failed: " + e.getMessage());
            }
            try {
                if (sharedTransport != null) sharedTransport.close();
            } catch (Exception e) {
                debug("transport close failed: " + e.getMessage());
            }
        }));

        infraInitialized = true;
        debug("Infrastructure initialized: poolSize=" + ASSISTANT_POOL.size());
    }

    // -------------------------------------------------- Assistant poolé --------------------------------------------------
    static PooledUmlAssistant newAssistant() {
        ChatMemory memory = MessageWindowChatMemory.withMaxMessages(50);
        AiServices<UmlModelingAssistant> builder = AiServices.builder(UmlModelingAssistant.class)
                .chatModel(sharedChatModel)
                .chatMemory(memory)
                .maxSequentialToolsInvocations(300);  // Permettre la création de modèles UML complexes (classes + attributs + associations)

        if (sharedToolProvider != null) {
            builder.toolProvider(sharedToolProvider);
        }

        UmlModelingAssistant assistant = builder.build();
        return new PooledUmlAssistant(memory, assistant);
    }

    static PooledUmlAssistant borrowAssistant() throws InterruptedException {
        PooledUmlAssistant pa = ASSISTANT_POOL.poll(POOL_BORROW_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (pa == null) { // pool épuisé / timeout
            debug("Pool exhausted, creating ephemeral assistant");
            return newAssistant();
        }
        return pa;
    }

    static void releaseAssistant(PooledUmlAssistant pa) {
        if (pa == null) return;
        // Remise dans le pool si de la place
        if (ASSISTANT_POOL.remainingCapacity() == 0) {
            // pool plein -> abandon
            return;
        }
        ASSISTANT_POOL.offer(newAssistant()); // offrir un nouveau pour éviter le contexte résiduel
    }

    static void ensureMcpToolsAvailable() {
        if (sharedToolProvider == null || cachedToolNames.isEmpty()) {
            String detail = (mcpInitErrorDetail != null && !mcpInitErrorDetail.isBlank())
                    ? mcpInitErrorDetail
                    : "No MCP tools discovered from server";
            throw new IllegalStateException("MCP tools unavailable: " + detail);
        }
    }

    static String describeThrowable(Throwable t) {
        if (t == null) return "unknown";
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 5) {
            if (depth > 0) sb.append(" <- ");
            sb.append(current.getClass().getName());
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                sb.append(": ").append(current.getMessage());
            }
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static void logMcpToolDiagnostics(List<ToolSpecification> tools) {
        if (tools == null || tools.isEmpty()) {
            debug("MCP tool diagnostics: no tools returned by listTools()");
            return;
        }
        for (int i = 0; i < tools.size(); i++) {
            ToolSpecification spec = tools.get(i);
            if (spec == null) {
                debug("MCP Tool[" + i + "] = null");
                continue;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("MCP Tool[").append(i).append("] ");
            sb.append("name='").append(nullToEmpty(spec.name())).append("'");
            if (spec.description() != null && !spec.description().isBlank()) {
                sb.append(" description='").append(truncate(spec.description(), 220)).append("'");
            }

            String schemaPreview;
            try {
                String schema = ToolSchemaBuilder.build(spec);
                schemaPreview = truncate(schema, 1200);
            } catch (Throwable schemaEx) {
                schemaPreview = "SCHEMA_BUILD_ERROR: " + describeThrowable(schemaEx);
            }
            sb.append(" schemaPreview=").append(schemaPreview);

            String reflectedParameters = invokeNoArgSafely(spec, "parameters");
            if (reflectedParameters != null) {
                sb.append(" reflected.parameters=").append(truncate(reflectedParameters, 600));
            }
            String reflectedInputSchema = invokeNoArgSafely(spec, "inputSchema");
            if (reflectedInputSchema != null) {
                sb.append(" reflected.inputSchema=").append(truncate(reflectedInputSchema, 600));
            }
            debug(sb.toString());
        }
    }

    private static String invokeNoArgSafely(Object target, String methodName) {
        try {
            Object value = target.getClass().getMethod(methodName).invoke(target);
            return value == null ? "null" : value.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String truncate(String value, int maxLen) {
        if (value == null) return "null";
        if (value.length() <= maxLen) return value;
        return value.substring(0, maxLen) + "...(truncated)";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Méthode de debug pour sauvegarder le contenu dans un fichier
     */
    static void saveDebugFile(String content, String filename) {
        saveDebugFile(content, filename, null);
    }

    /**
     * Méthode de debug pour sauvegarder le contenu dans un fichier avec répertoire optionnel
     */
    static void saveDebugFile(String content, String filename, String outputDirectory) {
        try {
            String debugPath;
            if (outputDirectory != null && !outputDirectory.trim().isEmpty()) {
                // Utiliser le répertoire spécifié (comme le pipeline)
                debugPath = outputDirectory + "/" + filename;
            } else {
                // Fallback vers %TEMP%
                debugPath = System.getProperty("java.io.tmpdir") + "/" + filename;
            }

            try (FileWriter writer = new FileWriter(debugPath)) {
                writer.write(content);
                debug("Debug file saved: " + debugPath);
            }
        } catch (IOException e) {
            debug("Failed to save debug file: " + e.getMessage());
        }
    }
}
