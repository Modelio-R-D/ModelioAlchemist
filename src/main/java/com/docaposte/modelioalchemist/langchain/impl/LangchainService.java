package com.docaposte.modelioalchemist.langchain.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private static final int PHASE2_CHUNKING_CLASS_THRESHOLD = 45;
    private static final int PHASE2_CHUNKING_RELATION_THRESHOLD = 60;
    private static final int PHASE2_CLASSES_CHUNK_SIZE = 20;
    private static final int PHASE2_ASSOCIATIONS_CHUNK_SIZE = 25;
    /** Max requirement UUIDs injected into a single chunk prompt to avoid context bloat. */
    
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

    private static String formatRequirementId(int numericId) {
        int safeId = Math.max(1, numericId);
        return CANONICAL_REQUIREMENT_PREFIX + "-" + String.format("%03d", safeId);
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

    private static String buildRequirementOrigin(com.fasterxml.jackson.databind.JsonNode reqNode, String sourceDocumentName, String fallbackDescription) {
        List<String> originParts = new ArrayList<>();
        if (sourceDocumentName != null && !sourceDocumentName.isBlank()) {
            originParts.add("document=" + sourceDocumentName.trim());
        }
        String originalRef = reqNode.path("original_ref").asText("").trim();
        if (!originalRef.isEmpty()) originParts.add("ref=" + originalRef);
        String sourceLocation = reqNode.path("source_location").asText("").trim();
        if (sourceLocation.isEmpty()) sourceLocation = reqNode.path("location").asText("").trim();
        if (sourceLocation.isEmpty()) sourceLocation = reqNode.path("page").asText("").trim();
        if (!sourceLocation.isEmpty()) originParts.add("location=" + sourceLocation);
        String context = reqNode.path("context").asText("").trim();
        if (!context.isEmpty()) originParts.add("context=" + context);
        String sourceQuote = reqNode.path("source_quote").asText("").trim();
        if (sourceQuote.isEmpty()) sourceQuote = reqNode.path("excerpt").asText("").trim();
        if (sourceQuote.isEmpty()) sourceQuote = reqNode.path("origin_excerpt").asText("").trim();
        if (!sourceQuote.isEmpty()) originParts.add("quote=" + truncate(sourceQuote.replaceAll("\\s+", " "), 240));
        if (originParts.isEmpty()) {
            String fallback = fallbackDescription == null ? "" : fallbackDescription.replaceAll("\\s+", " ").trim();
            if (!fallback.isEmpty()) originParts.add("description=" + truncate(fallback, 120));
        }
        return String.join(" | ", originParts);
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
            String prompt = UmlPromptBuilder.createLegacyModelGenerationPrompt(plantUMLContent, requirementsDocuments, parsedRequirements);
            
            // Laisser l'assistant IA gérer la création du modèle avec les outils MCP disponibles
            String result = McpRetryHandler.executeAssistantWithMcpTrace(pa, "legacy_generation", prompt, outputDirectory);
            
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

            if (requirementContainerUuid == null && McpFailurePatterns.isMissingRequirementContainerError(response)) {
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
            
            // Requirements always come from the input document (filteredJson → createRequirementsInModelio
            // → existingRequirementsReport). The PlantUML is the solution and must never be used as a
            // requirement source, so parsedRequirements is intentionally left empty here.
            List<Requirement> parsedRequirements = new ArrayList<>();
            
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
                    String requirementsPrompt = UmlPromptBuilder.createRequirementsPrompt(parsedRequirements);

                    PooledUmlAssistant pa1 = borrowAssistant();
                    if (pa1 == null) {
                        return "❌ Could not borrow assistant for requirements creation";
                    }

                    try {
                        requirementsResult = McpRetryHandler.executeAssistantWithMcpTrace(pa1, "requirements_phase", requirementsPrompt, outputDirectory);
                        // Apply fallback synthesis for requirements
                        requirementsResult = ensureStructuredRequirementsResult(requirementsResult);
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
            String classesPrompt = UmlPromptBuilder.createClassesPrompt(analysisResults, requirementsResult, parsedRequirements, requirementUUIDs);
            
            PooledUmlAssistant pa2 = borrowAssistant();
            if (pa2 == null) {
                return "❌ Could not borrow assistant for classes creation";
            }
            
            String classesResult;
            try {
                classesResult = executePhase2DomainModelWithChunking(
                        pa2,
                        analysisResults,
                        requirementsResult,
                        parsedRequirements,
                        requirementUUIDs,
                        classesPrompt,
                        outputDirectory);
                classesResult = ensureStructuredDomainModelResult(classesResult);
                validateMcpExecutionResult("domain_model_phase", classesResult, "domain_model_created", "modele_domaine_cree");
                finalReport.append("PHASE 2 - CLASSES & ASSOCIATIONS:\n").append(classesResult).append("\n\n");
                 
                // 🔍 EXTRACTION AUTOMATIQUE DES STRUCTURES  
                List<String> classesUUIDs = extractUUIDs(classesResult);
                String domainModelJSON = extractJSONStructure(classesResult, "domain_model_created", "modele_domaine_cree");
                String asBuildPlantUML = PlantUmlParser.extractPlantUMLDiagram(classesResult, "AS_BUILT_DOMAIN_MODEL");
                
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
            String useCasesPrompt = UmlPromptBuilder.createUseCasesPrompt(analysisResults, requirementsResult, classesResult, parsedRequirements, requirementUUIDs);
            
            PooledUmlAssistant pa3 = borrowAssistant();
            if (pa3 == null) {
                return "❌ Could not borrow assistant for use cases creation";
            }
            
            String useCasesResult;
            try {
                useCasesResult = McpRetryHandler.executeAssistantWithMcpTrace(pa3, "use_cases_phase", useCasesPrompt, outputDirectory);
                useCasesResult = McpRetryHandler.retryOnMissingRequirementTargetUuid(pa3, "use_cases_phase", useCasesPrompt, outputDirectory, useCasesResult, 2);
                useCasesResult = ensureStructuredUseCasesResult(useCasesResult);
                validateMcpExecutionResult("use_cases_phase", useCasesResult, "use_cases_created");
                finalReport.append("PHASE 3 - USE CASES & ACTORS:\n").append(useCasesResult).append("\n\n");
                
                // 🔍 EXTRACTION AUTOMATIQUE DES STRUCTURES
                List<String> useCasesUUIDs = extractUUIDs(useCasesResult);
                String useCasesJSON = extractJSONStructure(useCasesResult, "use_cases_created");
                String useCasePlantUML = PlantUmlParser.extractPlantUMLDiagram(useCasesResult, "USE_CASES_DIAGRAM");
                
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

    

    private static String executePhase2DomainModelWithChunking(
            PooledUmlAssistant assistant,
            String analysisResults,
            String requirementsResult,
            List<Requirement> parsedRequirements,
            List<String> requirementUUIDs,
            String defaultPrompt,
            String outputDirectory) throws IOException {
        String plantUml = PlantUmlParser.preparePlantUmlForPrompt(analysisResults);
        PlantUmlParser.DomainPlantUmlParts parts = PlantUmlParser.extractDomainPlantUmlParts(plantUml);
        boolean shouldChunk = parts.classBlocks.size() >= PHASE2_CHUNKING_CLASS_THRESHOLD
                || parts.relationLines.size() >= PHASE2_CHUNKING_RELATION_THRESHOLD;
        if (!shouldChunk) {
            String classesResult = McpRetryHandler.executeAssistantWithMcpTraceWithRetry(assistant, "domain_model_phase", defaultPrompt, outputDirectory, 2);
            classesResult = McpRetryHandler.retryOnMissingRequirementTargetUuid(assistant, "domain_model_phase", defaultPrompt, outputDirectory, classesResult, 2);
            classesResult = McpRetryHandler.retryOnMissingModelingRequest(assistant, "domain_model_phase", defaultPrompt, outputDirectory, classesResult, 2);
            classesResult = McpRetryHandler.retryOnProjectOverviewOnly(assistant, "domain_model_phase", defaultPrompt, outputDirectory, classesResult, 2);
            classesResult = McpRetryHandler.retryOnDuplicateClassesAmbiguous(assistant, "domain_model_phase", defaultPrompt, outputDirectory, classesResult, 2);
            return classesResult;
        }

        debug("📦 domain_model_phase switched to chunked mode: "
                + parts.classBlocks.size() + " classes, " + parts.relationLines.size() + " relations.");

        List<String> chunkReports = new ArrayList<>();
        List<String> chunkPhaseNames = new ArrayList<>();
        List<String> collectedUuids = new ArrayList<>();

        List<List<String>> classChunks = PlantUmlParser.splitIntoChunks(parts.classBlocks, PHASE2_CLASSES_CHUNK_SIZE);
        for (int i = 0; i < classChunks.size(); i++) {
            PooledUmlAssistant chunkAssistant = newAssistant();
            String phaseName = "domain_model_phase_classes_chunk_" + (i + 1);
            String prompt = UmlPromptBuilder.createClassesChunkPrompt(
                    requirementsResult,
                    parsedRequirements,
                    requirementUUIDs,
                    classChunks.get(i),
                    i + 1,
                    classChunks.size());
            String chunkResult = McpRetryHandler.executeAssistantWithMcpTraceWithRetry(chunkAssistant, phaseName, prompt, outputDirectory, 2);
            chunkResult = McpRetryHandler.retryOnMissingRequirementTargetUuid(chunkAssistant, phaseName, prompt, outputDirectory, chunkResult, 2);
            chunkResult = McpRetryHandler.retryOnMissingModelingRequest(chunkAssistant, phaseName, prompt, outputDirectory, chunkResult, 2);
            chunkResult = McpRetryHandler.retryOnProjectOverviewOnly(chunkAssistant, phaseName, prompt, outputDirectory, chunkResult, 2);
            chunkResult = McpRetryHandler.retryOnMemberUuidNotFound(chunkAssistant, phaseName, prompt, outputDirectory, chunkResult, 2);
            chunkResult = McpRetryHandler.retryOnDuplicateClassesAmbiguous(chunkAssistant, phaseName, prompt, outputDirectory, chunkResult, 2);
            chunkResult = McpFailurePatterns.acceptSatisfaitOnlyFailure(phaseName, chunkResult);
            validateMcpExecutionResult(phaseName, chunkResult);
            chunkReports.add("### " + phaseName + System.lineSeparator() + chunkResult.trim());
            chunkPhaseNames.add(phaseName);
            collectedUuids.addAll(extractUUIDs(chunkResult));
        }

        List<List<String>> relationChunks = PlantUmlParser.splitIntoChunks(parts.relationLines, PHASE2_ASSOCIATIONS_CHUNK_SIZE);
        for (int i = 0; i < relationChunks.size(); i++) {
            PooledUmlAssistant chunkAssistant = newAssistant();
            String phaseName = "domain_model_phase_associations_chunk_" + (i + 1);
            String prompt = UmlPromptBuilder.createAssociationsChunkPrompt(
                    requirementsResult,
                    requirementUUIDs,
                    parts.classNames,
                    relationChunks.get(i),
                    i + 1,
                    relationChunks.size());
            String chunkResult = McpRetryHandler.executeAssistantWithMcpTraceWithRetry(chunkAssistant, phaseName, prompt, outputDirectory, 2);
            chunkResult = McpRetryHandler.retryOnMissingRequirementTargetUuid(chunkAssistant, phaseName, prompt, outputDirectory, chunkResult, 2);
            chunkResult = McpRetryHandler.retryOnMissingModelingRequest(chunkAssistant, phaseName, prompt, outputDirectory, chunkResult, 2);
            chunkResult = McpRetryHandler.retryOnProjectOverviewOnly(chunkAssistant, phaseName, prompt, outputDirectory, chunkResult, 2);
            chunkResult = McpRetryHandler.retryOnMemberUuidNotFound(chunkAssistant, phaseName, prompt, outputDirectory, chunkResult, 2);
            chunkResult = McpRetryHandler.retryOnDuplicateClassesAmbiguous(chunkAssistant, phaseName, prompt, outputDirectory, chunkResult, 2);
            chunkResult = McpFailurePatterns.acceptSatisfaitOnlyFailure(phaseName, chunkResult);
            validateMcpExecutionResult(phaseName, chunkResult);
            chunkReports.add("### " + phaseName + System.lineSeparator() + chunkResult.trim());
            chunkPhaseNames.add(phaseName);
            collectedUuids.addAll(extractUUIDs(chunkResult));
        }

        String asBuiltPlantUml = PlantUmlParser.buildAsBuiltDomainPlantUml(parts.classBlocks, parts.relationLines);
        StringBuilder aggregated = new StringBuilder();
        aggregated.append("=== DOMAIN MODEL CHUNKED EXECUTION ===").append(System.lineSeparator());
        aggregated.append("Chunk strategy applied because model volume exceeded single-pass safety limits.").append(System.lineSeparator());
        aggregated.append("Classes: ").append(parts.classBlocks.size())
                .append(", Relations: ").append(parts.relationLines.size()).append(System.lineSeparator()).append(System.lineSeparator());
        aggregated.append(String.join(System.lineSeparator() + System.lineSeparator(), chunkReports)).append(System.lineSeparator());

        List<String> deduplicatedUuids = PlantUmlParser.deduplicatePreservingOrder(collectedUuids);
        if (asBuiltPlantUml != null) {
            aggregated.append(System.lineSeparator())
                    .append("```plantuml").append(System.lineSeparator())
                    .append(asBuiltPlantUml).append(System.lineSeparator())
                    .append("```").append(System.lineSeparator());
        }
        String synthesizedJson = PlantUmlParser.synthesizeDomainModelJsonWithFallback(asBuiltPlantUml, deduplicatedUuids);
        if (synthesizedJson != null) {
            aggregated.append(System.lineSeparator())
                    .append("```json").append(System.lineSeparator())
                    .append(synthesizedJson).append(System.lineSeparator())
                    .append("```").append(System.lineSeparator());
        }

        consolidateDomainPhaseChunkTraces(outputDirectory, chunkPhaseNames);
        return aggregated.toString();
    }


    private static void consolidateDomainPhaseChunkTraces(String outputDirectory, List<String> chunkPhaseNames) {
        if (outputDirectory == null || outputDirectory.isBlank() || chunkPhaseNames == null || chunkPhaseNames.isEmpty()) {
            return;
        }
        Path outDir = Path.of(outputDirectory);
        StringBuilder promptAggregate = new StringBuilder();
        StringBuilder traceAggregate = new StringBuilder();
        for (String chunkPhaseName : chunkPhaseNames) {
            appendFileIfExists(promptAggregate, outDir.resolve(chunkPhaseName + "_prompt.txt"), chunkPhaseName);
            appendFileIfExists(traceAggregate, outDir.resolve(chunkPhaseName + "_mcp_trace.txt"), chunkPhaseName);
        }
        if (!promptAggregate.isEmpty()) {
            saveDebugFile(promptAggregate.toString(), "domain_model_phase_prompt.txt", outputDirectory);
        }
        if (!traceAggregate.isEmpty()) {
            saveDebugFile(traceAggregate.toString(), "domain_model_phase_mcp_trace.txt", outputDirectory);
        }
    }

    private static void appendFileIfExists(StringBuilder target, Path path, String title) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            target.append("==== ").append(title).append(" ====").append(System.lineSeparator());
            target.append(Files.readString(path)).append(System.lineSeparator()).append(System.lineSeparator());
        } catch (IOException e) {
            debug("⚠️ Could not read chunk debug file " + path + ": " + e.getMessage());
        }
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

    private static String ensureStructuredRequirementsResult(String result) {
        if (extractJSONStructure(result, "requirements_created", "exigences_creees") != null) {
            return result;
        }
        if (result == null || result.trim().isEmpty()) {
            return result;
        }
        if (result.trim().startsWith("MCP_EXECUTION_FAILED:") || result.trim().startsWith("❌") || result.trim().startsWith("[error:")) {
            return result;
        }

        List<String> realUuids = extractUUIDs(result);
        
        // If we have real UUIDs, MCP work was done - synthesize minimal JSON structure
        if (!realUuids.isEmpty()) {
            String synthesizedJson = synthesizeMinimalRequirementsJson(realUuids);
            if (synthesizedJson != null) {
                debug("⚠️ requirements_phase returned no structured JSON; synthesized fallback requirements_created payload from " + realUuids.size() + " UUIDs");
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

    private static String synthesizeMinimalRequirementsJson(List<String> realUuids) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            ArrayNode requirementsArray = root.putArray("requirements_created");

            // Heuristic: the first UUID is likely the package, the rest are requirements
            for (int i = (realUuids.isEmpty() ? 0 : 1); i < realUuids.size(); i++) {
                ObjectNode req = requirementsArray.addObject();
                req.put("uuid", realUuids.get(i));
                req.put("synthesized", true);
            }

            root.put("total_requirements", requirementsArray.size());
            if (!realUuids.isEmpty()) {
                root.put("package_uuid", realUuids.get(0));
                root.put("container_uuid", realUuids.get(0));
            }

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            debug("⚠️ Could not synthesize minimal requirements_created JSON: " + e.getMessage());
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
        String asBuiltPlantUml = PlantUmlParser.extractPlantUMLDiagram(result, "MODELE_DOMAINE_AS_BUILT");
        if (asBuiltPlantUml == null) {
            asBuiltPlantUml = PlantUmlParser.extractPlantUMLDiagram(result, "AS_BUILT_DOMAIN_MODEL");
        }
        if (asBuiltPlantUml != null && PlantUMLAnalyzer.isValidPlantUML(asBuiltPlantUml) && !realUuids.isEmpty()) {
            String synthesizedJson = PlantUmlParser.synthesizeDomainModelJson(asBuiltPlantUml, realUuids);
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
            String synthesizedJson = PlantUmlParser.synthesizeMinimalDomainModelJson(realUuids);
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

    private static String ensureStructuredUseCasesResult(String result) {
        if (extractJSONStructure(result, "use_cases_created") != null) {
            return result;
        }
        if (result == null || result.trim().isEmpty()) {
            return result;
        }
        if (result.trim().startsWith("MCP_EXECUTION_FAILED:") || result.trim().startsWith("❌") || result.trim().startsWith("[error:")) {
            return result;
        }

        List<String> realUuids = extractUUIDs(result);

        // Fallback 1: PlantUML + UUIDs → synthesize a full JSON payload from the as-built output.
        String useCasesPlantUml = PlantUmlParser.extractPlantUMLDiagram(result, "USE_CASES_DIAGRAM");
        if (useCasesPlantUml != null && PlantUMLAnalyzer.isValidPlantUML(useCasesPlantUml) && !realUuids.isEmpty()) {
            String synthesizedJson = PlantUmlParser.synthesizeUseCasesJson(useCasesPlantUml, realUuids);
            if (synthesizedJson != null) {
                debug("⚠️ use_cases_phase returned no structured JSON; synthesized fallback use_cases_created payload from as-built PlantUML");
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

        // Fallback 2: UUIDs present but no PlantUML → synthesize a minimal JSON payload from UUIDs.
        if (!realUuids.isEmpty()) {
            String synthesizedJson = PlantUmlParser.synthesizeMinimalUseCasesJson(realUuids);
            if (synthesizedJson != null) {
                debug("⚠️ use_cases_phase returned no structured JSON or PlantUML; synthesized minimal use_cases_created payload from " + realUuids.size() + " UUIDs");
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


    private static void validateMcpExecutionResult(String phaseName, String result, String... expectedJsonKeys) {
        if (result == null || result.trim().isEmpty()) {
            throw new IllegalStateException("No output returned for phase '" + phaseName + "'");
        }

        String trimmed = result.trim();
        if (trimmed.startsWith("MCP_EXECUTION_FAILED:") || trimmed.startsWith("❌") || trimmed.startsWith("[error:")) {
            throw new IllegalStateException(trimmed);
        }

        if (McpFailurePatterns.MANUAL_INSTRUCTIONS_PATTERN.matcher(result).find()) {
            throw new IllegalStateException(
                    "The LLM returned manual instructions instead of executing MCP tools during phase '" + phaseName + "'");
        }

        if (PLACEHOLDER_UUID_VALUE_PATTERN.matcher(result).find() && !REAL_UUID_PATTERN.matcher(result).find()) {
            throw new IllegalStateException(
                    "The LLM returned placeholder UUID values instead of real MCP UUIDs during phase '" + phaseName + "'");
        }

        // More lenient JSON validation: if we have real UUIDs, MCP work was done even if JSON is malformed
        if (expectedJsonKeys != null && expectedJsonKeys.length > 0) {
            String jsonStructure = extractJSONStructure(result, expectedJsonKeys);
            if (jsonStructure == null) {
                // Check if MCP work was actually done (evidenced by real UUIDs)
                List<String> realUuids = extractUUIDs(result);
                if (realUuids.isEmpty()) {
                    // No UUIDs and no JSON = genuine failure
                    throw new IllegalStateException(
                            "No structured JSON result was returned for phase '" + phaseName + "'");
                }
                // UUIDs present = MCP work was done, just output formatting issue
                debug("⚠️ Phase '" + phaseName + "' missing JSON structure but has " + realUuids.size() + " UUIDs (MCP work confirmed)");
            }
        }
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

