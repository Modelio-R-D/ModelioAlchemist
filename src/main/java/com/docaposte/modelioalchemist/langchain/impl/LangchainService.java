package com.docaposte.modelioalchemist.langchain.impl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import dev.langchain4j.data.message.SystemMessage;
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
                .maxSequentialToolsInvocations(25);  // Permettre assez d'appels d'outils pour toutes les classes
                
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
     * Génère un modèle UML dans Modelio à partir du contenu PlantUML
     * en utilisant l'approche poolée partagée de ModelioBot.
     */
    public static String generateModelFromPlantUML(String plantUMLContent, String mcpSseUrl, PolicyAwareAzureChatModel chatModel) {
        debug("generateModelFromPlantUML plantLen=" + (plantUMLContent == null ? -1 : plantUMLContent.length()));
        
        PooledUmlAssistant pa = null;
        try {
            ensureInfrastructureInitialized(mcpSseUrl, chatModel);
            pa = borrowAssistant();
            
            ChatMemory chatMemory = pa.memory;
            
            // Injecter les ressources MCP (limité)
            injectMcpResources(chatMemory);
            
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
            String prompt = createModelGenerationPrompt(plantUMLContent);
            
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
     * Crée un prompt détaillé et précis pour l'assistant IA
     */
    private static String createModelGenerationPrompt(String plantUMLContent) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Vous êtes un assistant expert en modélisation UML et en utilisation d'outils MCP Modelio.\n\n");
        
        prompt.append("MISSION : Analysez le diagramme PlantUML ci-dessous et créez les éléments UML correspondants dans Modelio en utilisant les outils MCP disponibles.\n\n");
        
        prompt.append("DIAGRAMME PLANTUML À ANALYSER :\n");
        prompt.append("```plantuml\n");
        prompt.append(plantUMLContent);
        prompt.append("\n```\n\n");
        
        prompt.append("INSTRUCTIONS DÉTAILLÉES :\n\n");
        
        prompt.append("1. ANALYSE PRÉALABLE :\n");
        prompt.append("   - Identifiez CHAQUE classe définie dans le PlantUML\n");
        prompt.append("   - Comptez le nombre total de classes (il devrait y en avoir environ 18)\n");
        prompt.append("   - Listez leurs noms exacts pour vérification\n");
        prompt.append("   - Relevez TOUS les attributs de chaque classe\n");
        prompt.append("   - Inventoriez TOUTES les relations entre classes\n");
        prompt.append("   - Notez les méthodes/opérations si présentes\n\n");
        
        prompt.append("2. CRÉATION DES ÉLÉMENTS :\n");
        prompt.append("   - CRITIQUE: Créez les classes UNE PAR UNE, jamais plusieurs simultanément\n");
        prompt.append("   - Créez une classe, attendez sa confirmation, puis passez à la suivante\n");
        prompt.append("   - Respectez les noms EXACTS des classes du PlantUML\n");
        prompt.append("   - Ordre: D'abord toutes les classes (une par une), puis attributs, puis relations\n");
        prompt.append("   - Pour chaque classe créée, ajoutez immédiatement ses attributs\n");
        prompt.append("   - Créez ensuite les associations/relations entre classes\n");
        prompt.append("   - Signalez clairement les succès et échecs\n\n");
        
        prompt.append("⚠️  CONTRAINTES TECHNIQUES CRITIQUES ⚠️ :\n");
        prompt.append("- Le serveur Modelio NE SUPPORTE QU'UNE SEULE TRANSACTION À LA FOIS\n");
        prompt.append("- Vous DEVEZ créer les éléments UN PAR UN, JAMAIS EN PARALLÈLE\n");
        prompt.append("- Créez une classe → Attendez la réponse → Créez la suivante\n");
        prompt.append("- Si vous tentez de créer plusieurs classes simultanément, toutes échoueront sauf la première\n");
        prompt.append("- En cas d'erreur de transaction, arrêtez et signalez le problème\n\n");
        
        prompt.append("CRITÈRES DE SUCCÈS :\n");
        prompt.append("- TOUTES les classes du PlantUML doivent être créées (environ 18 classes)\n");
        prompt.append("- Aucun élément ne doit être oublié ou omis\n");
        prompt.append("- Exécution séquentielle (pas de parallélisme)\n");
        prompt.append("- Rapport complet listant chaque classe créée\n");
        prompt.append("- Confirmation finale du nombre total de classes créées\n");
        prompt.append("- Gestion des erreurs transparente\n\n");
        
        prompt.append("COMMENCEZ MAINTENANT :\n");
        prompt.append("1. Analysez le PlantUML pour identifier TOUTES les classes (il y en a environ 18)\n");
        prompt.append("2. Créez la PREMIÈRE classe et attendez la confirmation\n"); 
        prompt.append("3. Créez la DEUXIÈME classe et attendez la confirmation\n");
        prompt.append("4. Créez la TROISIÈME classe et attendez la confirmation\n");
        prompt.append("5. CONTINUEZ ainsi une par une jusqu'à avoir créé TOUTES les classes du PlantUML\n");
        prompt.append("6. NE VOUS ARRÊTEZ PAS après quelques classes - créez-les TOUTES\n");
        prompt.append("7. À la fin, confirmez que vous avez créé toutes les classes identifiées");
        
        return prompt.toString();
    }
    
    // -------------------------------------------------- Méthodes de compatibilité --------------------------------------------------
    
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
