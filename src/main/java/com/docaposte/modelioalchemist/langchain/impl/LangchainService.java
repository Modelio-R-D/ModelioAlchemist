package com.docaposte.modelioalchemist.langchain.impl;

import java.time.Duration;
import java.util.ArrayList;
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
import com.docaposte.modelioalchemist.langchain.impl.AzureEndpointResolver;
import com.docaposte.modelioalchemist.langchain.impl.HttpPolicies;
import com.docaposte.modelioalchemist.langchain.impl.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
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
                        parsedReqLog.append(String.format("  Priority: %s\n\n", req.priority));
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
                        reqContext.append(String.format("Priority: %s\n\n", req.priority));
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
            
            // Créer le prompt pour l'assistant
            String prompt = createModelGenerationPrompt(plantUMLContent, requirementsDocuments, parsedRequirements);
            
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
    
    /**
     * Génère un modèle UML avec documents d'analyse et répertoire de sortie pour les fichiers de debug
     */
    public static String generateModelFromPlantUML(String plantUMLContent, String requirementsDocuments, String outputDirectory, String mcpSseUrl, PolicyAwareAzureChatModel chatModel) {
        return generateModelFromPlantUMLInternal(plantUMLContent, requirementsDocuments, outputDirectory, mcpSseUrl, chatModel);
    }

    /**
     * Crée les exigences dans Modelio à partir des exigences filtrées
     */
    public static String createRequirementsInModelio(String filteredRequirementsJson, String outputDirectory, String mcpSseUrl, PolicyAwareAzureChatModel chatModel) {
        ensureInfrastructureInitialized(mcpSseUrl, chatModel);
        
        try {
            debug("🎯 Creating requirements in Modelio from filtered JSON...");
            
            // Parse les exigences filtrées
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(filteredRequirementsJson);
            
            if (!root.has("filtered_requirements")) {
                return "❌ No filtered_requirements found in JSON";
            }
            
            StringBuilder requirementsPrompt = new StringBuilder();
            requirementsPrompt.append("Créez les exigences suivantes dans Modelio :\n\n");
            
            JsonNode filteredReqs = root.get("filtered_requirements");
            int reqCount = 0;
            for (JsonNode reqNode : filteredReqs) {
                String id = reqNode.get("id").asText();
                String description = reqNode.get("description").asText();
                String category = reqNode.get("category").asText();
                String priority = reqNode.get("priority").asText();
                
                requirementsPrompt.append(String.format("EXIGENCE %s:\n", id));
                requirementsPrompt.append(String.format("- Description: %s\n", description));
                requirementsPrompt.append(String.format("- Catégorie: %s\n", category));
                requirementsPrompt.append(String.format("- Priorité: %s\n\n", priority));
                reqCount++;
            }
            
            debug("📝 Creating " + reqCount + " requirements in Modelio...");
            
            PooledUmlAssistant pa = borrowAssistant();
            if (pa == null) {
                return "❌ Could not borrow assistant for requirements creation";
            }
            
            try {
                String result = pa.assistant.createUmlModel(requirementsPrompt.toString());
                debug("✅ Requirements creation completed");
                
                // Sauvegarder le rapport
                if (outputDirectory != null) {
                    Files.writeString(Path.of(outputDirectory).resolve("requirements_creation_report.txt"), result);
                }
                
                return result;
            } finally {
                try {
                    ASSISTANT_POOL.offer(pa);
                } catch (Exception e) {
                    debug("Warning: Could not return assistant to pool: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            debug("❌ Error creating requirements in Modelio: " + e.getMessage());
            return "❌ Error creating requirements: " + e.getMessage();
        }
    }

    /**
     * Crée le modèle de classes UML dans Modelio à partir des résultats d'analyse
     */
    public static String createUmlClassModel(String analysisResults, String outputDirectory, String mcpSseUrl, PolicyAwareAzureChatModel chatModel) {
        ensureInfrastructureInitialized(mcpSseUrl, chatModel);
        
        try {
            debug("🏗️ Creating UML class model in Modelio from PlantUML diagram...");
            
            String modelPrompt = """
                Analysez le diagramme PlantUML suivant et créez un modèle de classes UML complet et fidèle dans Modelio.
                
                Votre mission :
                1. Parser TOUTES les classes définies dans le PlantUML
                2. Créer chaque classe avec TOUS ses attributs et méthodes
                3. Respecter EXACTEMENT les types et noms spécifiés
                4. Créer TOUTES les associations/relations définies (-->, --|>, --o, --*, etc.)
                5. Organiser en packages si définis dans le PlantUML
                6. Préserver les cardinalités et rôles des relations
                
                Instructions techniques :
                - Utilisez les outils MCP Modelio pour créer chaque élément
                - Travaillez séquentiellement : classes d'abord, puis relations
                - Gardez les noms EXACTS du PlantUML (pas de "amélioration")
                - Pour les types complexes, utilisez 'String' comme fallback
                - Créez les packages si spécifiés (package "NomPackage" { })
                
                Diagramme PlantUML à implémenter dans Modelio :
                
                """ + analysisResults;
            
            PooledUmlAssistant pa = borrowAssistant();
            if (pa == null) {
                return "❌ Could not borrow assistant for UML model creation";
            }
            
            try {
                String result = pa.assistant.createUmlModel(modelPrompt);
                debug("✅ UML class model creation completed");
                
                // Sauvegarder le rapport
                if (outputDirectory != null) {
                    Files.writeString(Path.of(outputDirectory).resolve("uml_model_creation_report.txt"), result);
                }
                
                return result;
            } finally {
                try {
                    ASSISTANT_POOL.offer(pa);
                } catch (Exception e) {
                    debug("Warning: Could not return assistant to pool: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            debug("❌ Error creating UML model in Modelio: " + e.getMessage());
            return "❌ Error creating UML model: " + e.getMessage();
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
                Vous êtes un expert en identification d'exigences système. Votre mission est de FILTRER le texte pour ne conserver QUE les vraies exigences opérationnelles.
                
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
                
                FORMAT DE SORTIE - JSON uniquement :
                {
                  "filtered_requirements": [
                    {
                      "id": "REQ-001",
                      "description": "Le système doit permettre l'authentification des utilisateurs via SSO",
                      "category": "Security",
                      "priority": "High"
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
                            String id = reqNode.get("id").asText("REQ-????");
                            String description = reqNode.get("description").asText("");
                            String category = reqNode.get("category").asText("Functional");
                            String priority = reqNode.get("priority").asText("Medium");
                            
                            if (!description.trim().isEmpty()) {
                                requirements.add(new Requirement(id, id, description, category, priority));
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
                
                requirements.set(i, new Requirement(req.id, req.title, cleanDesc, category, priority));
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
                    id = matcher.group(1).trim();
                    description = matcher.group(2).trim();
                    break;
                case "WORD":
                    id = "REQ" + String.format("%03d", Integer.parseInt(matcher.group(2)));
                    description = matcher.group(3).trim();
                    break;
                case "NUMBERED":
                    id = "REQ" + String.format("%03d", Integer.parseInt(matcher.group(1)));
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
                String id = "REQ" + String.format("%03d", count + 1);
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
    
    /**
     * Détecte la catégorie d'une exigence basée sur son contenu
     */
    private static String detectRequirementCategory(String description) {
        String lowerDesc = description.toLowerCase();
        
        if (lowerDesc.contains("sécurité") || lowerDesc.contains("security") || 
            lowerDesc.contains("authentification") || lowerDesc.contains("authorization") ||
            lowerDesc.contains("chiffrement") || lowerDesc.contains("encryption")) {
            return "Security";
        }
        
        if (lowerDesc.contains("performance") || lowerDesc.contains("temps de réponse") ||
            lowerDesc.contains("response time") || lowerDesc.contains("débit") ||
            lowerDesc.contains("throughput") || lowerDesc.contains("latence")) {
            return "Performance";
        }
        
        if (lowerDesc.contains("interface") || lowerDesc.contains("ui") || 
            lowerDesc.contains("utilisateur") || lowerDesc.contains("user") ||
            lowerDesc.contains("ergonomie") || lowerDesc.contains("usability")) {
            return "UI/UX";
        }
        
        if (lowerDesc.contains("intégration") || lowerDesc.contains("integration") ||
            lowerDesc.contains("api") || lowerDesc.contains("service") ||
            lowerDesc.contains("connexion") || lowerDesc.contains("connection")) {
            return "Integration";
        }
        
        return "Functional";  // Par défaut
    }
    
    /**
     * Détecte la priorité d'une exigence basée sur son contenu
     */
    private static String detectRequirementPriority(String description) {
        String lowerDesc = description.toLowerCase();
        
        if (lowerDesc.contains("critique") || lowerDesc.contains("critical") ||
            lowerDesc.contains("obligatoire") || lowerDesc.contains("mandatory") ||
            lowerDesc.contains("essentiel") || lowerDesc.contains("essential")) {
            return "High";
        }
        
        if (lowerDesc.contains("optionnel") || lowerDesc.contains("optional") ||
            lowerDesc.contains("souhaitable") || lowerDesc.contains("nice to have") ||
            lowerDesc.contains("bonus")) {
            return "Low";
        }
        
        return "Medium";  // Par défaut
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
     * Crée un prompt inspiré de ModelioBot - pour génération Requirements → Use Cases → Classes
     */
    private static String createModelGenerationPrompt(String plantUMLContent, String requirementsDocuments, List<Requirement> parsedRequirements) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("You are a Modelio modeling assistant with access to MCP tools for comprehensive model creation.\n\n");
        
        prompt.append("## Mission\n");
        prompt.append("Create a complete software model in Modelio following this sequence: Requirements → Use Cases → Classes → Associations.\n");
        prompt.append("You MUST use the available MCP tools to create every single element.\n\n");
        
        // Section requirements documents si disponible
        if (requirementsDocuments != null && !requirementsDocuments.trim().isEmpty()) {
            prompt.append("Use the provided requirements and analysis documents as the PRIMARY source for requirements creation.\n");
            prompt.append("The PlantUML diagram shows the target technical design to implement these requirements.\n\n");
        } else {
            prompt.append("Analyze the PlantUML diagram and create ALL corresponding elements with proper organization.\n");
        }
        
        // Section exigences parsées si disponible
        if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
            prompt.append("## Parsed Requirements Available\n");
            prompt.append("The following requirements have been automatically extracted from the analysis documents:\n\n");
            
            for (Requirement req : parsedRequirements) {
                prompt.append(String.format("- **%s**: %s (Category: %s, Priority: %s)\n", 
                    req.id, req.description, req.category, req.priority));
            }
            
            prompt.append("\n🚨 MANDATORY: Create these EXACT requirements in Modelio using MCP tools.\n");
            prompt.append("Use the requirement creation tools with the provided ID, description, category, and priority.\n\n");
        } else if (requirementsDocuments != null && !requirementsDocuments.trim().isEmpty()) {
            prompt.append("## Requirements and Analysis Documents Available\n");
            prompt.append("The requirements documents have been provided in the chat context.\n");
            prompt.append("🚨 MANDATORY: Use MCP tools to extract and create proper requirements in Modelio.\n");
            prompt.append("Establish traceability: Requirements → Use Cases → Classes.\n\n");
        }
        
        prompt.append("## PlantUML to Process\n");
        prompt.append("```plantuml\n");
        prompt.append(plantUMLContent);
        prompt.append("\n```\n\n");
        
        prompt.append("## MANDATORY Execution Sequence - USE MCP TOOLS FOR EACH STEP\n");
        if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
            prompt.append("1. **🚨 Requirements Creation (MANDATORY)**: Use MCP requirement creation tools for each parsed requirement\n");
            prompt.append("   - Create " + parsedRequirements.size() + " requirements using their exact details\n");
            prompt.append("   - Use tools that create analyst requirement elements\n");
        } else if (requirementsDocuments != null && !requirementsDocuments.trim().isEmpty()) {
            prompt.append("1. **🚨 Requirements Creation (MANDATORY)**: Use MCP tools to import requirements from analysis documents\n");
        } else {
            prompt.append("1. **🚨 Requirements Creation (MANDATORY)**: Use MCP tools to create placeholder requirements\n");
        }
        prompt.append("2. **Package Structure**: Use MCP tools to create packages\n");
        prompt.append("   - Create 'Requirements' package for all requirements\n");
        prompt.append("   - Create 'Use Cases' package for use case diagrams\n");
        prompt.append("   - Create 'Domain Model' package for business classes\n");
        prompt.append("3. **Use Cases & Actors**: Use MCP tools to create use cases and actors\n");
        prompt.append("   - Create actors (users, external systems)\n");
        prompt.append("   - Create use cases for main functionalities\n");
        prompt.append("   - Create associations between actors and use cases\n");
        prompt.append("4. **Domain Classes**: Use MCP tools to create all classes with attributes\n");
        prompt.append("5. **🚨 CRITICAL: Associations Creation (MANDATORY)**: Use MCP association tools\n");
        prompt.append("   - This step is MANDATORY and often forgotten\n");
        prompt.append("   - Parse ALL PlantUML relationships: -->, --|>, --o, --*, etc.\n");
        prompt.append("   - Use MCP tools to create associations ONLY after all classes exist\n");
        prompt.append("   - YOU MUST EXPLICITLY USE ASSOCIATION CREATION TOOLS\n");
        prompt.append("6. **Verify**: Ensure complete model with all relationships\n\n");
        
        prompt.append("## Core Instructions - MCP TOOLS USAGE\n");
        prompt.append("- 🚨 YOU MUST USE MCP TOOLS FOR EVERY ELEMENT CREATION\n");
        prompt.append("- Work sequentially: complete each phase before moving to the next\n");
        prompt.append("- Keep track of UUIDs for all created elements for relationships\n");
        prompt.append("- Create meaningful names for requirements based on PlantUML analysis\n");
        prompt.append("- Organize elements in appropriate packages for maintainability\n");
        prompt.append("- Use 'String' as fallback type for complex/unknown attribute types\n\n");
        
        prompt.append("## 🚨 REQUIREMENTS CREATION - MANDATORY ACTION\n");
        if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
            prompt.append("- YOU MUST CREATE ALL " + parsedRequirements.size() + " PARSED REQUIREMENTS\n");
            prompt.append("- Use MCP requirement creation tools for each requirement\n");
            prompt.append("- Create each requirement exactly as specified with ID, description, category, and priority\n");
            prompt.append("- Do not skip any parsed requirement - they are all mandatory\n");
        } else {
            prompt.append("- WARNING: No requirements were parsed from the provided documents\n");
            prompt.append("- Create placeholder requirements using MCP tools based on PlantUML analysis\n");
            prompt.append("- Example format: 'REQ001: System shall manage user authentication'\n");
        }
        prompt.append("- Requirements are ANALYST REQUIREMENT elements in Modelio\n");
        prompt.append("- Use appropriate MCP tools that create requirement elements\n\n");
        
        prompt.append("## Use Case Guidelines - MCP TOOLS\n");
        prompt.append("- Use MCP tools to identify and create actors (users, external systems)\n");
        prompt.append("- Use MCP tools to create use cases for main functionalities: 'Manage Products', 'Process Orders'\n");
        prompt.append("- Use MCP tools to create actors like: 'Customer', 'Administrator', 'External System'\n");
        prompt.append("- Use MCP tools to link actors to relevant use cases with associations\n\n");
        
        prompt.append("## Package Organization - MCP TOOLS\n");
        prompt.append("- Use MCP package creation tools for 'Requirements' package: all requirements\n");
        prompt.append("- Use MCP package creation tools for 'Use Cases' package: actors, use cases, and their relationships\n");
        prompt.append("- Use MCP package creation tools for 'Domain Model' package: business classes, attributes, associations\n");
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
        
        prompt.append("## ⚠️  CRITICAL SUCCESS FACTORS ⚠️\n");
        prompt.append("1. **🚨 REQUIREMENTS CREATION (MANDATORY)**: If parsed requirements are provided, USE MCP TOOLS TO CREATE ALL OF THEM\n");
        prompt.append("   - Each requirement must be created as an analyst requirement element\n");
        prompt.append("   - Use the exact ID, description, category, and priority provided\n");
        prompt.append("2. **🚨 ASSOCIATIONS CREATION (MANDATORY)**: USE MCP ASSOCIATION TOOLS\n");
        prompt.append("   - This step is frequently omitted but is MANDATORY\n");
        prompt.append("   - Look for: -->, --|>, --o, --*, <|-- in PlantUML\n");
        prompt.append("   - Use MCP tools to create associations ONLY after all classes exist\n");
        prompt.append("   - Each PlantUML relationship must become a Modelio association\n");
        prompt.append("3. **🚨 COMPLETENESS (MANDATORY)**: The model must be complete (packages + requirements + use cases + classes + associations)\n");
        prompt.append("   - No element can be skipped\n");
        prompt.append("   - Every PlantUML element must have a corresponding Modelio element\n\n");
        
        prompt.append("## 🚨 ASSOCIATION CREATION GUIDELINES - USE MCP TOOLS\n");
        prompt.append("- Create associations ONLY after all classes and attributes are complete\n");
        prompt.append("- Parse PlantUML relationships: -->, --|>, --o, --*, etc.\n");
        prompt.append("- Use MCP association tools with appropriate UML association types:\n");
        prompt.append("  * --> = Simple Association\n");
        prompt.append("  * --|> = Generalization/Inheritance\n");
        prompt.append("  * --o = Aggregation\n");
        prompt.append("  * --* = Composition\n");
        prompt.append("- Set proper multiplicities using MCP tools: 1, 0..1, 1..*, 0..*, etc.\n");
        prompt.append("- Keep role names from PlantUML when specified\n");
        prompt.append("- YOU MUST CREATE EVERY SINGLE ASSOCIATION FROM THE PLANTUML\n\n");
        
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
        
        prompt.append("## Start Now - MANDATORY ACTIONS\n");
        prompt.append("BEGIN IMMEDIATELY with the following MANDATORY sequence:\n\n");
        
        if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
            prompt.append("🚨 PHASE 1 - REQUIREMENTS CREATION (MANDATORY):\n");
            prompt.append("- You have " + parsedRequirements.size() + " parsed requirements to create\n");
            prompt.append("- Use MCP requirement creation tools for EACH requirement\n");
            prompt.append("- Create requirements as analyst requirement elements\n");
            prompt.append("- Report the UUID of each created requirement\n\n");
        } else {
            prompt.append("🚨 PHASE 1 - REQUIREMENTS CREATION (MANDATORY):\n");
            prompt.append("- Analyze PlantUML to identify functional requirements\n");
            prompt.append("- Use MCP requirement creation tools to create them\n");
            prompt.append("- Report the UUID of each created requirement\n\n");
        }
        
        prompt.append("🚨 PHASE 2 - PACKAGES CREATION:\n");
        prompt.append("- Use MCP tools to create 'Requirements', 'Use Cases', and 'Domain Model' packages\n");
        prompt.append("- Report the UUID of each created package\n\n");
        
        prompt.append("🚨 PHASE 3 - USE CASES & ACTORS:\n");
        prompt.append("- Use MCP tools to create actors and use cases\n");
        prompt.append("- Use MCP tools to create associations between actors and use cases\n");
        prompt.append("- Report UUIDs of all created elements\n\n");
        
        prompt.append("🚨 PHASE 4 - CLASSES CREATION:\n");
        prompt.append("- Use MCP tools to create all classes from PlantUML\n");
        prompt.append("- Use MCP tools to add all attributes to each class\n");
        prompt.append("- Report UUIDs of all created classes\n\n");
        
        prompt.append("🚨 PHASE 5 - ASSOCIATIONS CREATION (CRITICAL):\n");
        prompt.append("- Parse EVERY relationship in the PlantUML: -->, --|>, --o, --*, <|--\n");
        prompt.append("- Use MCP association creation tools for EACH relationship\n");
        prompt.append("- This is the MOST IMPORTANT step - DO NOT SKIP ANY ASSOCIATION\n");
        prompt.append("- Report UUIDs of all created associations\n\n");
        
        prompt.append("## Final Instructions\n");
        prompt.append("- Use MCP tools for EVERY element creation - no exceptions\n");
        prompt.append("- Report progress after each phase completion\n");
        prompt.append("- Provide detailed reports including names, metaclasses, and UUIDs\n");
        prompt.append("- If any tool fails, report the error and retry with adjusted parameters\n");
        prompt.append("- The model is only complete when ALL phases are finished successfully\n");
        
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
