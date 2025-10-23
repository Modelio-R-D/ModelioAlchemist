package com.docaposte.modelioalchemist.langchain.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpResource;
import dev.langchain4j.mcp.client.McpResourceContents;
import dev.langchain4j.mcp.client.McpTextResourceContents;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.AiServices;
import com.docaposte.modelioalchemist.langchain.impl.PolicyAwareAzureChatModel;
/**
 * Service LangChain4j unifié pour ModelioAlchemist suivant le pattern de ModelioBot.
 * Implémentation poolée : réutilise le client MCP, le fournisseur d'outils, et un petit pool d'instances UmlModelingAssistant.
 * Évite la configuration de connexion par requête tout en gardant les conversations isolées.
 */
public class LangchainService {

    // -------------------------------------------------- Logging --------------------------------------------------
    private static final boolean DEBUG = true;
    private static void debug(String msg) { 
        if (DEBUG) System.out.println("[LangchainService] " + msg); 
    }

    // -------------------------------------------------- Configuration --------------------------------------------------
    private static final int POOL_SIZE = 2;                    // max assistants parallèles
    private static final long POOL_BORROW_TIMEOUT_MS = 5000;
    private static final String DEFAULT_MCP_SSE_URL = "http://localhost:8080/sse";
    
    // -------------------------------------------------- Infrastructure partagée (construite une fois) --------------------------------------------------
    private static volatile boolean infraInitialized = false;
    private static PolicyAwareAzureChatModel sharedChatModel;
    private static McpToolProvider sharedToolProvider;        // optionnel, peut être null
    private static DefaultMcpClient sharedMcpClient;          // pour shutdown; connexion unique
    private static HttpMcpTransport sharedTransport;          // pour shutdown
    private static final ArrayBlockingQueue<PooledUmlAssistant> ASSISTANT_POOL = new ArrayBlockingQueue<>(POOL_SIZE);
    
    // Cache des ressources MCP
    private static List<McpResource> cachedResources = new ArrayList<>();

    /**
     * Initialise l'infrastructure partagée (client MCP, modèle de chat, pool d'assistants)
     */
    private static synchronized void ensureInfrastructureInitialized(String mcpSseUrl, PolicyAwareAzureChatModel chatModel) {
        if (infraInitialized) return;
        
        sharedChatModel = chatModel;
        String actualMcpUrl = (mcpSseUrl != null && !mcpSseUrl.isEmpty()) ? mcpSseUrl : DEFAULT_MCP_SSE_URL;
        
        // MCP
        try {
            sharedTransport = new HttpMcpTransport.Builder()
                    .sseUrl(actualMcpUrl)
                    .timeout(Duration.ofMinutes(5))
                    .logRequests(true)
                    .logResponses(false)
                    .build();
                    
            sharedMcpClient = new DefaultMcpClient.Builder()
                    .transport(sharedTransport)
                    .build();
                    
            try {
                // Lister les outils
                sharedMcpClient.listTools().forEach(ts -> debug("MCP Tool: " + ts.name()));
                
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
        } catch (Throwable t) { 
            debug("MCP init failed (continuing without tools/resources): " + t.getMessage()); 
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
                .maxSequentialToolsInvocations(100);  // Permettre la création de modèles UML complexes (classes + attributs + associations)
                
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

    // -------------------------------------------------- API Publique (poolée) --------------------------------------------------
    
    /**
     * Génère un modèle UML dans Modelio à partir du contenu PlantUML et des documents d'analyse
     * en utilisant l'approche poolée partagée de ModelioBot.
     */
    public static String generateModelFromPlantUML(String plantUMLContent, String requirementsDocuments, String mcpSseUrl, PolicyAwareAzureChatModel chatModel) {
        debug("generateModelFromPlantUML plantLen=" + (plantUMLContent == null ? -1 : plantUMLContent.length()) + 
              ", reqDocsLen=" + (requirementsDocuments == null ? -1 : requirementsDocuments.length()));
        
        PooledUmlAssistant pa = null;
        try {
            ensureInfrastructureInitialized(mcpSseUrl, chatModel);
            pa = borrowAssistant();
            
            ChatMemory chatMemory = pa.memory;
            
            // Injecter les ressources MCP (limité)
            injectMcpResources(chatMemory);
            
            // Injecter les documents d'analyse dans le contexte (priorité)
            if (requirementsDocuments != null && !requirementsDocuments.trim().isEmpty()) {
                String reqSnippet = requirementsDocuments.length() > 15000 ? 
                        requirementsDocuments.substring(0, 15000) + "\n... (truncated)" : requirementsDocuments;
                        
                chatMemory.add(UserMessage.from("Context - Requirements and Analysis Documents:\n" + reqSnippet));
                debug("Injected requirements documents: " + reqSnippet.length() + " chars");
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
            
            // Créer le prompt pour l'assistant
            String prompt = createModelGenerationPrompt(plantUMLContent, requirementsDocuments);
            
            // Laisser l'assistant IA gérer la création du modèle avec les outils MCP disponibles
            String result = pa.assistant.createUmlModel(prompt);
            
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
     * Crée un prompt inspiré de ModelioBot - pour génération Requirements → Use Cases → Classes
     */
    private static String createModelGenerationPrompt(String plantUMLContent, String requirementsDocuments) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("You are a Modelio modeling assistant with access to MCP tools for comprehensive model creation.\n\n");
        
        prompt.append("## Mission\n");
        prompt.append("Create a complete software model in Modelio following this sequence: Requirements → Use Cases → Classes.\n");
        
        // Section requirements documents si disponible
        if (requirementsDocuments != null && !requirementsDocuments.trim().isEmpty()) {
            prompt.append("Use the provided requirements and analysis documents as the PRIMARY source for requirements creation.\n");
            prompt.append("The PlantUML diagram shows the target technical design to implement these requirements.\n\n");
        } else {
            prompt.append("Analyze the PlantUML diagram and create ALL corresponding elements with proper organization.\n");
        }
        
        // Section documents d'analyse si disponible
        if (requirementsDocuments != null && !requirementsDocuments.trim().isEmpty()) {
            prompt.append("## Requirements and Analysis Documents Available\n");
            prompt.append("The requirements documents have been provided in the chat context.\n");
            prompt.append("Use these documents to extract and create proper requirements in Modelio.\n");
            prompt.append("Establish traceability: Requirements → Use Cases → Classes.\n\n");
        }
        
        prompt.append("## PlantUML to Process\n");
        prompt.append("```plantuml\n");
        prompt.append(plantUMLContent);
        prompt.append("\n```\n\n");
        
        prompt.append("## MANDATORY Execution Sequence\n");
        if (requirementsDocuments != null && !requirementsDocuments.trim().isEmpty()) {
            prompt.append("1. **Requirements Creation**: Import requirements from provided analysis documents\n");
        } else {
            prompt.append("1. **Requirements Creation**: Create placeholder requirements if no documents provided\n");
        }
        prompt.append("2. **Package Structure**: Create packages to organize the model\n");
        prompt.append("   - Create 'Use Cases' package for use case diagrams\n");
        prompt.append("   - Create 'Domain Model' package for business classes\n");
        prompt.append("3. **Use Cases & Actors**: Create use cases and actors\n");
        prompt.append("   - Create actors (users, external systems)\n");
        prompt.append("   - Create use cases for main functionalities\n");
        prompt.append("   - Create associations between actors and use cases\n");
        prompt.append("4. **Domain Classes**: Create all classes with attributes and associations\n");
        prompt.append("5. **Verify**: Ensure complete model with all relationships\n\n");
        
        prompt.append("## Core Instructions\n");
        prompt.append("- Work sequentially: complete each phase before moving to the next\n");
        prompt.append("- Keep track of UUIDs for all created elements for relationships\n");
        prompt.append("- Create meaningful names for requirements based on PlantUML analysis\n");
        prompt.append("- Organize elements in appropriate packages for maintainability\n");
        prompt.append("- Use 'String' as fallback type for complex/unknown attribute types\n\n");
        
        prompt.append("## Requirements Guidelines\n");
        prompt.append("- Requirements should come from existing analysis documents (functional, technical, RSSI, RSE)\n");
        prompt.append("- If requirements documents are available, import/reference those existing requirements\n");
        prompt.append("- Only create placeholder requirements if no existing analysis is provided\n");
        prompt.append("- Example placeholder format: 'REQ001: System shall manage user authentication'\n");
        prompt.append("- Focus on linking the PlantUML model to existing business requirements\n");
        prompt.append("- Ask user for requirements documents if available for proper traceability\n");
        prompt.append("- Traceability: Requirements → Use Cases → Classes should reflect real project needs\n\n");
        
        prompt.append("## Use Case Guidelines\n");
        prompt.append("- Identify actors (users, external systems) from PlantUML context\n");
        prompt.append("- Create use cases for main functionalities: 'Manage Products', 'Process Orders'\n");
        prompt.append("- Create actors like: 'Customer', 'Administrator', 'External System'\n");
        prompt.append("- Link actors to relevant use cases with associations\n\n");
        
        prompt.append("## Package Organization\n");
        prompt.append("- 'Requirements' package: all requirements\n");
        prompt.append("- 'Use Cases' package: actors, use cases, and their relationships\n");
        prompt.append("- 'Domain Model' package: business classes, attributes, associations\n");
        prompt.append("- Use packages to create clean model architecture\n\n");
        
        prompt.append("## Class Creation Guidelines (Critical for Success)\n");
        prompt.append("- Create ONE class at a time, wait for confirmation before next\n");
        prompt.append("- ALWAYS create the class FIRST, then add attributes separately\n");
        prompt.append("- Keep class names exactly as in PlantUML: preserve original naming\n");
        prompt.append("- Never modify class names or try to 'improve' them\n\n");
        
        prompt.append("## Attribute Creation Guidelines (Critical for Success)\n");
        prompt.append("- Add attributes ONLY AFTER the class is successfully created\n");
        prompt.append("- Create ONE attribute at a time, wait for confirmation\n");
        prompt.append("- Parse PlantUML attributes carefully: handle 'type name' and 'name: type' formats\n");
        prompt.append("- For complex types not in Modelio, use 'String' as fallback type\n");
        prompt.append("- Example types: String, Integer, Boolean, Double, Date\n");
        prompt.append("- Keep attribute names exactly as specified in PlantUML\n");
        prompt.append("- Handle special characters and spaces in attribute names properly\n\n");
        
        prompt.append("## Association Creation Guidelines\n");
        prompt.append("- Create associations ONLY after all classes and attributes are complete\n");
        prompt.append("- Parse PlantUML relationships: -->, --|>, --o, --*, etc.\n");
        prompt.append("- Use appropriate UML association types: Association, Aggregation, Composition\n");
        prompt.append("- Set proper multiplicities: 1, 0..1, 1..*, 0..*, etc.\n");
        prompt.append("- Keep role names from PlantUML when specified\n\n");
        
        prompt.append("## Element Types Needed\n");
        prompt.append("- Requirements: analyst requirement elements\n");
        prompt.append("- Packages: UML packages for organization\n");
        prompt.append("- Use Cases: UML use case elements\n");
        prompt.append("- Actors: UML actor elements\n");
        prompt.append("- Classes: UML class elements with attributes\n");
        prompt.append("- Associations: relationships between elements\n\n");
        
        prompt.append("## Critical Technical Constraints\n");
        prompt.append("- Modelio supports only ONE transaction at a time\n");
        prompt.append("- Create elements sequentially, never in parallel\n");
        prompt.append("- Wait for confirmation before creating next element\n");
        prompt.append("- Report any transaction errors immediately\n\n");
        
        prompt.append("## Success Criteria\n");
        prompt.append("- Complete requirements coverage of PlantUML functionality\n");
        prompt.append("- Well-organized package structure\n");
        prompt.append("- All use cases with proper actor associations\n");
        prompt.append("- Complete domain model with classes, attributes, and relationships\n");
        prompt.append("- UUIDs tracked for all elements\n\n");
        
        prompt.append("## Start Now\n");
        prompt.append("Begin with Phase 1: Requirements Creation. Analyze the PlantUML to identify functional requirements and create them. ");
        prompt.append("Then proceed systematically through packages, use cases, and finally domain classes. ");
        prompt.append("Provide detailed progress reports including names, metaclasses, and UUIDs of all created elements.\n");
        
        return prompt.toString();
    }    // -------------------------------------------------- Méthodes de compatibilité --------------------------------------------------
    
    /**
     * Instance du service pour compatibilité avec l'ancienne interface (non-statique)
     */
    private final PolicyAwareAzureChatModel instanceChatModel;
    
    /**
     * Constructeur pour compatibilité avec l'ancienne interface PipelineRunner
     */
    public LangchainService(String apiKey, String baseUrl, String deployment, boolean debug) {
        // Create PolicyAwareAzureChatModel for this instance
        AzureEndpointResolver.AzureEndpointInfo info = AzureEndpointResolver.resolve(
            baseUrl, deployment != null ? deployment : "", "gpt-4o");
        
        com.azure.ai.openai.OpenAIAsyncClient client = buildClient(info, apiKey);
        this.instanceChatModel = new PolicyAwareAzureChatModel(client, info, 0.9);
        
        if (debug) {
            debug("LangchainService instance created for compatibility");
        }
    }
    
    /**
     * Méthode de compatibilité pour runPrompt (interface non-statique)
     */
    public String runPrompt(String systemPrompt, String userContext) {
        try {
            debug("Running prompt with system prompt length: " + 
                  (systemPrompt != null ? systemPrompt.length() : 0));
            debug("User context length: " + 
                  (userContext != null ? userContext.length() : 0));
            
            // Create messages
            dev.langchain4j.data.message.SystemMessage systemMessage = dev.langchain4j.data.message.SystemMessage.from(systemPrompt != null ? systemPrompt : "");
            dev.langchain4j.data.message.UserMessage userMessage = dev.langchain4j.data.message.UserMessage.from(userContext != null ? userContext : "");
            
            // Execute chat
            dev.langchain4j.model.chat.response.ChatResponse response = instanceChatModel.chat(java.util.Arrays.asList(systemMessage, userMessage));
            
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
     * Méthode de compatibilité pour getChatModel
     */
    public PolicyAwareAzureChatModel getChatModel() {
        return instanceChatModel;
    }
    
    /**
     * Construit le client Azure OpenAI (méthode utilitaire)
     */
    private static com.azure.ai.openai.OpenAIAsyncClient buildClient(AzureEndpointResolver.AzureEndpointInfo info, String aadToken) {
        com.azure.ai.openai.OpenAIClientBuilder builder = new com.azure.ai.openai.OpenAIClientBuilder()
            .endpoint(info.endpoint)
            .httpLogOptions(new com.azure.core.http.policy.HttpLogOptions().setLogLevel(com.azure.core.http.policy.HttpLogDetailLevel.BODY_AND_HEADERS))
            .addPolicy(HttpPolicies.auth(aadToken))
            .addPolicy(HttpPolicies.capture());
            
        if (!aadToken.isEmpty()) {
            builder.credential(HttpPolicies.staticTokenCredential(aadToken));
            debug("Using token credential");
        } else {
            debug("No AAD token provided; requests may fail due to missing auth");
        }
        
        return builder.buildAsyncClient();
    }
}
