package com.docaposte.modelioalchemist.langchain.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpResource;
import dev.langchain4j.mcp.client.McpResourceContents;
import dev.langchain4j.mcp.client.McpTextResourceContents;
import dev.langchain4j.memory.ChatMemory;
import com.docaposte.modelioalchemist.langchain.impl.PolicyAwareAzureChatModel;
import com.docaposte.modelioalchemist.langchain.impl.AzureEndpointResolver;
import com.docaposte.modelioalchemist.langchain.impl.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
/**
 * Service LangChain4j unifié pour ModelioAlchemist suivant le pattern de ModelioBot.
 * Implémentation poolée : réutilise le client MCP, le fournisseur d'outils, et un petit pool d'instances UmlModelingAssistant.
 * Évite la configuration de connexion par requête tout en gardant les conversations isolées.
 *
 * <p>Ce fichier est la façade publique de l'orchestration ModelioAlchemist. L'infrastructure MCP
 * partagée vit dans {@link McpAssistantPool}, la création directe des exigences dans
 * {@link RequirementCreationService}, le traitement des lots du modèle de domaine dans
 * {@link DomainModelChunker}, le post-traitement des sorties d'agent dans
 * {@link AgentResultProcessor}, et la construction des clients Azure OpenAI par instance dans
 * {@link AzureChatModelFactory}.</p>
 */
public class LangchainService {

    private static void debug(String msg) {
        McpAssistantPool.debug(msg);
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
            McpAssistantPool.ensureInfrastructureInitialized(mcpSseUrl, chatModel);
            McpAssistantPool.ensureMcpToolsAvailable();
            pa = McpAssistantPool.borrowAssistant();

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
                McpAssistantPool.saveDebugFile(requirementsDocuments, "requirements_input.txt", outputDirectory);
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

                    McpAssistantPool.saveDebugFile(parsedReqLog.toString(), "parsed_requirements.txt", outputDirectory);
                    debug("Saved parsed requirements debug file");
                } else {
                    McpAssistantPool.saveDebugFile("NO REQUIREMENTS PARSED - Check patterns and input format", "parsed_requirements_EMPTY.txt", outputDirectory);
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
                McpAssistantPool.sharedMcpClient().listTools(); // Test de connexion
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
            McpAssistantPool.releaseAssistant(pa);
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
        McpAssistantPool.ensureInfrastructureInitialized(mcpSseUrl, chatModel);
        McpAssistantPool.ensureMcpToolsAvailable();

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
                String normalizedId = RequirementIdFormatter.normalizeRequirementId(reqNode.path("id").asText(null), index);
                String description = reqNode.path("description").asText("");
                String origin = RequirementIdFormatter.buildRequirementOrigin(reqNode, sourceDocumentName, description);
                requirements.add(new Requirement(
                        normalizedId,
                        normalizedId,
                        description,
                        reqNode.path("category").asText("Fonctionnel"),
                        reqNode.path("priority").asText("Moyenne"),
                        origin));
                index++;
            }

            String result = RequirementCreationService.createRequirementsDirectlyViaMcp(requirements, outputDirectory);
            AgentResultProcessor.validateMcpExecutionResult("requirements", result, "requirements_created", "exigences_creees");
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
        McpAssistantPool.ensureInfrastructureInitialized(mcpSseUrl, chatModel);
        McpAssistantPool.ensureMcpToolsAvailable();

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
                    requirementsResult = RequirementCreationService.createRequirementsDirectlyViaMcp(parsedRequirements, outputDirectory);
                } else {
                    String requirementsPrompt = UmlPromptBuilder.createRequirementsPrompt(parsedRequirements);

                    PooledUmlAssistant pa1 = McpAssistantPool.borrowAssistant();
                    if (pa1 == null) {
                        return "❌ Could not borrow assistant for requirements creation";
                    }

                    try {
                        requirementsResult = McpRetryHandler.executeAssistantWithMcpTrace(pa1, "requirements_phase", requirementsPrompt, outputDirectory);
                        // Apply fallback synthesis for requirements
                        requirementsResult = AgentResultProcessor.ensureStructuredRequirementsResult(requirementsResult);
                    } finally {
                        try {
                            McpAssistantPool.assistantPool().offer(pa1);
                        } catch (Exception e) {
                            debug("Warning: Could not return assistant to pool: " + e.getMessage());
                        }
                    }
                }
            }
            AgentResultProcessor.validateMcpExecutionResult("requirements_phase", requirementsResult, "requirements_created", "exigences_creees");
            finalReport.append("PHASE 1 - REQUIREMENTS:\n").append(requirementsResult).append("\n\n");

            List<String> requirementsUUIDs = AgentResultProcessor.extractRequirementUUIDs(requirementsResult);
            String requirementsJSON = AgentResultProcessor.extractJSONStructure(requirementsResult, "requirements_created", "exigences_creees");

            debug("✅ Requirements creation completed - UUIDs extracted: " + requirementsUUIDs.size());
            if (requirementsJSON != null) {
                debug("📊 Requirements JSON structure extracted successfully");
            }

            // Extract only requirement element UUIDs (exclude package/container UUIDs)
            List<String> requirementUUIDs = AgentResultProcessor.extractRequirementUUIDs(requirementsResult);
            debug("📋 Extracted " + requirementUUIDs.size() + " requirement UUIDs from Phase 1 for downstream linking");

            // PHASE 2 : Création des Use Cases et Actors
            debug("👥 PHASE 2: Creating Use Cases and Actors...");
            // Résolution déterministe (sans LLM) d'un unique package "Cas d'Usage" : sans elle, le
            // LLM improvisait le nom du package à chaque appel ('Cas d Usage', 'Cas d'utilisation'...),
            // créant plusieurs packages distincts pour le même contenu logique.
            String useCasesPackageUuid = McpAssistantPool.findOrCreatePackage("Cas d'Usage", McpAssistantPool.getProjectRootUuid());
            String useCasesPrompt = UmlPromptBuilder.createUseCasesPrompt(
                    analysisResults,
                    requirementsResult,
                    null,
                    parsedRequirements,
                    requirementUUIDs,
                    useCasesPackageUuid);

            PooledUmlAssistant pa2 = McpAssistantPool.borrowAssistant();
            if (pa2 == null) {
                return "❌ Could not borrow assistant for use cases creation";
            }

            String useCasesResult;
            try {
                useCasesResult = McpRetryHandler.executeAssistantWithMcpTrace(pa2, "use_cases_phase", useCasesPrompt, outputDirectory);
                useCasesResult = McpRetryHandler.retryOnMissingRequirementTargetUuid(pa2, "use_cases_phase", useCasesPrompt, outputDirectory, useCasesResult, 2);
                useCasesResult = AgentResultProcessor.ensureStructuredUseCasesResult(useCasesResult);
                useCasesResult = McpFailurePatterns.acceptSatisfaitOnlyFailure("use_cases_phase", useCasesResult);
                AgentResultProcessor.validateMcpExecutionResult("use_cases_phase", useCasesResult, "use_cases_created");
                finalReport.append("PHASE 2 - USE CASES & ACTORS:\n").append(useCasesResult).append("\n\n");

                // 🔍 EXTRACTION AUTOMATIQUE DES STRUCTURES
                List<String> useCasesUUIDs = AgentResultProcessor.extractUUIDs(useCasesResult);
                String useCasesJSON = AgentResultProcessor.extractJSONStructure(useCasesResult, "use_cases_created");
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
                    McpAssistantPool.assistantPool().offer(pa2);
                } catch (Exception e) {
                    debug("Warning: Could not return assistant to pool: " + e.getMessage());
                }
            }

            // PHASE 3 : Création des Classes et Associations
            debug("🏛️ PHASE 3: Creating Classes and Associations...");
            debug("📋 Reusing Phase 1 requirement UUIDs for Phase 3 linking");
            // No requirementUUIDs: «Satisfait» links are forbidden in the domain-model phase.
            // Résolution déterministe (sans LLM) d'un unique package "Modèle de Domaine" : sans elle,
            // le LLM en créait un nouveau à chaque appel (ou par lot, en mode chunké), dupliquant tout
            // le modèle de domaine sous plusieurs packages racine distincts.
            String domainPackageUuid = McpAssistantPool.findOrCreatePackage("Modèle de Domaine", McpAssistantPool.getProjectRootUuid());
            String classesPrompt = UmlPromptBuilder.createClassesPrompt(
                    analysisResults,
                    requirementsResult,
                    parsedRequirements,
                    domainPackageUuid);

            PooledUmlAssistant pa3 = McpAssistantPool.borrowAssistant();
            if (pa3 == null) {
                return "❌ Could not borrow assistant for classes creation";
            }

            String classesResult;
            try {
                classesResult = DomainModelChunker.executePhase2DomainModelWithChunking(
                        pa3,
                        analysisResults,
                        requirementsResult,
                        parsedRequirements,
                        requirementUUIDs,
                        classesPrompt,
                        domainPackageUuid,
                        outputDirectory);
                classesResult = AgentResultProcessor.ensureStructuredDomainModelResult(classesResult);
                classesResult = McpFailurePatterns.acceptSatisfaitOnlyFailure("domain_model_phase", classesResult);
                AgentResultProcessor.validateMcpExecutionResult("domain_model_phase", classesResult, "domain_model_created", "modele_domaine_cree");
                finalReport.append("PHASE 3 - CLASSES & ASSOCIATIONS:\n").append(classesResult).append("\n\n");

                // 🔍 EXTRACTION AUTOMATIQUE DES STRUCTURES
                List<String> classesUUIDs = AgentResultProcessor.extractUUIDs(classesResult);
                String domainModelJSON = AgentResultProcessor.extractJSONStructure(classesResult, "domain_model_created", "modele_domaine_cree");
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
                    McpAssistantPool.assistantPool().offer(pa3);
                } catch (Exception e) {
                    debug("Warning: Could not return assistant to pool: " + e.getMessage());
                }
            }

            // Résumé final
            finalReport.append("=== RÉSUMÉ FINAL ===\n");
            finalReport.append("✅ PHASE 1: Requirements créés\n");
            finalReport.append("✅ PHASE 2: Use cases et actors créés\n");
            finalReport.append("✅ PHASE 3: Classes et associations créées\n");
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
        List<McpResource> cachedResources = McpAssistantPool.cachedResources();
        if (cachedResources == null || cachedResources.isEmpty()) return;

        // sélection simple : premières 3 ressources texte
        List<McpResource> selected = cachedResources.stream().limit(3).collect(Collectors.toList());
        int injected = 0;

        for (McpResource res : selected) {
            try {
                McpReadResourceResult rr = McpAssistantPool.sharedMcpClient().readResource(res.uri());
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
            PooledUmlAssistant filterAssistant = McpAssistantPool.borrowAssistant();
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
                            String id = RequirementIdFormatter.normalizeRequirementId(reqNode.path("id").asText(null), requirements.size() + 1);
                            String description = reqNode.get("description").asText("");
                            String category = reqNode.get("category").asText("Fonctionnel");
                            String priority = reqNode.get("priority").asText("Moyenne");
                            String origin = RequirementIdFormatter.buildRequirementOrigin(reqNode, "Documents d'analyse", description);

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
                    McpAssistantPool.assistantPool().offer(filterAssistant);
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

        com.azure.ai.openai.OpenAIAsyncClient client = AzureChatModelFactory.buildClient(info, this.instanceApiKey);
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
            com.azure.ai.openai.OpenAIAsyncClient client = AzureChatModelFactory.buildClient(info, instanceApiKey);
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
}
