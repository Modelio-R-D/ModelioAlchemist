package com.docaposte.modelioalchemist.langchain.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpResource;
import dev.langchain4j.mcp.client.McpResourceContents;
import dev.langchain4j.mcp.client.McpTextResourceContents;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import com.docaposte.modelioalchemist.langchain.impl.PolicyAwareAzureChatModel;
import com.docaposte.modelioalchemist.langchain.impl.AzureEndpointResolver;
import com.docaposte.modelioalchemist.langchain.impl.HttpPolicies;
import com.docaposte.modelioalchemist.langchain.impl.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
/**
 * Service LangChain4j unifié pour ModelioAlchemist suivant le pattern de ModelioBot.
 * Implémentation poolée : réutilise le client MCP, le fournisseur d'outils, et un petit pool d'instances UmlModelingAssistant.
 * Évite la configuration de connexion par requête tout en gardant les conversations isolées.
 */
public class LangchainService {
    private static final String CANONICAL_REQUIREMENT_PREFIX = "EXG";
    private static final String DEFAULT_REQUIREMENT_CONTAINER_NAME = "ModelioAlchemist Requirements";
    private static final String DEFAULT_REQUIREMENT_CONTAINER_DEFINITION =
            "Automatically created by ModelioAlchemist to store imported requirements.";
    private static final Pattern REAL_UUID_PATTERN = Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");
    private static final Pattern PLACEHOLDER_UUID_VALUE_PATTERN = Pattern.compile("\"[^\"]*uuid[^\"]*\"\\s*:\\s*\"uuid-[^\"]+\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUIREMENT_ID_PATTERN = Pattern.compile("(?i)(?:REQ|EXG|EX)[-_\\s]?(\\d{1,6})");
    private static final Pattern MISSING_REQUIREMENT_CONTAINER_PATTERN =
            Pattern.compile("(?i)no\\s+requirementcontainer\\s+found");
    private static final Pattern MANUAL_INSTRUCTIONS_PATTERN = Pattern.compile(
            "(?i)(^|\\b)(?:étape\\s*1|step\\s*1|ouvrez?\\s+modelio|ouvrir\\s+modelio|suivez\\s+les\\s+étapes|vous\\s+pouvez\\s+trouver\\s+l['’]uuid|trouver\\s+l['’]uuid|assurez-vous\\s+d['’]avoir\\s+modelio|créez\\s+un\\s+nouveau\\s+projet)");
    private static final String MCP_NO_TOOL_CALL_SUFFIX = "completed without MCP tool calls.";

    // -------------------------------------------------- Logging --------------------------------------------------
    private static final boolean DEBUG = true;
    private static void debug(String msg) { 
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

    /**
     * Initialise l'infrastructure partagée (client MCP, modèle de chat, pool d'assistants)
     */
    private static synchronized void ensureInfrastructureInitialized(String mcpSseUrl, PolicyAwareAzureChatModel chatModel) {
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
    private static PooledUmlAssistant newAssistant() {
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

    private static PooledUmlAssistant borrowAssistant() throws InterruptedException {
        PooledUmlAssistant pa = ASSISTANT_POOL.poll(POOL_BORROW_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (pa == null) { // pool épuisé / timeout
            debug("Pool exhausted, creating ephemeral assistant");
            return newAssistant();
        }
        return pa;
    }

    private static void releaseAssistant(PooledUmlAssistant pa) {
        if (pa == null) return;
        // Remise dans le pool si de la place
        if (ASSISTANT_POOL.remainingCapacity() == 0) {
            // pool plein -> abandon
            return;
        }
        ASSISTANT_POOL.offer(newAssistant()); // offrir un nouveau pour éviter le contexte résiduel
    }

    private static String executeAssistantWithMcpTrace(PooledUmlAssistant pa, String phaseName, String prompt, String outputDirectory) throws IOException {
        debug("MCP discovery snapshot for phase '" + phaseName + "': tools=" + cachedToolNames.size() + " " + cachedToolNames);
        PolicyAwareAzureChatModel.startToolExecutionTrace(phaseName);
        String result;
        try {
            result = pa.assistant.createUmlModel(prompt);
        } finally {
            PolicyAwareAzureChatModel.ToolExecutionTrace trace = PolicyAwareAzureChatModel.finishToolExecutionTrace();
            String traceSummary = formatToolExecutionTrace(trace);
            debug(traceSummary);
            if (outputDirectory != null && !outputDirectory.trim().isEmpty()) {
                saveDebugFile(traceSummary + System.lineSeparator(), phaseName + "_mcp_trace.txt", outputDirectory);
            }
            if (trace == null || trace.modelRequestedToolCalls <= 0) {
                throw new IllegalStateException(
                        "MCP_EXECUTION_FAILED: phase '" + phaseName + "' completed without MCP tool calls.");
            }
        }
        return result;
    }

    private static String executeAssistantWithMcpTraceWithRetry(
            PooledUmlAssistant pa, String phaseName, String prompt, String outputDirectory, int maxAttempts) throws IOException {
        if (maxAttempts <= 1) {
            return executeAssistantWithMcpTrace(pa, phaseName, prompt, outputDirectory);
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String attemptPrompt = prompt;
            if (attempt > 1) {
                attemptPrompt = prompt + System.lineSeparator() + System.lineSeparator()
                        + "RETRY #" + attempt + ": la tentative précédente n'a exécuté aucun outil MCP."
                        + " Exécutez maintenant les appels MCP requis et retournez uniquement la sortie as-built.";
                debug("♻️ Retrying phase '" + phaseName + "' after zero MCP tool call response (attempt " + attempt + "/" + maxAttempts + ")");
            }
            try {
                return executeAssistantWithMcpTrace(pa, phaseName, attemptPrompt, outputDirectory);
            } catch (IllegalStateException e) {
                if (!isNoToolCallFailure(e) || attempt == maxAttempts) {
                    throw e;
                }
            }
        }

        throw new IllegalStateException("MCP_EXECUTION_FAILED: phase '" + phaseName + "' exceeded retry limit.");
    }

    private static boolean isNoToolCallFailure(IllegalStateException e) {
        return e != null && e.getMessage() != null && e.getMessage().contains(MCP_NO_TOOL_CALL_SUFFIX);
    }

    private static String formatToolExecutionTrace(PolicyAwareAzureChatModel.ToolExecutionTrace trace) {
        if (trace == null) {
            return "MCP_TRACE phase=unknown status=no-trace";
        }
        return trace.toSummary();
    }

    private static void ensureMcpToolsAvailable() {
        if (sharedToolProvider == null || cachedToolNames.isEmpty()) {
            String detail = (mcpInitErrorDetail != null && !mcpInitErrorDetail.isBlank())
                    ? mcpInitErrorDetail
                    : "No MCP tools discovered from server";
            throw new IllegalStateException("MCP tools unavailable: " + detail);
        }
    }

    private static String describeThrowable(Throwable t) {
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

    private static String truncate(String value, int maxLen) {
        if (value == null) return "null";
        if (value.length() <= maxLen) return value;
        return value.substring(0, maxLen) + "...(truncated)";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // -------------------------------------------------- API Publique (poolée) --------------------------------------------------
    
    /**
     * Génère un modèle UML dans Modelio à partir du contenu PlantUML et des documents d'analyse
     * en utilisant l'approche poolée partagée de ModelioBot.
     */
    public static String generateModelFromPlantUML(String plantUMLContent, String requirementsDocuments, String mcpSseUrl, PolicyAwareAzureChatModel chatModel) {
        return generateModelFromPlantUMLInternal(plantUMLContent, requirementsDocuments, null, mcpSseUrl, chatModel);
    }
    
    /**
     * Méthode interne qui gère la génération avec optionnel répertoire de sortie
     */
    private static String generateModelFromPlantUMLInternal(String plantUMLContent, String requirementsDocuments, String outputDirectory, String mcpSseUrl, PolicyAwareAzureChatModel chatModel) {
        System.out.println("=== LANGCHAIN SERVICE DEBUG ===");
        System.out.println("generateModelFromPlantUMLInternal called");
        System.out.println("requirementsDocuments null check: " + (requirementsDocuments == null));
        if (requirementsDocuments != null) {
            System.out.println("requirementsDocuments length: " + requirementsDocuments.length());
            System.out.println("requirementsDocuments empty check: " + requirementsDocuments.trim().isEmpty());
            System.out.println("First 100 chars: " + requirementsDocuments.substring(0, Math.min(100, requirementsDocuments.length())));
        }
        System.out.println("outputDirectory: " + outputDirectory);
        System.out.println("=== END DEBUG ===");
        
        debug("🔍 generateModelFromPlantUML called with:");
        debug("   plantUMLContent length: " + (plantUMLContent == null ? -1 : plantUMLContent.length()));
        debug("   requirementsDocuments length: " + (requirementsDocuments == null ? -1 : requirementsDocuments.length()));
        debug("   outputDirectory: " + (outputDirectory == null ? "null" : outputDirectory));
        debug("   mcpSseUrl: " + mcpSseUrl);
        
        PooledUmlAssistant pa = null;
        try {
            ensureInfrastructureInitialized(mcpSseUrl, chatModel);
            ensureMcpToolsAvailable();
            pa = borrowAssistant();
            
            ChatMemory chatMemory = pa.memory;
            
            // Injecter les ressources MCP (limité)
            injectMcpResources(chatMemory);
            
            // Parser et injecter les exigences extraites des documents d'analyse
            List<Requirement> parsedRequirements = new ArrayList<>();
            debug("🔍 Checking requirementsDocuments: " + (requirementsDocuments == null ? "NULL" : "NOT NULL"));
            debug("🔍 Requirements documents empty check: " + (requirementsDocuments != null ? requirementsDocuments.trim().isEmpty() : "N/A"));
            if (requirementsDocuments != null && !requirementsDocuments.trim().isEmpty()) {
                debug("✅ Processing requirements documents - length: " + requirementsDocuments.length());
                // DEBUG: Sauvegarder le contenu pour analyse
                debug("🔍 Attempting to save requirements_input.txt to: " + (outputDirectory == null ? "TEMP" : outputDirectory));
                saveDebugFile(requirementsDocuments, "requirements_input.txt", outputDirectory);
                debug("✅ Successfully saved requirements_input.txt");
                
                // Parser automatiquement les exigences
                debug("🔍 Starting requirements parsing...");
                parsedRequirements = parseRequirementsFromDocuments(requirementsDocuments);
                debug("✅ Parsing completed - found " + parsedRequirements.size() + " requirements");
                
                // DEBUG: Sauvegarder les requirements parsées
                if (!parsedRequirements.isEmpty()) {
                    StringBuilder parsedReqLog = new StringBuilder();
                    parsedReqLog.append("=== PARSED REQUIREMENTS DEBUG ===\n\n");
                    parsedReqLog.append("Total parsed: ").append(parsedRequirements.size()).append("\n\n");
                    
                    for (int i = 0; i < parsedRequirements.size(); i++) {
                        Requirement req = parsedRequirements.get(i);
                        parsedReqLog.append(String.format("Requirement %d:\n", i + 1));
                        parsedReqLog.append(String.format("  ID: %s\n", req.id));
                        parsedReqLog.append(String.format("  Title: %s\n", req.title));
                        parsedReqLog.append(String.format("  Description: %s\n", req.description));
                        parsedReqLog.append(String.format("  Category: %s\n", req.category));
                        parsedReqLog.append(String.format("  Priority: %s\n", req.priority));
                        parsedReqLog.append(String.format("  Origin: %s\n\n", req.origin));
                    }
                    
                    saveDebugFile(parsedReqLog.toString(), "parsed_requirements.txt", outputDirectory);
                    debug("Saved parsed requirements debug file");
                } else {
                    saveDebugFile("NO REQUIREMENTS PARSED - Check patterns and input format", "parsed_requirements_EMPTY.txt", outputDirectory);
                    debug("❌ NO REQUIREMENTS PARSED - saved empty debug file");
                }
                
                // Injecter les exigences structurées dans le contexte
                if (!parsedRequirements.isEmpty()) {
                    StringBuilder reqContext = new StringBuilder();
                    reqContext.append("Extracted Requirements from Analysis Documents:\n\n");
                    
                    for (Requirement req : parsedRequirements) {
                        reqContext.append(String.format("ID: %s\n", req.id));
                        reqContext.append(String.format("Title: %s\n", req.title));
                        reqContext.append(String.format("Description: %s\n", req.description));
                        reqContext.append(String.format("Category: %s\n", req.category));
                        reqContext.append(String.format("Priority: %s\n", req.priority));
                        reqContext.append(String.format("Origin: %s\n\n", req.origin));
                    }
                    
                    chatMemory.add(UserMessage.from(reqContext.toString()));
                    debug("Injected " + parsedRequirements.size() + " structured requirements");
                } else {
                    debug("❌ NO requirements documents provided!");
                    debug("   requirementsDocuments == null: " + (requirementsDocuments == null));
                    if (requirementsDocuments != null) {
                        debug("   requirementsDocuments.trim().isEmpty(): " + requirementsDocuments.trim().isEmpty());
                        debug("   requirementsDocuments.length(): " + requirementsDocuments.length());
                        debug("   First 100 chars: " + requirementsDocuments.substring(0, Math.min(100, requirementsDocuments.length())));
                    }
                }
            }
            
            // Injecter le snippet PlantUML dans le contexte
            if (plantUMLContent != null && !plantUMLContent.trim().isEmpty()) {
                String modelSnippet = plantUMLContent.length() > 10000 ? 
                        plantUMLContent.substring(0, 10000) + "\n... (truncated)" : plantUMLContent;
                chatMemory.add(SystemMessage.from("PlantUML content to analyze:\n" + modelSnippet));
                debug("Injected PlantUML snippet length=" + modelSnippet.length());
            }
            
            // Tester la connexion MCP d'abord
            debug("🔄 Testing MCP connection...");
            try {
                sharedMcpClient.listTools(); // Test de connexion
                debug("✅ MCP connection successful");
            } catch (Exception e) {
                debug("❌ MCP connection failed: " + e.getMessage());
                return "❌ Impossible de se connecter au serveur MCP Modelio.\n\n" +
                       "VÉRIFICATIONS NÉCESSAIRES :\n" +
                       "1. Modelio est-il démarré ?\n" +
                       "2. Le module 'Modelio MCP Server' est-il installé et activé ?\n" +
                       "3. Le serveur MCP écoute-t-il sur " + mcpSseUrl + " ?\n" +
                       "4. Y a-t-il des messages d'erreur dans la console Modelio ?\n\n" +
                       "Erreur technique : " + e.getMessage();
            }
            
            // Créer le prompt pour l'assistant - utiliser l'ancienne approche monolithique pour generateModelFromPlantUML
            String prompt = createLegacyModelGenerationPrompt(plantUMLContent, requirementsDocuments, parsedRequirements);
            
            // Laisser l'assistant IA gérer la création du modèle avec les outils MCP disponibles
            String result = executeAssistantWithMcpTrace(pa, "legacy_generation", prompt, outputDirectory);
            
            return result;
            
        } catch (Throwable t) {
            debug("generateModelFromPlantUML error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return "[error: " + t.getClass().getSimpleName() + " - " + t.getMessage() + "]";
        } finally {
            releaseAssistant(pa);
        }
    }

    /**
     * Méthode de compatibilité pour les appels existants (PlantUML uniquement)
     */
    public static String generateModelFromPlantUML(String plantUMLContent, String mcpSseUrl, PolicyAwareAzureChatModel chatModel) {
        return generateModelFromPlantUML(plantUMLContent, null, mcpSseUrl, chatModel);
    }
    
    /**
     * Génère un modèle UML avec documents d'analyse et répertoire de sortie pour les fichiers de debug
     */
    public static String generateModelFromPlantUML(String plantUMLContent, String requirementsDocuments, String outputDirectory, String mcpSseUrl, PolicyAwareAzureChatModel chatModel) {
        return generateModelFromPlantUMLInternal(plantUMLContent, requirementsDocuments, outputDirectory, mcpSseUrl, chatModel);
    }

    /**
     * Crée les exigences dans Modelio à partir des exigences filtrées
     */
    public static String createRequirementsInModelio(
            String filteredRequirementsJson,
            String outputDirectory,
            String sourceDocumentName,
            String mcpSseUrl,
            PolicyAwareAzureChatModel chatModel) {
        ensureInfrastructureInitialized(mcpSseUrl, chatModel);
        ensureMcpToolsAvailable();
        
        try {
            debug("🎯 Creating requirements in Modelio from filtered JSON...");
            
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(filteredRequirementsJson);
            
            if (!root.has("filtered_requirements")) {
                return "❌ No filtered_requirements found in JSON";
            }

            JsonNode filteredReqs = root.get("filtered_requirements");
            if (!filteredReqs.isArray() || filteredReqs.isEmpty()) {
                return "❌ filtered_requirements is empty";
            }

            List<Requirement> requirements = new ArrayList<>();
            int index = 1;
            for (JsonNode reqNode : filteredReqs) {
                String normalizedId = normalizeRequirementId(reqNode.path("id").asText(null), index);
                String description = reqNode.path("description").asText("");
                String origin = buildRequirementOrigin(reqNode, sourceDocumentName, description);
                requirements.add(new Requirement(
                        normalizedId,
                        normalizedId,
                        description,
                        reqNode.path("category").asText("Fonctionnel"),
                        reqNode.path("priority").asText("Moyenne"),
                        origin));
                index++;
            }

            String result = createRequirementsDirectlyViaMcp(requirements, outputDirectory);
            validateMcpExecutionResult("requirements", result, "requirements_created", "exigences_creees");
            debug("✅ Requirements creation completed");

            if (outputDirectory != null) {
                Files.writeString(Path.of(outputDirectory).resolve("requirements_creation_report.txt"), result);
            }

            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            debug("❌ Requirements creation interrupted: " + e.getMessage());
            return "❌ Error creating requirements: interrupted while waiting for Modelio MCP";
        } catch (Exception e) {
            debug("❌ Error creating requirements in Modelio: " + e.getMessage());
            return "❌ Error creating requirements: " + e.getMessage();
        }
    }

    private static String createRequirementsDirectlyViaMcp(List<Requirement> requirements, String outputDirectory) throws IOException, InterruptedException {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode createdRequirements = mapper.createArrayNode();
        ArrayNode failedRequirements = mapper.createArrayNode();
        StringBuilder executionTrace = new StringBuilder();
        String requirementContainerUuid = null;

        debug("📝 Creating " + requirements.size() + " requirements in Modelio via direct MCP calls...");

        for (int i = 0; i < requirements.size(); i++) {
            Requirement requirement = requirements.get(i);
            String response = createRequirementElement(requirement, requirementContainerUuid, mapper, executionTrace);

            if (requirementContainerUuid == null && isMissingRequirementContainerError(response)) {
                debug("📁 No RequirementContainer found. Creating one automatically before retrying " + requirement.id);
                requirementContainerUuid = createRequirementContainer(outputDirectory, mapper, executionTrace);
                response = createRequirementElement(requirement, requirementContainerUuid, mapper, executionTrace);
            }

            debug("MCP direct analyst_createElement for " + requirement.id + " -> " + truncate(response, 400));

            String createdUuid = extractFirstRealUuid(response);
            if (createdUuid == null) {
                ObjectNode failedRequirement = failedRequirements.addObject();
                failedRequirement.put("id", requirement.id);
                failedRequirement.put("description", requirement.description);
                failedRequirement.put("error", extractMcpErrorMessage(response, "No UUID returned by analyst_createElement"));
                failedRequirement.put("raw_response", truncate(response, 1200));
                break;
            }

            ObjectNode createdRequirement = createdRequirements.addObject();
            createdRequirement.put("id", requirement.id);
            createdRequirement.put("uuid", createdUuid);
            createdRequirement.put("description", requirement.description);
            createdRequirement.put("category", requirement.category);
            createdRequirement.put("priority", requirement.priority);
            if (requirementContainerUuid != null) {
                createdRequirement.put("container_uuid", requirementContainerUuid);
            }
            createdRequirement.put("origin", requirement.origin);

            if (i < requirements.size() - 1) {
                // Give Modelio a brief moment to process Analyst-side refreshes between transactions.
                // Was previously 250ms; that added ~seconds of pure idle wait for documents with many
                // requirements (e.g. 10s+ for 40 requirements). 40ms is still enough slack for the
                // refresh while cutting that overhead by ~6x.
                TimeUnit.MILLISECONDS.sleep(40);
            }
        }

        if (outputDirectory != null) {
            Files.writeString(Path.of(outputDirectory).resolve("requirements_direct_mcp_trace.txt"), executionTrace.toString());
        }

        ObjectNode report = mapper.createObjectNode();
        report.set("requirements_created", createdRequirements);
        report.put("total_requirements", createdRequirements.size());
        if (requirementContainerUuid == null) {
            report.putNull("package_uuid");
            report.putNull("container_uuid");
        } else {
            report.put("package_uuid", requirementContainerUuid);
            report.put("container_uuid", requirementContainerUuid);
        }
        if (!failedRequirements.isEmpty()) {
            report.set("failed_requirements", failedRequirements);
        }

        String reportJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        if (!failedRequirements.isEmpty() || createdRequirements.size() != requirements.size()) {
            throw new IllegalStateException("MCP direct requirement creation stopped after "
                    + createdRequirements.size() + "/" + requirements.size() + " items. Partial report:\n" + reportJson);
        }

        return reportJson;
    }

    private static String createRequirementElement(Requirement requirement, String containerUuid, ObjectMapper mapper,
            StringBuilder executionTrace) throws IOException {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("type", "Requirement");
        arguments.put("name", requirement.id);
        arguments.put("definition", requirement.description);
        if (containerUuid != null && !containerUuid.isBlank()) {
            arguments.put("container_uuid", containerUuid);
        }
        ObjectNode analystProperties = arguments.putObject("analyst_properties");
        analystProperties.put("categorie", requirement.category);
        analystProperties.put("priorité", requirement.priority);
        if (requirement.origin != null && !requirement.origin.isBlank()) {
            // "Origin" (capital O) is the exact analyst property key defined by the Modelio
            // Analyst project's Requirement stereotype in the target environment. Property keys
            // set via analyst_properties are stored verbatim (no case-insensitive fallback), so
            // they must match the stereotype definition precisely or the value is written under
            // an unrecognized key and never shows up in Modelio's "Origin" field.
            analystProperties.put("Origin", requirement.origin);
            // Keep legacy lower/French-case keys too for backward compatibility with
            // environments whose stereotype defines the property differently.
            analystProperties.put("origin", requirement.origin);
            analystProperties.put("origine", requirement.origin);
        }

        return executeAnalystTool("REQ " + requirement.id, "create-requirement-" + requirement.id, "analyst_createElement",
                arguments, mapper, executionTrace);
    }

    private static String createRequirementContainer(String outputDirectory, ObjectMapper mapper, StringBuilder executionTrace)
            throws IOException {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("type", "RequirementContainer");
        arguments.put("name", DEFAULT_REQUIREMENT_CONTAINER_NAME);
        arguments.put("definition", DEFAULT_REQUIREMENT_CONTAINER_DEFINITION);
        // Explicitly set the container as modifiable to allow deletion and editing of contained elements
        arguments.put("isModifiable", true);

        String response = executeAnalystTool("CONTAINER auto-create", "create-requirement-container-" + System.nanoTime(),
                "analyst_createContainer", arguments, mapper, executionTrace);
        debug("MCP direct analyst_createContainer -> " + truncate(response, 400));

        String containerUuid = extractFirstRealUuid(response);
        if (containerUuid == null) {
            throw new IllegalStateException("Unable to create RequirementContainer automatically. "
                    + extractMcpErrorMessage(response, "No UUID returned by analyst_createContainer"));
        }

        if (outputDirectory != null) {
            Files.writeString(Path.of(outputDirectory).resolve("requirements_container_creation_report.txt"), response);
        }

        return containerUuid;
    }

    private static String executeAnalystTool(String traceLabel, String requestId, String toolName, ObjectNode arguments,
            ObjectMapper mapper, StringBuilder executionTrace) throws IOException {
        String argumentsJson = mapper.writeValueAsString(arguments);
        executionTrace.append(traceLabel).append(" args=").append(argumentsJson).append(System.lineSeparator());

        String response = sharedMcpClient.executeTool(ToolExecutionRequest.builder()
                .id(requestId)
                .name(toolName)
                .arguments(argumentsJson)
                .build());
        executionTrace.append(traceLabel).append(" response=")
                .append(truncate(response, 1200))
                .append(System.lineSeparator())
                .append(System.lineSeparator());
        return response;
    }

    private static boolean isMissingRequirementContainerError(String response) {
        return response != null && MISSING_REQUIREMENT_CONTAINER_PATTERN.matcher(response).find();
    }

    private static String extractMcpErrorMessage(String response, String fallbackMessage) {
        if (response == null || response.isBlank()) {
            return fallbackMessage;
        }

        try {
            JsonNode root = new ObjectMapper().readTree(response);
            if (root.path("isError").asBoolean(false)) {
                String message = root.path("message").asText(null);
                if (message != null && !message.isBlank()) {
                    return message;
                }
            }
        } catch (Exception e) {
            debug("⚠️ Could not parse MCP error payload: " + e.getMessage());
        }

        return fallbackMessage + " | response=" + truncate(response, 400);
    }

    private static String extractFirstRealUuid(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = REAL_UUID_PATTERN.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    /**
     * Crée le modèle de classes UML dans Modelio à partir des résultats d'analyse
     * Utilise 3 prompts séparés pour plus de clarté et de contrôle
     */
    public static String createUmlClassModel(String analysisResults, String outputDirectory, String mcpSseUrl, PolicyAwareAzureChatModel chatModel) {
        return createUmlClassModel(analysisResults, null, outputDirectory, mcpSseUrl, chatModel);
    }

    /**
     * Crée le modèle de classes UML dans Modelio à partir des résultats d'analyse
     * Utilise 3 prompts séparés pour plus de clarté et de contrôle
     *
     * @param existingRequirementsReport rapport JSON des exigences déjà créées dans Modelio
     *        (par exemple via {@link #createRequirementsInModelio}). Lorsqu'il est fourni,
     *        la PHASE 1 ne recrée pas les exigences (évite les doublons "Exigences" dans
     *        Modelio) et réutilise simplement ce rapport comme contexte pour les phases suivantes.
     */
    public static String createUmlClassModel(String analysisResults, String existingRequirementsReport, String outputDirectory, String mcpSseUrl, PolicyAwareAzureChatModel chatModel) {
        ensureInfrastructureInitialized(mcpSseUrl, chatModel);
        ensureMcpToolsAvailable();
        
        StringBuilder finalReport = new StringBuilder();
        finalReport.append("=== CRÉATION DU MODÈLE UML EN 3 PHASES ===\n\n");
        
        try {
            debug("🏗️ Creating UML model in Modelio using 3-phase approach...");
            
            // Récupération des requirements parsés depuis les documents d'analyse si disponibles
            List<Requirement> parsedRequirements = new ArrayList<>();
            String requirementsDocuments = "";
            
            // Extraire les documents d'analyse du PlantUML s'ils sont intégrés
            if (analysisResults != null && analysisResults.contains("EXTRACTED REQUIREMENTS")) {
                int startIdx = analysisResults.indexOf("EXTRACTED REQUIREMENTS");
                int endIdx = analysisResults.indexOf("@startuml", startIdx);
                if (endIdx == -1) endIdx = analysisResults.length();
                requirementsDocuments = analysisResults.substring(startIdx, endIdx);
                parsedRequirements = parseRequirementsFromDocuments(requirementsDocuments);
                debug("📋 Found " + parsedRequirements.size() + " parsed requirements in analysis results");
            }
            
            // PHASE 1 : Création des Requirements
            String requirementsResult;
            if (existingRequirementsReport != null && !existingRequirementsReport.isBlank()) {
                // Les exigences ont déjà été créées dans Modelio par une étape précédente
                // (ex: PipelineRunner appelle createRequirementsInModelio avant createUmlClassModel).
                // Ne PAS les recréer ici : cela provoquait des exigences dupliquées dans Modelio
                // et pouvait déclencher un échec MCP_EXECUTION_FAILED sur des modèles volumineux.
                debug("📋 PHASE 1: Reusing requirements already created in Modelio (skipping duplicate creation)...");
                requirementsResult = existingRequirementsReport;
            } else {
                debug("📋 PHASE 1: Creating Requirements...");
                if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
                    requirementsResult = createRequirementsDirectlyViaMcp(parsedRequirements, outputDirectory);
                } else {
                    String requirementsPrompt = createRequirementsPrompt(analysisResults, parsedRequirements, requirementsDocuments);

                    PooledUmlAssistant pa1 = borrowAssistant();
                    if (pa1 == null) {
                        return "❌ Could not borrow assistant for requirements creation";
                    }

                    try {
                        requirementsResult = executeAssistantWithMcpTrace(pa1, "requirements_phase", requirementsPrompt, outputDirectory);
                    } finally {
                        try {
                            ASSISTANT_POOL.offer(pa1);
                        } catch (Exception e) {
                            debug("Warning: Could not return assistant to pool: " + e.getMessage());
                        }
                    }
                }
            }
            validateMcpExecutionResult("requirements_phase", requirementsResult, "requirements_created", "exigences_creees");
            finalReport.append("PHASE 1 - REQUIREMENTS:\n").append(requirementsResult).append("\n\n");
             
            List<String> requirementsUUIDs = extractRequirementUUIDs(requirementsResult);
            String requirementsJSON = extractJSONStructure(requirementsResult, "requirements_created", "exigences_creees");
            
            debug("✅ Requirements creation completed - UUIDs extracted: " + requirementsUUIDs.size());
            if (requirementsJSON != null) {
                debug("📊 Requirements JSON structure extracted successfully");
            }
            
            // PHASE 2 : Création des Classes et Associations
            debug("🏛️ PHASE 2: Creating Classes and Associations...");
            // Extract only requirement element UUIDs (exclude package/container UUIDs)
            List<String> requirementUUIDs = extractRequirementUUIDs(requirementsResult);
            debug("📋 Extracted " + requirementUUIDs.size() + " requirement UUIDs from Phase 1 for Phase 2 linking");
            String classesPrompt = createClassesPrompt(analysisResults, requirementsResult, parsedRequirements, requirementUUIDs);
            
            PooledUmlAssistant pa2 = borrowAssistant();
            if (pa2 == null) {
                return "❌ Could not borrow assistant for classes creation";
            }
            
            String classesResult;
            try {
                classesResult = executeAssistantWithMcpTraceWithRetry(pa2, "domain_model_phase", classesPrompt, outputDirectory, 2);
                classesResult = ensureStructuredDomainModelResult(classesResult);
                validateMcpExecutionResult("domain_model_phase", classesResult, "domain_model_created", "modele_domaine_cree");
                finalReport.append("PHASE 2 - CLASSES & ASSOCIATIONS:\n").append(classesResult).append("\n\n");
                 
                // 🔍 EXTRACTION AUTOMATIQUE DES STRUCTURES  
                List<String> classesUUIDs = extractUUIDs(classesResult);
                String domainModelJSON = extractJSONStructure(classesResult, "domain_model_created", "modele_domaine_cree");
                String asBuildPlantUML = extractPlantUMLDiagram(classesResult, "AS_BUILT_DOMAIN_MODEL");
                
                debug("✅ Classes and associations creation completed - UUIDs extracted: " + classesUUIDs.size());
                if (domainModelJSON != null) {
                    debug("📊 Domain model JSON structure extracted successfully");
                }
                if (asBuildPlantUML != null) {
                    debug("🎯 As-built PlantUML diagram extracted successfully");
                }
                
            } finally {
                try {
                    ASSISTANT_POOL.offer(pa2);
                } catch (Exception e) {
                    debug("Warning: Could not return assistant to pool: " + e.getMessage());
                }
            }
            
            // PHASE 3 : Création des Use Cases et Actors
            debug("👥 PHASE 3: Creating Use Cases and Actors...");
            String useCasesPrompt = createUseCasesPrompt(analysisResults, requirementsResult, classesResult, parsedRequirements, requirementUUIDs);
            
            PooledUmlAssistant pa3 = borrowAssistant();
            if (pa3 == null) {
                return "❌ Could not borrow assistant for use cases creation";
            }
            
            String useCasesResult;
            try {
                useCasesResult = executeAssistantWithMcpTrace(pa3, "use_cases_phase", useCasesPrompt, outputDirectory);
                validateMcpExecutionResult("use_cases_phase", useCasesResult, "use_cases_created");
                finalReport.append("PHASE 3 - USE CASES & ACTORS:\n").append(useCasesResult).append("\n\n");
                
                // 🔍 EXTRACTION AUTOMATIQUE DES STRUCTURES
                List<String> useCasesUUIDs = extractUUIDs(useCasesResult);
                String useCasesJSON = extractJSONStructure(useCasesResult, "use_cases_created");
                String useCasePlantUML = extractPlantUMLDiagram(useCasesResult, "USE_CASES_DIAGRAM");
                
                debug("✅ Use cases and actors creation completed - UUIDs extracted: " + useCasesUUIDs.size());
                if (useCasesJSON != null) {
                    debug("📊 Use cases JSON structure extracted successfully");
                }
                if (useCasePlantUML != null) {
                    debug("🎯 Use case diagram extracted successfully");
                }
                
            } finally {
                try {
                    ASSISTANT_POOL.offer(pa3);
                } catch (Exception e) {
                    debug("Warning: Could not return assistant to pool: " + e.getMessage());
                }
            }
            
            // Résumé final
            finalReport.append("=== RÉSUMÉ FINAL ===\n");
            finalReport.append("✅ PHASE 1: Requirements créés\n");
            finalReport.append("✅ PHASE 2: Classes et associations créées\n");
            finalReport.append("✅ PHASE 3: Use cases et actors créés\n");
            finalReport.append("🎯 Modèle UML complet généré avec succès!");
            
            // Sauvegarder le rapport complet
            if (outputDirectory != null) {
                Files.writeString(Path.of(outputDirectory).resolve("uml_model_3phase_report.txt"), finalReport.toString());
            }
            
            debug("✅ UML model creation completed using 3-phase approach");
            return finalReport.toString();
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            debug("❌ UML model creation interrupted: " + e.getMessage());
            return "❌ Error in 3-phase UML model creation: interrupted while waiting for Modelio MCP";
        } catch (Exception e) {
            debug("❌ Error in 3-phase UML model creation: " + e.getMessage());
            return "❌ Error in 3-phase UML model creation: " + e.getMessage();
        }
    }

    // Injecter des extraits de ressources MCP sélectionnées dans la mémoire de chat.
    private static void injectMcpResources(ChatMemory chatMemory) {
        if (cachedResources == null || cachedResources.isEmpty()) return;
        
        // sélection simple : premières 3 ressources texte
        List<McpResource> selected = cachedResources.stream().limit(3).collect(Collectors.toList());
        int injected = 0;
        
        for (McpResource res : selected) {
            try {
                McpReadResourceResult rr = sharedMcpClient.readResource(res.uri());
                if (rr == null || rr.contents() == null) continue;
                
                for (McpResourceContents c : rr.contents()) {
                    if (c instanceof McpTextResourceContents) {
                        String text = ((McpTextResourceContents) c).text();
                        if (text == null || text.isEmpty()) continue;
                        if (text.length() > 4000) text = text.substring(0, 4000) + "\n...(truncated)";
                        chatMemory.add(SystemMessage.from("MCP Resource: " + res.name() + " (" + res.uri() + ")\n" + text));
                        injected++;
                        break; // seulement le premier contenu texte
                    }
                }
            } catch (Throwable t) {
                debug("read resource failed: " + res.uri() + " -> " + t.getMessage());
            }
        }
        
        if (injected > 0) debug("Injected " + injected + " MCP resource snippet(s)");
    }
    
    /**
     * Parse automatiquement les exigences à partir des documents d'analyse
     * Supporte plusieurs formats courants d'exigences
     */
    private static List<Requirement> parseRequirementsFromDocuments(String documentsText) {
        List<Requirement> requirements = new ArrayList<>();
        
        if (documentsText == null || documentsText.trim().isEmpty()) {
            debug("parseRequirementsFromDocuments: documentsText is null or empty");
            return requirements;
        }
        
        debug("parseRequirementsFromDocuments: input length = " + documentsText.length());
        debug("parseRequirementsFromDocuments: using AI-based requirements filtering instead of regex patterns");
        
        try {
            // Utiliser le Requirements Filter Agent intelligent au lieu des regex permissives
            String requirementsFilterPrompt = """
                🇫🇷 IMPORTANT : RÉPONDEZ UNIQUEMENT EN FRANÇAIS. Vous êtes un expert en identification d'exigences système. Votre mission est de FILTRER le texte pour ne conserver QUE les vraies exigences opérationnelles.
                
                CRITÈRES STRICTS pour qu'un élément soit une VRAIE exigence :
                ✅ ACCEPTER : Exigences qui décrivent des capacités, contraintes, ou comportements spécifiques du système
                - "Le système doit permettre..."
                - "L'application doit supporter..."
                - "La base de données doit gérer..."
                - "L'interface doit afficher..."
                - "Le temps de réponse doit être inférieur à..."
                - "Les données doivent être chiffrées..."
                
                ❌ REJETER ABSOLUMENT : Tout ce qui N'EST PAS une exigence concrète
                - Titres de sections ("Objectif du Document", "Fonctionnalités Principales", "Spécifications Techniques")
                - Descriptions génériques ("Ce chapitre présente...")
                - Références bibliographiques
                - Artefacts de formatage (**Pour EX-XXX**, ***Note***, etc.)
                - Résumés ou conclusions
                
                🇫🇷 OBLIGATOIRE : Toutes les descriptions extraites DOIVENT être en français.
                
                FORMAT DE SORTIE - JSON uniquement avec descriptions en français :
                {
                  "filtered_requirements": [
                    {
                      "id": "EXG-001",
                      "original_ref": "EX-015",
                      "description": "Le système doit permettre l'authentification des utilisateurs via SSO",
                      "category": "Sécurité",
                      "priority": "Haute",
                      "context": "Section Sécurité des accès",
                      "source_location": "Chapitre 4.2, page 12",
                      "source_quote": "Le soumissionnaire doit garantir une authentification SSO..."
                    }
                  ],
                  "rejected_items": ["Objectif du document", "Fonctionnalités principales"],
                  "statistics": {
                    "total_items_analyzed": 45,
                    "requirements_retained": 23,
                    "items_rejected": 22
                  }
                }
                
                Texte à filtrer :
                """;
            
            // Obtenir une instance temporaire d'assistant pour le filtrage
            PooledUmlAssistant filterAssistant = borrowAssistant();
            if (filterAssistant == null) {
                debug("❌ Could not borrow assistant for requirements filtering");
                return requirements; // fallback vide
            }
            
            try {
                // Exécuter le filtrage IA en utilisant createUmlModel
                String filteredResponse = filterAssistant.assistant.createUmlModel(requirementsFilterPrompt + documentsText);
                debug("AI filter response length: " + filteredResponse.length());
                
                // Parser le JSON de réponse  
                String filteredJson = JsonUtils.extractFirstJson(filteredResponse);
                if (filteredJson == null) {
                    filteredJson = filteredResponse;
                }
                
                // Parser le JSON avec Jackson
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(filteredJson);
                
                if (root.has("filtered_requirements")) {
                    JsonNode filteredReqs = root.get("filtered_requirements");
                    if (filteredReqs.isArray()) {
                        for (JsonNode reqNode : filteredReqs) {
                            String id = normalizeRequirementId(reqNode.path("id").asText(null), requirements.size() + 1);
                            String description = reqNode.get("description").asText("");
                            String category = reqNode.get("category").asText("Fonctionnel");
                            String priority = reqNode.get("priority").asText("Moyenne");
                            String origin = buildRequirementOrigin(reqNode, "Documents d'analyse", description);
                            
                            if (!description.trim().isEmpty()) {
                                requirements.add(new Requirement(id, id, description, category, priority, origin));
                            }
                        }
                    }
                }
                
                // Log des statistiques si disponibles
                if (root.has("statistics")) {
                    JsonNode stats = root.get("statistics");
                    int total = stats.get("total_items_analyzed").asInt(0);
                    int retained = stats.get("requirements_retained").asInt(0);
                    int rejected = stats.get("items_rejected").asInt(0);
                    
                    debug("📊 AI Requirements filtering statistics:");
                    debug("   - Total items analyzed: " + total);
                    debug("   - True requirements retained: " + retained);
                    debug("   - False positives rejected: " + rejected);
                    debug("   - Retention rate: " + (total > 0 ? (retained * 100 / total) : 0) + "%");
                }
                
            } finally {
                // Retourner l'assistant au pool
                try {
                    ASSISTANT_POOL.offer(filterAssistant);
                } catch (Exception e) {
                    debug("Warning: Could not return assistant to pool: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            debug("Erreur lors du filtrage IA des exigences: " + e.getMessage());
            e.printStackTrace();
        }
        
        debug("parseRequirementsFromDocuments: extracted " + requirements.size() + " requirements using AI filtering");
        return requirements;
    }

    /** 
                Pattern.DOTALL
            );
            
            // Pattern 3: Format "Exigence 001: Description" ou "Requirement 001: Description"
            Pattern reqPattern3 = Pattern.compile(
                "(?i)(Exigence|Requirement)\\s+(\\d+)\\s*[:.] *(.+?)(?=(?:Exigence|Requirement)\\s+\\d+|$)", 
                Pattern.DOTALL
            );
            
            // Pattern 4: Format numéroté simple "1. Description", "2. Description"
            Pattern reqPattern4 = Pattern.compile(
                "(?m)^\\s*(\\d+)\\. *(.+?)(?=^\\s*\\d+\\.|$)", 
                Pattern.DOTALL
            );
            
            // Pattern 5: Lignes qui commencent par des mots-clés d'exigences
            Pattern reqPattern5 = Pattern.compile(
                "(?im)^\\s*(Le système|The system|L'application|The application|Il faut|Must|Shall).{10,}$"
            );
            
            // Pattern 6: Format du pipeline ModelioAlchemist "EX-001: description"
            Pattern reqPattern6 = Pattern.compile(
                "(EX-\\d+)\\s*[:.] *(.+?)(?=EX-\\d+|$)", 
                Pattern.DOTALL
            );
            
            // Pattern 7: Lignes débutant par des puces ou numéros dans les rapports
            Pattern reqPattern7 = Pattern.compile(
                "(?m)^\\s*[-•*]\\s*(.{20,})$"
            );
            
            debug("Tentative d'extraction avec Pattern 1 (REQ001:)");
            extractWithPattern(documentsText, reqPattern1, requirements, "REQ");
            
            if (requirements.isEmpty()) {
                debug("Tentative d'extraction avec Pattern 2 ([REQ-001])");
                extractWithPattern(documentsText, reqPattern2, requirements, "BRACKET");
            }
            
            if (requirements.isEmpty()) {
                debug("Tentative d'extraction avec Pattern 3 (Exigence 001:)");
                extractWithPattern(documentsText, reqPattern3, requirements, "WORD");
            }
            
            if (requirements.isEmpty()) {
                debug("Tentative d'extraction avec Pattern 4 (1. Description)");
                extractWithPattern(documentsText, reqPattern4, requirements, "NUMBERED");
            }
            
            if (requirements.isEmpty()) {
                debug("Tentative d'extraction avec Pattern 5 (phrases d'exigences)");
                extractRequirementSentences(documentsText, reqPattern5, requirements);
            }
            
            if (requirements.isEmpty()) {
                debug("Tentative d'extraction avec Pattern 6 (EX-001: format pipeline)");
                extractWithPattern(documentsText, reqPattern6, requirements, "PIPELINE");
            }
            
            if (requirements.isEmpty()) {
                debug("Tentative d'extraction avec Pattern 7 (listes à puces)");
                extractRequirementSentences(documentsText, reqPattern7, requirements);
            }
            
            // Post-traitement : nettoyer les descriptions
            for (int i = 0; i < requirements.size(); i++) {
                Requirement req = requirements.get(i);
                String cleanDesc = cleanRequirementDescription(req.description);
                String category = detectRequirementCategory(cleanDesc);
                String priority = detectRequirementPriority(cleanDesc);
                
                requirements.set(i, new Requirement(req.id, req.title, cleanDesc, category, priority, req.origin));
            }
            
            debug("Extraction terminée: " + requirements.size() + " exigences trouvées");
            
            // Debug : afficher les premières exigences trouvées
            for (int i = 0; i < Math.min(3, requirements.size()); i++) {
                debug("Requirement " + i + ": " + requirements.get(i).toString());
            }
            
        } catch (Exception e) {
            debug("Erreur lors du parsing des exigences: " + e.getMessage());
            e.printStackTrace();
        }
        
        return requirements;
    }
    
    /**
     * Extrait les exigences avec un pattern donné
     */
    private static void extractWithPattern(String text, Pattern pattern, List<Requirement> requirements, String patternType) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        
        while (matcher.find() && count < 100) { // Limite pour éviter les boucles infinies
            String id, description;
            
            switch (patternType) {
                case "REQ":
                case "BRACKET":
                case "PIPELINE":
                    id = normalizeRequirementId(matcher.group(1).trim(), requirements.size() + 1);
                    description = matcher.group(2).trim();
                    break;
                case "WORD":
                    id = formatRequirementId(Integer.parseInt(matcher.group(2)));
                    description = matcher.group(3).trim();
                    break;
                case "NUMBERED":
                    id = formatRequirementId(Integer.parseInt(matcher.group(1)));
                    description = matcher.group(2).trim();
                    break;
                default:
                    continue;
            }
            
            if (!description.isEmpty() && description.length() > 10) {
                requirements.add(new Requirement(id, description));
                count++;
            }
        }
        
        debug("Pattern " + patternType + " a trouvé " + count + " exigences");
    }
    
    /**
     * Extrait les phrases qui ressemblent à des exigences
     */
    private static void extractRequirementSentences(String text, Pattern pattern, List<Requirement> requirements) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        
        while (matcher.find() && count < 50) {
            String sentence = matcher.group().trim();
            
            if (sentence.length() > 20 && sentence.length() < 500) {
                String id = formatRequirementId(requirements.size() + 1);
                requirements.add(new Requirement(id, sentence));
                count++;
            }
        }
        
        debug("Pattern sentences a trouvé " + count + " exigences");
    }
    
    /**
     * Nettoie la description d'une exigence
     */
    private static String cleanRequirementDescription(String description) {
        if (description == null) return "";
        
        return description
            .replaceAll("\\s+", " ")  // Normaliser les espaces
            .replaceAll("[\\r\\n]+", " ")  // Supprimer les retours à la ligne
            .replaceAll("(?i)(priorité|priority)\\s*[:=]\\s*\\w+", "")  // Supprimer info priorité
            .replaceAll("(?i)(catégorie|category)\\s*[:=]\\s*\\w+", "")  // Supprimer info catégorie
            .trim();
    }

    private static String buildRequirementOrigin(JsonNode reqNode, String sourceDocumentName, String fallbackDescription) {
        List<String> originParts = new ArrayList<>();
        if (sourceDocumentName != null && !sourceDocumentName.isBlank()) {
            originParts.add("document=" + sourceDocumentName.trim());
        }

        String originalRef = reqNode.path("original_ref").asText("").trim();
        if (!originalRef.isEmpty()) {
            originParts.add("ref=" + originalRef);
        }

        String sourceLocation = reqNode.path("source_location").asText("").trim();
        if (sourceLocation.isEmpty()) {
            sourceLocation = reqNode.path("location").asText("").trim();
        }
        if (sourceLocation.isEmpty()) {
            sourceLocation = reqNode.path("page").asText("").trim();
        }
        if (!sourceLocation.isEmpty()) {
            originParts.add("location=" + sourceLocation);
        }

        String context = reqNode.path("context").asText("").trim();
        if (!context.isEmpty()) {
            originParts.add("context=" + context);
        }

        String sourceQuote = reqNode.path("source_quote").asText("").trim();
        if (sourceQuote.isEmpty()) {
            sourceQuote = reqNode.path("excerpt").asText("").trim();
        }
        if (sourceQuote.isEmpty()) {
            sourceQuote = reqNode.path("origin_excerpt").asText("").trim();
        }
        if (!sourceQuote.isEmpty()) {
            originParts.add("quote=" + truncate(sourceQuote.replaceAll("\\s+", " "), 240));
        }

        if (originParts.isEmpty()) {
            String fallback = fallbackDescription == null ? "" : fallbackDescription.replaceAll("\\s+", " ").trim();
            if (!fallback.isEmpty()) {
                originParts.add("description=" + truncate(fallback, 120));
            }
        }
        return String.join(" | ", originParts);
    }

    private static String normalizeRequirementId(String rawId, int fallbackIndex) {
        if (rawId != null) {
            Matcher idMatcher = REQUIREMENT_ID_PATTERN.matcher(rawId.trim());
            if (idMatcher.find()) {
                return formatRequirementId(Integer.parseInt(idMatcher.group(1)));
            }
        }
        return formatRequirementId(fallbackIndex);
    }

    private static String formatRequirementId(int numericId) {
        int safeNumericId = Math.max(1, numericId);
        return CANONICAL_REQUIREMENT_PREFIX + "-" + String.format("%03d", safeNumericId);
    }
    
    /**
     * Détecte la catégorie d'une exigence basée sur son contenu
     */
    private static String detectRequirementCategory(String description) {
        String lowerDesc = description.toLowerCase();
        
        if (lowerDesc.contains("sécurité") || lowerDesc.contains("security") || 
            lowerDesc.contains("authentification") || lowerDesc.contains("authorization") ||
            lowerDesc.contains("chiffrement") || lowerDesc.contains("encryption")) {
            return "Sécurité";
        }
        
        if (lowerDesc.contains("performance") || lowerDesc.contains("temps de réponse") ||
            lowerDesc.contains("response time") || lowerDesc.contains("débit") ||
            lowerDesc.contains("throughput") || lowerDesc.contains("latence")) {
            return "Performance";
        }
        
        if (lowerDesc.contains("interface") || lowerDesc.contains("ui") || 
            lowerDesc.contains("utilisateur") || lowerDesc.contains("user") ||
            lowerDesc.contains("ergonomie") || lowerDesc.contains("usability")) {
            return "Interface";
        }
        
        if (lowerDesc.contains("intégration") || lowerDesc.contains("integration") ||
            lowerDesc.contains("api") || lowerDesc.contains("service") ||
            lowerDesc.contains("connexion") || lowerDesc.contains("connection")) {
            return "Intégration";
        }
        
        return "Fonctionnel";  // Par défaut
    }
    
    /**
     * Détecte la priorité d'une exigence basée sur son contenu
     */
    private static String detectRequirementPriority(String description) {
        String lowerDesc = description.toLowerCase();
        
        if (lowerDesc.contains("critique") || lowerDesc.contains("critical") ||
            lowerDesc.contains("obligatoire") || lowerDesc.contains("mandatory") ||
            lowerDesc.contains("essentiel") || lowerDesc.contains("essential")) {
            return "Haute";
        }
        
        if (lowerDesc.contains("optionnel") || lowerDesc.contains("optional") ||
            lowerDesc.contains("souhaitable") || lowerDesc.contains("nice to have") ||
            lowerDesc.contains("bonus")) {
            return "Basse";
        }
        
        return "Moyenne";  // Par défaut
    }
    
    /**
     * Méthode de debug pour sauvegarder le contenu dans un fichier
     */
    private static void saveDebugFile(String content, String filename) {
        saveDebugFile(content, filename, null);
    }
    
    /**
     * Méthode de debug pour sauvegarder le contenu dans un fichier avec répertoire optionnel
     */
    private static void saveDebugFile(String content, String filename, String outputDirectory) {
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

    /**
     * Génère le prompt spécialisé pour la création des requirements (PHASE 1)
     */
    private static String createRequirementsPrompt(String analysisResults, List<Requirement> parsedRequirements, String requirementsDocuments) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("🇷🇷 Vous êtes un analyste d'exigences Modelio. Votre mission : créer TOUTES les exigences en français dans Modelio.\n\n");
        
        prompt.append("## MISSION PHASE 1 : CRÉATION DES EXIGENCES\n");
        prompt.append("🎯 Créer des exigences complètes en français basées sur l'analyse et PlantUML.\n");
        prompt.append("🚨 Utiliser les outils MCP pour créer CHAQUE exigence comme élément d'analyse.\n\n");
        
        // Section requirements parsés si disponible
        if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
            prompt.append("## EXIGENCES PARSÉES (PRIORITÉ)\n");
            prompt.append("Les " + parsedRequirements.size() + " exigences suivantes ont été extraites des documents d'analyse :\n\n");
            
            for (int i = 0; i < Math.min(parsedRequirements.size(), 20); i++) {
                Requirement req = parsedRequirements.get(i);
                prompt.append(String.format("**%s**: %s\n", req.id, req.description));
                prompt.append(String.format("  - Catégorie: %s | Priorité: %s\n\n", req.category, req.priority));
            }
            
            if (parsedRequirements.size() > 20) {
                prompt.append("... et " + (parsedRequirements.size() - 20) + " exigences supplémentaires\n\n");
            }
            
            prompt.append("🚨 OBLIGATOIRE : Créer CHACUNE de ces exigences EXACTEMENT avec les outils MCP.\n\n");
        }
        
        // Section documents d'analyse si disponible
        if (requirementsDocuments != null && !requirementsDocuments.trim().isEmpty()) {
            prompt.append("## CONTEXTE DES DOCUMENTS D'ANALYSE\n");
            if (requirementsDocuments.length() > 2000) {
                prompt.append(requirementsDocuments.substring(0, 2000)).append("\n... (contexte tronqué)\n\n");
            } else {
                prompt.append(requirementsDocuments).append("\n\n");
            }
        }
        
        prompt.append("## Analyse PlantUML à traiter\n");
        prompt.append("```\n");
        if (analysisResults != null && analysisResults.length() > 6000) {
            prompt.append(analysisResults.substring(0, 6000)).append("\n... (PlantUML tronqué)");
        } else {
            prompt.append(analysisResults);
        }
        prompt.append("\n```\n\n");
        
        prompt.append("## INSTRUCTIONS D'EXÉCUTION\n");
        prompt.append("🚨 OBLIGATOIRE : Utiliser les outils MCP de création d'exigences pour CHAQUE exigence\n");
        
        if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
            prompt.append("- Créer les " + parsedRequirements.size() + " exigences parsées EXACTEMENT comme spécifié\n");
            prompt.append("- Utiliser les ID, descriptions, catégories et priorités fournis\n");
        } else {
            prompt.append("- Extraire 8-15 exigences complètes du PlantUML\n");
            prompt.append("- Utiliser le format : 'EXG-001 : Le système doit gérer l'authentification utilisateur'\n");
        }
        
        prompt.append("- Créer les exigences comme éléments d'analyse dans Modelio\n");
        prompt.append("- Rapporter l'UUID de chaque exigence créée pour la traçabilité\n");
        prompt.append("- Organiser dans le package 'Exigences'\n\n");
        
        prompt.append("📊 **CRITIQUE : PRODUIRE UNE SORTIE STRUCTURÉE**\n");
        prompt.append("Après avoir créé toutes les exigences, générer ce format EXACT :\n\n");
        prompt.append("```json\n");
        prompt.append("{\n");
        prompt.append("  \"requirements_created\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"id\": \"EXG-001\",\n");
        prompt.append("      \"uuid\": \"00000000-0000-0000-0000-000000000000\",\n");
        prompt.append("      \"description\": \"Le système doit...\",\n");
        prompt.append("      \"categorie\": \"Fonctionnelle\",\n");
        prompt.append("      \"priorite\": \"Haute\",\n");
        prompt.append("      \"elements_plantuml\": [\"Utilisateur\", \"Authentification\"]\n");
        prompt.append("    }\n");
        prompt.append("  ],\n");
        prompt.append("  \"total_requirements\": 12,\n");
        prompt.append("  \"package_uuid\": \"00000000-0000-0000-0000-000000000000\"\n");
        prompt.append("}\n");
        prompt.append("```\n\n");
        prompt.append("🔗 **elements_plantuml** : Lister les classes/acteurs PlantUML liés à cette exigence\n\n");
        
        prompt.append("## CRITÈRES DE RÉUSSITE\n");
        prompt.append("✅ Toutes les exigences couvertes par des éléments d'analyse appropriés\n");
        prompt.append("✅ UUIDs rapportés pour toutes les exigences créées\n");
        prompt.append("✅ Exigences correctement catégorisées et priorisées\n");
        prompt.append("✅ Toutes les descriptions en français\n\n");
        
        prompt.append("NE FOURNISSEZ PAS DE PROCÉDURE MANUELLE. COMMENCEZ MAINTENANT : créez les exigences avec les outils MCP et retournez uniquement le JSON demandé.");
        
        return prompt.toString();
    }
    
    /**
     * Génère le prompt spécialisé pour la création des classes et associations (PHASE 2)
     */
    private static String createClassesPrompt(String analysisResults, String requirementsResult, List<Requirement> parsedRequirements, List<String> requirementUUIDs) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("🇫🇷 Vous êtes un modélisateur de domaine Modelio. Votre mission : créer TOUTES les classes et associations en français.\n\n");
        
        prompt.append("## MISSION PHASE 2 : CLASSES & ASSOCIATIONS\n");
        prompt.append("🎯 Créer un modèle de domaine complet en français à partir du PlantUML avec toutes les relations.\n");
        prompt.append("🚨 Utiliser les outils MCP pour créer classes, attributs et associations.\n\n");
        
        // Contexte des requirements créés
        prompt.append("## TRAÇABILITÉ - EXIGENCES CRÉÉES\n");
        if (requirementsResult != null && !requirementsResult.trim().isEmpty()) {
            if (requirementsResult.length() > 1500) {
                prompt.append(requirementsResult.substring(0, 1500)).append("\n... (contexte exigences tronqué)\n\n");
            } else {
                prompt.append(requirementsResult).append("\n\n");
            }
            
            prompt.append("🔗 MAINTENIR LA TRAÇABILITÉ : Lier les classes aux exigences pertinentes lors de leur création.\n\n");
        }
        
        // ✨ NEW: Add requirement UUIDs for direct linking (avoid keyword search failures)
        if (requirementUUIDs != null && !requirementUUIDs.isEmpty()) {
            prompt.append("## UUIDS DES EXIGENCES POUR LIEN DIRECT (À UTILISER OBLIGATOIREMENT)\n");
            prompt.append("🚨 NE PAS CHERCHER LES EXIGENCES PAR MOTS-CLÉS! Utiliser directement ces UUIDs pour créer les liens «Satisfait»:\n\n");
            for (int i = 0; i < requirementUUIDs.size(); i++) {
                prompt.append(String.format("- UUID exigence #%d: %s\n", (i + 1), requirementUUIDs.get(i)));
            }
            prompt.append("\n🚨 CRITIQUE : Lors de la création de chaque classe, appeler analyst_createRelation IMMÉDIATEMENT\n");
            prompt.append("   avec relation_type=\"satisfy\", source_uuid=<UUID classe>, target_uuid=<l'un des UUIDs exigences ci-dessus>\n");
            prompt.append("   NE PAS attendre la fin. Lier chaque classe AU FUR ET À MESURE.\n\n");
        }
        
        // Contexte des requirements parsés pour traçabilité
        if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
            prompt.append("## CONTEXTE DES EXIGENCES POUR TRAÇABILITÉ\n");
            prompt.append("Considérer ces exigences lors de la création des classes :\n");
            
            for (int i = 0; i < Math.min(parsedRequirements.size(), 10); i++) {
                Requirement req = parsedRequirements.get(i);
                prompt.append(String.format("- %s: %s\n", req.id, 
                    req.description.length() > 80 ? req.description.substring(0, 80) + "..." : req.description));
            }
            
            if (parsedRequirements.size() > 10) {
                prompt.append("... et " + (parsedRequirements.size() - 10) + " exigences supplémentaires\n");
            }
            prompt.append("\n");
        }
        
        prompt.append("## PlantUML à implémenter\n");
        prompt.append("```plantuml\n");
        if (analysisResults != null && analysisResults.length() > 8000) {
            prompt.append(analysisResults.substring(0, 8000)).append("\n... (PlantUML tronqué)");
        } else {
            prompt.append(analysisResults);
        }
        prompt.append("\n```\n\n");
        
        prompt.append("## SÉQUENCE D'EXÉCUTION (OBLIGATOIRE)\n");
        prompt.append("1️⃣ **Créer les Packages** : Utiliser les outils MCP\n");
        prompt.append("   - Créer le package 'Modèle de Domaine' pour toutes les classes\n");
        prompt.append("   - Rapporter l'UUID du package\n\n");
        
        prompt.append("2️⃣ **Créer les Classes** : Utiliser les outils MCP UNE PAR UNE\n");
        prompt.append("   - Parser TOUTES les classes du PlantUML\n");
        prompt.append("   - Conserver les noms exacts du PlantUML\n");
        prompt.append("   - Créer dans le package 'Modèle de Domaine'\n");
        prompt.append("   - Rapporter l'UUID de chaque classe\n");
        prompt.append("   - 🚨 OBLIGATOIRE : pour chaque classe liée à une exigence, matérialiser le lien «Satisfait» avec l'outil MCP\n");
        prompt.append("     `analyst_createRelation` (relation_type=\"satisfy\", source_uuid=<UUID de la classe>, target_uuid=<UUID de l'exigence>,\n");
        prompt.append("     module_name=\"ModelerModule\") en un seul appel.\n");
        prompt.append("     Sans cette dépendance «Satisfait» réelle dans le modèle, la classe N'EST PAS considérée comme tracée.\n\n");
        
        prompt.append("3️⃣ **Ajouter les Attributs** : Utiliser les outils MCP POUR CHAQUE CLASSE\n");
        prompt.append("   - Ajouter TOUS les attributs du PlantUML\n");
        prompt.append("   - Utiliser les types : String, int, boolean, float (compatibles Modelio)\n");
        prompt.append("   - NE JAMAIS utiliser : Date, Integer, Boolean\n");
        prompt.append("   - Attendre la confirmation de création de classe avant d'ajouter les attributs\n\n");
        
        prompt.append("4️⃣ **Créer les Associations** : Utiliser les outils MCP POUR CHAQUE RELATION\n");
        prompt.append("   - Parser CHAQUE relation : -->, --|>, --o, --*, <|--\n");
        prompt.append("   - Créer les associations SEULEMENT après que toutes les classes existent\n");
        prompt.append("   - Définir les cardinalités appropriées (1, 0..1, 1..*, 0..*)\n");
        prompt.append("   - Nommer les relations de manière significative\n");
        prompt.append("   - 🚨 NE PAS IGNORER AUCUNE ASSOCIATION\n\n");
        
        prompt.append("📊 **CRITIQUE : PRODUIRE UNE SORTIE AS-BUILT**\n");
        prompt.append("Après création du modèle de domaine, générer :\n\n");
        prompt.append("### 1. DIAGRAMME PLANTUML AS-BUILT\n");
        prompt.append("```plantuml\n");
        prompt.append("@startuml MODELE_DOMAINE_AS_BUILT\n");
        prompt.append("' Générer le PlantUML EXACT de ce qui a été créé dans Modelio\n");
        prompt.append("class Utilisateur {\n");
        prompt.append("  +nom: String\n");
        prompt.append("  +email: String\n");
        prompt.append("}\n");
        prompt.append("' Inclure TOUTES les classes, attributs et associations créés\n");
        prompt.append("@enduml\n");
        prompt.append("```\n\n");
        prompt.append("### 2. JSON DU MODÈLE DE DOMAINE STRUCTURÉ\n");
        prompt.append("```json\n");
        prompt.append("{\n");
        prompt.append("  \"domain_model_created\": {\n");
        prompt.append("    \"package_uuid\": \"00000000-0000-0000-0000-000000000000\",\n");
        prompt.append("    \"classes\": [\n");
        prompt.append("      {\n");
        prompt.append("        \"nom\": \"Utilisateur\",\n");
        prompt.append("        \"uuid\": \"00000000-0000-0000-0000-000000000000\",\n");
        prompt.append("        \"attributs\": [{\"nom\": \"email\", \"type\": \"String\"}],\n");
        prompt.append("        \"exigences_liees\": [\"EXG-001\", \"EXG-003\"]\n");
        prompt.append("      }\n");
        prompt.append("    ],\n");
        prompt.append("    \"associations\": [\n");
        prompt.append("      {\"de\": \"Utilisateur\", \"vers\": \"Commande\", \"type\": \"Association\", \"cardinalite\": \"1..*\"}\n");
        prompt.append("    ]\n");
        prompt.append("  }\n");
        prompt.append("}\n");
        prompt.append("```\n\n");
        
        prompt.append("## CRITICAL TYPE RULES\n");
        prompt.append("✅ ALLOWED: String, int, boolean, float\n");
        prompt.append("❌ FORBIDDEN: Date, Integer, Boolean, LocalDate\n");
        prompt.append("🔄 FALLBACK: Use String for complex types\n\n");
        
        prompt.append("## ASSOCIATION TYPES\n");
        prompt.append("- --> = Association\n");
        prompt.append("- --|> = Generalization\n");
        prompt.append("- --o = Aggregation\n");
        prompt.append("- --* = Composition\n\n");
        
        prompt.append("## RÈGLE DE TRAÇABILITÉ OBLIGATOIRE («Satisfait»)\n");
        prompt.append("🚨 Chaque élément de modélisation créé (classe, etc.) qui répond à une exigence DOIT être relié à celle-ci\n");
        prompt.append("   par une relation de Dépendance stéréotypée «Satisfait» (stéréotype défini par le profil Modelio Analyst).\n");
        prompt.append("   - Appel MCP exact à exécuter dès que l'élément et l'exigence existent (UUIDs réels requis) :\n");
        prompt.append("     `analyst_createRelation` avec relation_type=\"satisfy\", source_uuid=<UUID de l'élément>,\n");
        prompt.append("     target_uuid=<UUID de l'exigence>, module_name=\"ModelerModule\"\n");
        prompt.append("   - Sens de la relation : source = élément de modélisation (satisfait), target = exigence (satisfaite).\n");
        prompt.append("   - Un élément sans dépendance «Satisfait» réelle vers son/ses exigence(s) n'est PAS conforme.\n");
        prompt.append("   - Ne pas se contenter de le mentionner dans le JSON : la relation doit exister dans le modèle Modelio.\n\n");
        
        prompt.append("NE FOURNISSEZ PAS DE PROCÉDURE MANUELLE. START NOW: create packages, classes, attributes, then associations with MCP tools and return the as-built outputs only.");
        
        return prompt.toString();
    }
    
    /**
     * Génère le prompt spécialisé pour la création des use cases et actors (PHASE 3)
     */
    private static String createUseCasesPrompt(String analysisResults, String requirementsResult, String classesResult, List<Requirement> parsedRequirements, List<String> requirementUUIDs) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("🇫🇷 Vous êtes un analyste de cas d'usage Modelio. Votre mission : créer TOUS les cas d'usage et acteurs en français.\n\n");
        
        prompt.append("## MISSION PHASE 3 : CAS D'USAGE & ACTEURS\n");
        prompt.append("🎯 Créer un modèle de cas d'usage complet en français avec acteurs et scénarios.\n");
        prompt.append("🚨 Utiliser les outils MCP pour créer acteurs, cas d'usage et leurs associations.\n\n");
        
        // Contexte des requirements pour lien fonctionnel
        if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
            prompt.append("## CONTEXTE FONCTIONNEL - APERÇU DES EXIGENCES\n");
            prompt.append("Baser les cas d'usage sur ces exigences fonctionnelles :\n");
            
            for (int i = 0; i < Math.min(parsedRequirements.size(), 12); i++) {
                Requirement req = parsedRequirements.get(i);
                if (req.category.contains("Fonctionnel") || req.category.contains("Interface") || req.category.contains("Intégration") ||
                    req.category.contains("Fonctionnelle") || req.category.contains("UI") || req.category.contains("Integration")) {
                    prompt.append(String.format("- **%s** (%s): %s\n", req.id, req.category,
                        req.description.length() > 100 ? req.description.substring(0, 100) + "..." : req.description));
                }
            }
            prompt.append("\n🔗 LIEN : Créer des cas d'usage qui implémentent ces exigences fonctionnelles.\n\n");
        }
        
        prompt.append("## CONTEXTE DES PHASES PRÉCÉDENTES\n");
        prompt.append("### Exigences Créées (Phase 1) :\n");
        if (requirementsResult != null && requirementsResult.length() > 2000) {
            prompt.append(requirementsResult.substring(0, 2000)).append("\n... (contexte exigences tronqué)\n\n");
        } else {
            prompt.append(requirementsResult).append("\n\n");
        }
        
        // ✨ NEW: Add requirement UUIDs for direct linking (avoid keyword search failures)
        if (requirementUUIDs != null && !requirementUUIDs.isEmpty()) {
            prompt.append("## UUIDS DES EXIGENCES POUR LIEN DIRECT (À UTILISER OBLIGATOIREMENT)\n");
            prompt.append("🚨 NE PAS CHERCHER LES EXIGENCES PAR MOTS-CLÉS! Utiliser directement ces UUIDs pour créer les liens «Satisfait»:\n\n");
            for (int i = 0; i < requirementUUIDs.size(); i++) {
                prompt.append(String.format("- UUID exigence #%d: %s\n", (i + 1), requirementUUIDs.get(i)));
            }
            prompt.append("\n🚨 CRITIQUE : Lors de la création de chaque cas d'usage, appeler analyst_createRelation IMMÉDIATEMENT\n");
            prompt.append("   avec relation_type=\"satisfy\", source_uuid=<UUID cas d'usage>, target_uuid=<l'un des UUIDs exigences ci-dessus>\n");
            prompt.append("   NE PAS attendre la fin. Lier chaque cas d'usage AU FUR ET À MESURE.\n\n");
        }
        
        prompt.append("🔍 **EXTRAIRE LE JSON DES EXIGENCES** : Rechercher la structure JSON dans les résultats ci-dessus\n\n");
        
        prompt.append("### Modèle de Domaine Créé (Phase 2) :\n");
        if (classesResult != null && classesResult.length() > 2000) {
            prompt.append(classesResult.substring(0, 2000)).append("\n... (contexte classes tronqué)\n\n");
        } else {
            prompt.append(classesResult).append("\n\n");
        }
        
        prompt.append("🔍 **EXTRAIRE LE MODÈLE DE DOMAINE** : Rechercher le PlantUML MODELE_DOMAINE_AS_BUILT et JSON ci-dessus\n");
        prompt.append("🎯 **UTILISER LE MODÈLE AS-BUILT** : Baser les cas d'usage sur les classes réellement créées, pas le PlantUML original\n\n");
        
        prompt.append("## RÉFÉRENCE PLANTUML ORIGINAL\n");
        prompt.append("```\n");
        if (analysisResults != null && analysisResults.length() > 4000) {
            prompt.append(analysisResults.substring(0, 4000)).append("\n... (PlantUML tronqué)");
        } else {
            prompt.append(analysisResults);
        }
        prompt.append("\n```\n\n");
        
        prompt.append("## SÉQUENCE D'EXÉCUTION (OBLIGATOIRE)\n");
        prompt.append("1️⃣ **Créer le Package Cas d'Usage** : Utiliser les outils MCP\n");
        prompt.append("   - Créer le package 'Cas d Usage'\n");
        prompt.append("   - Rapporter l'UUID du package\n\n");
        
        prompt.append("2️⃣ **Créer les Acteurs** : Utiliser les outils MCP\n");
        prompt.append("   - Identifier tous les types d'utilisateurs à partir des exigences et du modèle de domaine\n");
        prompt.append("   - Créer les acteurs : Utilisateur, Administrateur, Système Externe, etc.\n");
        prompt.append("   - Placer dans le package 'Cas d Usage'\n");
        prompt.append("   - Rapporter l'UUID de chaque acteur\n\n");
        
        prompt.append("3️⃣ **Créer les Cas d'Usage** : Utiliser les outils MCP\n");
        prompt.append("   - Extraire les fonctionnalités principales des exigences et classes\n");
        prompt.append("   - Créer les cas d'usage : 'Gérer les Utilisateurs', 'Traiter les Données', etc.\n");
        prompt.append("   - Lier aux exigences d'implémentation quand c'est possible\n");
        prompt.append("   - Placer dans le package 'Cas d Usage'\n");
        prompt.append("   - Rapporter l'UUID de chaque cas d'usage\n");
        prompt.append("   - 🚨 OBLIGATOIRE : pour chaque cas d'usage lié à une exigence, matérialiser le lien «Satisfait» avec l'outil MCP\n");
        prompt.append("     `analyst_createRelation` (relation_type=\"satisfy\", source_uuid=<UUID du cas d'usage>, target_uuid=<UUID de l'exigence>,\n");
        prompt.append("     module_name=\"ModelerModule\") en un seul appel.\n\n");
        
        prompt.append("4️⃣ **Créer les Associations Acteur-Cas d'Usage** : Utiliser les outils MCP\n");
        prompt.append("   - Connecter chaque acteur aux cas d'usage pertinents\n");
        prompt.append("   - Utiliser les types d'association appropriés\n");
        prompt.append("   - Ajouter les relations <<include>> et <<extend>> si nécessaire\n");
        prompt.append("   - Maintenir la traçabilité vers les exigences\n\n");
        
        prompt.append("📊 **CRITICAL: PRODUCE VALIDATION & OUTPUTS**\n");
        prompt.append("After creating use cases, generate:\n\n");
        prompt.append("### 1. REQUIREMENTS COVERAGE VALIDATION\n");
        prompt.append("```\n");
        prompt.append("REQUIREMENTS COVERAGE ANALYSIS:\n");
        prompt.append("- REQ-001: Covered by [Login, User Management] use cases\n");
        prompt.append("- REQ-002: Covered by [Data Processing] use case\n");
        prompt.append("- REQ-XXX: NOT COVERED - Missing use case needed\n");
        prompt.append("\n");
        prompt.append("COVERAGE RATE: 85% (11/13 requirements covered)\n");
        prompt.append("```\n\n");
        prompt.append("### 2. USE CASE DIAGRAM PLANTUML\n");
        prompt.append("```plantuml\n");
        prompt.append("@startuml USE_CASES_DIAGRAM\n");
        prompt.append("actor User\n");
        prompt.append("actor Admin\n");
        prompt.append("rectangle System {\n");
        prompt.append("  usecase \"Login\" as UC1\n");
        prompt.append("  usecase \"Manage Data\" as UC2\n");
        prompt.append("}\n");
        prompt.append("User --> UC1\n");
        prompt.append("Admin --> UC2\n");
        prompt.append("@enduml\n");
        prompt.append("```\n\n");
        prompt.append("### 3. USE CASES SUMMARY JSON\n");
        prompt.append("```json\n");
        prompt.append("{\n");
        prompt.append("  \"use_cases_created\": {\n");
        prompt.append("    \"package_uuid\": \"00000000-0000-0000-0000-000000000000\",\n");
        prompt.append("    \"actors\": [{\"name\": \"User\", \"uuid\": \"00000000-0000-0000-0000-000000000000\"}],\n");
        prompt.append("    \"use_cases\": [\n");
        prompt.append("      {\n");
        prompt.append("        \"name\": \"Login\",\n");
        prompt.append("        \"uuid\": \"00000000-0000-0000-0000-000000000000\",\n");
        prompt.append("        \"actors\": [\"User\"],\n");
        prompt.append("        \"linked_requirements\": [\"REQ-001\"],\n");
        prompt.append("        \"domain_classes_used\": [\"User\", \"Authentication\"]\n");
        prompt.append("      }\n");
        prompt.append("    ],\n");
        prompt.append("    \"coverage_rate\": 0.85,\n");
        prompt.append("    \"uncovered_requirements\": [\"REQ-007\"]\n");
        prompt.append("  }\n");
        prompt.append("}\n");
        prompt.append("```\n\n");
        
        prompt.append("## ACTEURS TYPIQUES À CONSIDÉRER\n");
        prompt.append("- Utilisateurs principaux (qui vont utiliser le système)\n");
        prompt.append("- Administrateurs (qui gèrent le système)\n");
        prompt.append("- Systèmes externes (APIs, bases de données)\n");
        prompt.append("- Parties prenantes (managers, auditeurs)\n\n");
        
        prompt.append("## CAS D'USAGE TYPIQUES À CONSIDÉRER\n");
        prompt.append("- Gestion des utilisateurs (inscription, connexion, profil)\n");
        prompt.append("- Opérations sur les données (créer, lire, modifier, supprimer)\n");
        prompt.append("- Reporting et analytiques\n");
        prompt.append("- Administration système\n");
        prompt.append("- Intégration avec systèmes externes\n\n");
        
        prompt.append("## EXIGENCES DE TRAÇABILITÉ\n");
        prompt.append("🔗 Lier les cas d'usage aux exigences qui définissent leurs fonctionnalités\n");
        prompt.append("🔗 Référencer les classes du modèle de domaine manipulées par les cas d'usage\n");
        prompt.append("🔗 Assurer une couverture complète des exigences fonctionnelles\n");
        prompt.append("🚨 RAPPEL OBLIGATOIRE : chaque lien de traçabilité vers une exigence DOIT être matérialisé dans Modelio via l'outil MCP\n");
        prompt.append("   `analyst_createRelation` (relation_type=\"satisfy\", source_uuid=<UUID de l'élément>, target_uuid=<UUID de l'exigence>,\n");
        prompt.append("   module_name=\"ModelerModule\"). Un simple champ JSON 'linked_requirements' ne suffit pas.\n\n");
        
        prompt.append("NE FOURNISSEZ PAS DE PROCÉDURE MANUELLE. COMMENCEZ MAINTENANT : créez le package cas d'usage, les acteurs, les cas d'usage, puis les associations avec les outils MCP et retournez uniquement les résultats as-built.");
        
        return prompt.toString();
    }

    /**
     * Extrait automatiquement les UUIDs depuis un output d'agent
     */
    private static List<String> extractUUIDs(String agentOutput) {
        List<String> uuids = new ArrayList<>();
        if (agentOutput == null) return uuids;
        
        // Pattern pour UUIDs Modelio (format standard UUID)
        String uuidPattern = "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(uuidPattern);
        java.util.regex.Matcher matcher = pattern.matcher(agentOutput);
        
        while (matcher.find()) {
            uuids.add(matcher.group());
        }
        
        debug("📋 Extracted " + uuids.size() + " UUIDs from agent output");
        return uuids;
    }

    /**
     * Extrait les UUIDs des exigences uniquement depuis la structure JSON dédiée.
     * Évite de fournir des UUIDs de package/conteneur qui cassent analyst_createRelation(satisfy).
     */
    private static List<String> extractRequirementUUIDs(String requirementsOutput) {
        if (requirementsOutput == null || requirementsOutput.isBlank()) {
            return List.of();
        }

        String requirementsJson = extractJSONStructure(requirementsOutput, "requirements_created", "exigences_creees");
        if (requirementsJson == null || requirementsJson.isBlank()) {
            return extractUUIDs(requirementsOutput);
        }

        try {
            JsonNode root = new ObjectMapper().readTree(requirementsJson);
            JsonNode created = root.path("requirements_created");
            if (!created.isArray()) {
                created = root.path("exigences_creees");
            }

            if (!created.isArray()) {
                return extractUUIDs(requirementsOutput);
            }

            List<String> requirementUuids = new ArrayList<>();
            for (JsonNode requirementNode : created) {
                String uuid = requirementNode.path("uuid").asText(null);
                if (uuid != null && REAL_UUID_PATTERN.matcher(uuid).matches()) {
                    requirementUuids.add(uuid);
                }
            }

            if (!requirementUuids.isEmpty()) {
                debug("📋 Extracted " + requirementUuids.size() + " requirement-only UUIDs from requirements JSON");
                return requirementUuids;
            }
        } catch (Exception e) {
            debug("⚠️ Could not parse requirement-only UUIDs from JSON: " + e.getMessage());
        }

        return extractUUIDs(requirementsOutput);
    }

    /**
     * Extrait la structure JSON depuis un output d'agent
     */
    private static String extractJSONStructure(String agentOutput, String... jsonKeys) {
        if (agentOutput == null || jsonKeys == null || jsonKeys.length == 0) return null;
        
        try {
            // Chercher le bloc JSON avec la clé spécifiée
            int jsonStart = agentOutput.indexOf("{");
            int jsonEnd = agentOutput.lastIndexOf("}");
            
            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                String potentialJson = agentOutput.substring(jsonStart, jsonEnd + 1);
                
                for (String jsonKey : jsonKeys) {
                    if (jsonKey != null && potentialJson.contains("\"" + jsonKey + "\"")) {
                        debug("📊 Extracted JSON structure for key: " + jsonKey);
                        return potentialJson;
                    }
                }
            }
            
            debug("⚠️ No JSON structure found for keys: " + String.join(", ", jsonKeys));
            return null;
            
        } catch (Exception e) {
            debug("❌ Error extracting JSON structure: " + e.getMessage());
            return null;
        }
    }

    private static String ensureStructuredDomainModelResult(String result) {
        if (extractJSONStructure(result, "domain_model_created", "modele_domaine_cree") != null) {
            return result;
        }
        if (result == null || result.trim().isEmpty()) {
            return result;
        }
        if (result.trim().startsWith("MCP_EXECUTION_FAILED:") || result.trim().startsWith("❌") || result.trim().startsWith("[error:")) {
            return result;
        }

        List<String> realUuids = extractUUIDs(result);

        // Fallback 1: PlantUML + UUIDs → synthesize full JSON from PlantUML
        String asBuiltPlantUml = extractPlantUMLDiagram(result, "MODELE_DOMAINE_AS_BUILT");
        if (asBuiltPlantUml == null) {
            asBuiltPlantUml = extractPlantUMLDiagram(result, "AS_BUILT_DOMAIN_MODEL");
        }
        if (asBuiltPlantUml != null && PlantUMLAnalyzer.isValidPlantUML(asBuiltPlantUml) && !realUuids.isEmpty()) {
            String synthesizedJson = synthesizeDomainModelJson(asBuiltPlantUml, realUuids);
            if (synthesizedJson != null) {
                debug("⚠️ domain_model_phase returned no structured JSON; synthesized fallback domain_model_created payload from as-built PlantUML");
                return result
                        + System.lineSeparator()
                        + System.lineSeparator()
                        + "```json"
                        + System.lineSeparator()
                        + synthesizedJson
                        + System.lineSeparator()
                        + "```";
            }
        }

        // Fallback 2: UUIDs present but no PlantUML → synthesize minimal JSON from UUIDs
        // This covers the case where MCP tools executed (UUIDs prove it) but the LLM
        // didn't produce a PlantUML diagram or structured JSON in its final output.
        if (!realUuids.isEmpty()) {
            String synthesizedJson = synthesizeMinimalDomainModelJson(realUuids);
            if (synthesizedJson != null) {
                debug("⚠️ domain_model_phase returned no structured JSON or PlantUML; synthesized minimal domain_model_created payload from " + realUuids.size() + " UUIDs");
                return result
                        + System.lineSeparator()
                        + System.lineSeparator()
                        + "```json"
                        + System.lineSeparator()
                        + synthesizedJson
                        + System.lineSeparator()
                        + "```";
            }
        }

        return result;
    }

    private static String synthesizeMinimalDomainModelJson(List<String> realUuids) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            ObjectNode domainModel = root.putObject("domain_model_created");
            domainModel.put("synthesized_from_uuids_only", true);
            domainModel.put("uuid_count", realUuids.size());

            // Heuristic: the first UUID is likely the package, the rest are classes/elements
            if (!realUuids.isEmpty()) {
                domainModel.put("package_uuid", realUuids.get(0));
            }

            ArrayNode classes = domainModel.putArray("classes");
            // Treat UUIDs after the first as class UUIDs
            for (int i = 1; i < realUuids.size(); i++) {
                ObjectNode cls = classes.addObject();
                cls.put("uuid", realUuids.get(i));
                cls.put("synthesized", true);
                cls.putArray("attributs");
                cls.putArray("exigences_liees");
            }

            domainModel.putArray("associations");

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            debug("⚠️ Could not synthesize minimal domain_model_created JSON: " + e.getMessage());
            return null;
        }
    }

    private static String synthesizeDomainModelJson(String plantUml, List<String> realUuids) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            ObjectNode domainModel = root.putObject("domain_model_created");
            domainModel.put("package_uuid", realUuids.get(0));
            domainModel.put("synthesized_from_as_built_output", true);

            ArrayNode classes = domainModel.putArray("classes");
            appendClassesFromPlantUml(classes, plantUml);

            ArrayNode associations = domainModel.putArray("associations");
            appendAssociationsFromPlantUml(associations, plantUml);

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            debug("⚠️ Could not synthesize domain_model_created JSON: " + e.getMessage());
            return null;
        }
    }

    private static void appendClassesFromPlantUml(ArrayNode classes, String plantUml) {
        java.util.Set<String> seenClassNames = new java.util.LinkedHashSet<>();
        ObjectNode currentClass = null;

        for (String rawLine : plantUml.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty() || line.startsWith("'") || line.startsWith("//")) {
                continue;
            }
            if (line.startsWith("@startuml") || line.startsWith("@enduml")) {
                continue;
            }
            if (line.startsWith("}")) {
                currentClass = null;
                continue;
            }

            String className = extractPlantUmlClassifierName(line);
            if (className != null) {
                if (seenClassNames.add(className)) {
                    currentClass = classes.addObject();
                    currentClass.put("nom", className);
                    currentClass.putNull("uuid");
                    currentClass.putArray("attributs");
                    currentClass.putArray("exigences_liees");
                } else {
                    currentClass = null;
                }
                if (!line.contains("{")) {
                    currentClass = null;
                }
                continue;
            }

            if (currentClass != null && line.contains(":") && !line.contains("(")) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    String attributeName = cleanupPlantUmlAttributeToken(parts[0]);
                    String attributeType = cleanupPlantUmlAttributeToken(parts[1]);
                    if (!attributeName.isEmpty() && !attributeType.isEmpty()) {
                        ArrayNode attributes = (ArrayNode) currentClass.get("attributs");
                        ObjectNode attribute = attributes.addObject();
                        attribute.put("nom", attributeName);
                        attribute.put("type", attributeType);
                    }
                }
            }
        }
    }

    private static void appendAssociationsFromPlantUml(ArrayNode associations, String plantUml) {
        for (String rawLine : plantUml.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty() || line.startsWith("'") || line.startsWith("//")) {
                continue;
            }

            String relationToken = detectPlantUmlRelationToken(line);
            if (relationToken == null) {
                continue;
            }

            String relationWithoutLabel = line;
            String relationLabel = null;
            int labelSeparator = line.indexOf(':');
            if (labelSeparator >= 0) {
                relationWithoutLabel = line.substring(0, labelSeparator).trim();
                relationLabel = line.substring(labelSeparator + 1).trim();
            }

            String[] endpoints = relationWithoutLabel.split(Pattern.quote(relationToken), 2);
            if (endpoints.length != 2) {
                continue;
            }

            String from = cleanupPlantUmlRelationEndpoint(endpoints[0]);
            String to = cleanupPlantUmlRelationEndpoint(endpoints[1]);
            if (from.isEmpty() || to.isEmpty()) {
                continue;
            }

            ObjectNode association = associations.addObject();
            association.put("de", from);
            association.put("vers", to);
            association.put("type", mapPlantUmlRelationType(relationToken));
            if (relationLabel != null && !relationLabel.isEmpty()) {
                association.put("nom", relationLabel);
            }

            String cardinality = extractPlantUmlCardinality(relationWithoutLabel);
            if (cardinality == null || cardinality.isBlank()) {
                association.putNull("cardinalite");
            } else {
                association.put("cardinalite", cardinality);
            }
        }
    }

    private static String extractPlantUmlClassifierName(String line) {
        String working = line;
        if (working.startsWith("abstract ")) {
            working = working.substring("abstract ".length()).trim();
        }

        String[] prefixes = {"class ", "interface ", "enum ", "entity "};
        String matchedPrefix = null;
        for (String prefix : prefixes) {
            if (working.startsWith(prefix)) {
                matchedPrefix = prefix;
                break;
            }
        }
        if (matchedPrefix == null) {
            return null;
        }

        working = working.substring(matchedPrefix.length()).trim();
        if (working.isEmpty()) {
            return null;
        }

        if (working.startsWith("\"")) {
            int endQuote = working.indexOf('"', 1);
            if (endQuote > 1) {
                return working.substring(1, endQuote).trim();
            }
        }

        int cutIndex = working.length();
        String[] delimiters = {" {", " as ", " <<", " extends ", " implements "};
        for (String delimiter : delimiters) {
            int index = working.indexOf(delimiter);
            if (index >= 0 && index < cutIndex) {
                cutIndex = index;
            }
        }
        String name = working.substring(0, cutIndex).trim();
        if (name.endsWith("{")) {
            name = name.substring(0, name.length() - 1).trim();
        }
        return name;
    }

    private static String cleanupPlantUmlAttributeToken(String token) {
        if (token == null) {
            return "";
        }
        String cleaned = token.trim()
                .replace("+", "")
                .replace("-", "")
                .replace("#", "")
                .replace("~", "")
                .trim();
        int genericStart = cleaned.indexOf('{');
        if (genericStart >= 0) {
            cleaned = cleaned.substring(0, genericStart).trim();
        }
        return cleaned;
    }

    private static String detectPlantUmlRelationToken(String line) {
        String[] relationTokens = {"<|--", "--|>", "*--", "--*", "o--", "--o", "<--", "-->", "..>", "<..", "..", "--"};
        for (String token : relationTokens) {
            if (line.contains(token)) {
                return token;
            }
        }
        return null;
    }

    private static String cleanupPlantUmlRelationEndpoint(String endpoint) {
        if (endpoint == null) {
            return "";
        }
        String cleaned = endpoint.replaceAll("\"[^\"]*\"", " ")
                .replaceAll("\\b(left|right|up|down|hidden)\\b", " ")
                .trim();
        Matcher matcher = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_.-]*").matcher(cleaned);
        String lastMatch = "";
        while (matcher.find()) {
            lastMatch = matcher.group();
        }
        return lastMatch;
    }

    private static String mapPlantUmlRelationType(String relationToken) {
        return switch (relationToken) {
            case "<|--", "--|>" -> "Generalization";
            case "*--", "--*" -> "Composition";
            case "o--", "--o" -> "Aggregation";
            case "..>", "<..", ".." -> "Dependency";
            default -> "Association";
        };
    }

    private static String extractPlantUmlCardinality(String relationLine) {
        if (relationLine == null || relationLine.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(relationLine);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1).trim());
        }
        if (values.isEmpty()) {
            return null;
        }
        return String.join(" / ", values);
    }

    private static void validateMcpExecutionResult(String phaseName, String result, String... expectedJsonKeys) {
        if (result == null || result.trim().isEmpty()) {
            throw new IllegalStateException("No output returned for phase '" + phaseName + "'");
        }

        String trimmed = result.trim();
        if (trimmed.startsWith("MCP_EXECUTION_FAILED:") || trimmed.startsWith("❌") || trimmed.startsWith("[error:")) {
            throw new IllegalStateException(trimmed);
        }

        if (MANUAL_INSTRUCTIONS_PATTERN.matcher(result).find()) {
            throw new IllegalStateException(
                    "The LLM returned manual instructions instead of executing MCP tools during phase '" + phaseName + "'");
        }

        if (PLACEHOLDER_UUID_VALUE_PATTERN.matcher(result).find() && !REAL_UUID_PATTERN.matcher(result).find()) {
            throw new IllegalStateException(
                    "The LLM returned placeholder UUID values instead of real MCP UUIDs during phase '" + phaseName + "'");
        }

        if (expectedJsonKeys != null && expectedJsonKeys.length > 0 && extractJSONStructure(result, expectedJsonKeys) == null) {
            throw new IllegalStateException(
                    "No structured JSON result was returned for phase '" + phaseName + "'");
        }
    }

    /**
     * Extrait le diagramme PlantUML depuis un output d'agent
     */
    private static String extractPlantUMLDiagram(String agentOutput, String diagramName) {
        if (agentOutput == null || diagramName == null) return null;
        
        try {
            // Chercher le bloc PlantUML spécifique
            String startMarker = "@startuml " + diagramName;
            String endMarker = "@enduml";
            
            int start = agentOutput.indexOf(startMarker);
            if (start == -1) {
                // Essayer sans nom spécifique
                start = agentOutput.indexOf("@startuml");
            }
            
            if (start != -1) {
                int end = agentOutput.indexOf(endMarker, start);
                if (end != -1) {
                    String diagram = agentOutput.substring(start, end + endMarker.length());
                    debug("🎯 Extracted PlantUML diagram: " + diagramName);
                    return diagram;
                }
            }
            
            debug("⚠️ No PlantUML diagram found for: " + diagramName);
            return null;
            
        } catch (Exception e) {
            debug("❌ Error extracting PlantUML diagram: " + e.getMessage());
            return null;
        }
    }

    /**
     * Ancienne méthode monolithique conservée pour compatibilité avec generateModelFromPlantUML
     * (Version simplifiée du prompt géant d'origine - FRANCISÉE)
     */
    private static String createLegacyModelGenerationPrompt(String plantUMLContent, String requirementsDocuments, List<Requirement> parsedRequirements) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("🇫🇷 Vous êtes un assistant de modélisation Modelio. Créez un modèle UML complet en français à partir du PlantUML.\n\n");
        
        if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
            prompt.append("## Exigences Parsées Disponibles\n");
            for (Requirement req : parsedRequirements) {
                prompt.append(String.format("- **%s**: %s (Catégorie: %s, Priorité: %s)\n", 
                    req.id, req.description, req.category, req.priority));
            }
            prompt.append("\n🚨 Créer ces exigences dans Modelio en utilisant les outils MCP.\n\n");
        }
        
        prompt.append("## PlantUML à Traiter\n");
        prompt.append("```plantuml\n");
        prompt.append(plantUMLContent);
        prompt.append("\n```\n\n");
        
        prompt.append("## SÉQUENCE D'EXÉCUTION\n");
        prompt.append("1. Créer les packages (Exigences, Cas d'Usage, Modèle de Domaine)\n");
        prompt.append("2. Créer les exigences en utilisant les outils MCP\n");
        prompt.append("3. Créer les classes avec leurs attributs\n");
        prompt.append("4. Créer les associations entre les classes\n");
        prompt.append("5. Créer les cas d'usage et les acteurs\n\n");
        
        prompt.append("## RÈGLES CRITIQUES\n");
        prompt.append("- Utiliser les outils MCP pour CHAQUE création d'élément\n");
        prompt.append("- Créer d'abord les classes, puis les attributs, puis les associations\n");
        prompt.append("- Utiliser les types : String, int, boolean, float (compatibles Modelio)\n");
        prompt.append("- NE JAMAIS ignorer les associations - parser TOUTES les relations du PlantUML\n");
        prompt.append("- TOUTES les descriptions et noms doivent être en français\n");
        prompt.append("- 🚨 OBLIGATOIRE : chaque élément (classe, acteur, cas d'usage) qui répond à une exigence DOIT être relié\n");
        prompt.append("  à celle-ci via l'outil MCP `analyst_createRelation` (relation_type=\"satisfy\", source_uuid=<UUID élément>,\n");
        prompt.append("  target_uuid=<UUID exigence>, module_name=\"ModelerModule\")\n\n");
        
        prompt.append("COMMENCEZ MAINTENANT : Créez le modèle complet en utilisant les outils MCP.");
        
        return prompt.toString();
    }
    
    // -------------------------------------------------- Instance state (compatibility layer) --------------------------------------------------

    /** Default chat model built from the base URL / deployment passed to the constructor. */
    private final PolicyAwareAzureChatModel instanceChatModel;

    /** Base endpoint (without deployment suffix), reused when building models for other deployments. */
    private final String instanceEndpoint;

    /** API key / APIM subscription key, reused when building models for other deployments. */
    private final String instanceApiKey;

    /** Per-stage deployment overrides configured via the Modelio module parameter panel. */
    private final StageModelConfig stageConfig;

    /** Lazily populated cache: deployment name → chat model. Avoids rebuilding HTTP clients. */
    private final java.util.concurrent.ConcurrentHashMap<String, PolicyAwareAzureChatModel> modelCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Primary constructor — accepts stage-level deployment overrides from the Modelio module
     * parameter panel.
     */
    public LangchainService(String apiKey, String baseUrl, String deployment, StageModelConfig stageConfig, boolean debug) {
        AzureEndpointResolver.AzureEndpointInfo info = AzureEndpointResolver.resolve(
            baseUrl, deployment != null ? deployment : "", OpenAiDefaults.DEPLOYMENT);

        this.instanceEndpoint = info.endpoint;
        this.instanceApiKey   = apiKey != null ? apiKey : "";
        this.stageConfig      = stageConfig != null ? stageConfig : StageModelConfig.defaults();

        com.azure.ai.openai.OpenAIAsyncClient client = buildClient(info, this.instanceApiKey);
        this.instanceChatModel = new PolicyAwareAzureChatModel(client, info, OpenAiDefaults.TEMPERATURE);
        modelCache.put(info.deployment, this.instanceChatModel);

        if (debug) {
            debug("LangchainService instance created — stage config: " + this.stageConfig.summary());
        }
    }

    /** Backward-compatible constructor — all stages use the default deployment. */
    public LangchainService(String apiKey, String baseUrl, String deployment, boolean debug) {
        this(apiKey, baseUrl, deployment, StageModelConfig.defaults(), debug);
    }

    /**
     * Returns (or builds and caches) a chat model for the given {@code deployment}.
     * Uses the same base endpoint and API key as the instance's default model.
     * Returns {@link #instanceChatModel} when {@code deployment} matches the default or is null/blank.
     */
    public PolicyAwareAzureChatModel getChatModelForDeployment(String deployment) {
        if (deployment == null || deployment.isBlank()) {
            return instanceChatModel;
        }
        return modelCache.computeIfAbsent(deployment, dep -> {
            AzureEndpointResolver.AzureEndpointInfo info =
                    new AzureEndpointResolver.AzureEndpointInfo(instanceEndpoint, dep);
            com.azure.ai.openai.OpenAIAsyncClient client = buildClient(info, instanceApiKey);
            debug("Built chat model for deployment: " + dep);
            return new PolicyAwareAzureChatModel(client, info, OpenAiDefaults.TEMPERATURE);
        });
    }

    /**
     * Runs a prompt using the deployment configured for {@code stageName} in this instance's
     * {@link StageModelConfig}. Falls back to the default deployment when no override is set.
     */
    public String runPrompt(String systemPrompt, String userContext, String stageName) {
        String deployment = stageConfig.deploymentFor(stageName);
        PolicyAwareAzureChatModel model = getChatModelForDeployment(deployment);
        debug("Stage '" + stageName + "' → deployment: " + deployment);
        return runPromptWithModel(systemPrompt, userContext, model);
    }

    /**
     * Runs a prompt using the instance's default chat model.
     */
    public String runPrompt(String systemPrompt, String userContext) {
        return runPromptWithModel(systemPrompt, userContext, instanceChatModel);
    }

    private String runPromptWithModel(String systemPrompt, String userContext, PolicyAwareAzureChatModel model) {
        try {
            debug("Running prompt with system prompt length: " +
                  (systemPrompt != null ? systemPrompt.length() : 0));
            debug("User context length: " +
                  (userContext != null ? userContext.length() : 0));

            dev.langchain4j.data.message.SystemMessage systemMessage =
                    dev.langchain4j.data.message.SystemMessage.from(systemPrompt != null ? systemPrompt : "");
            dev.langchain4j.data.message.UserMessage userMessage =
                    dev.langchain4j.data.message.UserMessage.from(userContext != null ? userContext : "");

            dev.langchain4j.model.chat.response.ChatResponse response =
                    model.chat(java.util.Arrays.asList(systemMessage, userMessage));

            String result = response.aiMessage().text();
            debug("Response received, length: " + (result != null ? result.length() : 0));

            return result != null ? result : "";

        } catch (Exception e) {
            String errorMsg = "Failed to process chat request: " + e.getMessage();
            debug(errorMsg);
            throw new RuntimeException(errorMsg, e);
        }
    }

    /**
     * Returns the default chat model for this instance.
     * Use {@link #getChatModelForDeployment(String)} when a specific deployment is required.
     */
    public PolicyAwareAzureChatModel getChatModel() {
        return instanceChatModel;
    }
    
    /**
     * Construit le client Azure OpenAI (méthode utilitaire)
     */
    private static com.azure.ai.openai.OpenAIAsyncClient buildClient(AzureEndpointResolver.AzureEndpointInfo info, String aadToken) {
        // Note: we intentionally do NOT call builder.credential(...) here. Doing so makes the Azure
        // SDK attach an additional "Authorization: Bearer <aadToken>" header via its
        // BearerTokenAuthenticationPolicy, using our raw APIM subscription key as if it were a real
        // AAD JWT. Some API Management operations (e.g. newer deployments behind stricter policies)
        // reject that malformed Authorization header with a 404, even though the same
        // Ocp-Apim-Subscription-Key-only request succeeds (this is exactly what ModelioAI does).
        // Auth is handled entirely by HttpPolicies.auth() below, which sets Ocp-Apim-Subscription-Key.
        // The Azure SDK's default Netty HttpClient has a 60s response timeout, which is too
        // short for large prompts (140K+ chars) against slower deployments/proxies. Raise it
        // to match the request-level timeouts used in PolicyAwareAzureChatModel.
        com.azure.core.http.HttpClient httpClient = new com.azure.core.http.netty.NettyAsyncHttpClientBuilder()
                .responseTimeout(Duration.ofSeconds(OpenAiDefaults.REQUEST_TIMEOUT_SECONDS))
                .build();

        com.azure.ai.openai.OpenAIClientBuilder builder = new com.azure.ai.openai.OpenAIClientBuilder()
            .endpoint(info.endpoint)
            .httpClient(httpClient)
            .httpLogOptions(HttpPolicies.httpLogOptions())
            .addPolicy(HttpPolicies.auth(aadToken))
            .addPolicy(HttpPolicies.capture());

        if (aadToken.isEmpty()) {
            debug("No AAD token provided; requests may fail due to missing auth");
        }

        return builder.buildAsyncClient();
    }
}
