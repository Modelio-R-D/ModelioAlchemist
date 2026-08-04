package com.docaposte.modelioalchemist.langchain.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Chunked execution of the "PHASE 3" domain-model creation (classes + associations) for large
 * PlantUML inputs that would otherwise exhaust a single agent's tool-call budget. Extracted from
 * {@code LangchainService}.
 */
final class DomainModelChunker {

    private DomainModelChunker() {}

    // Abaissé de 45 à 25 : un agent unique gérant classes + attributs + associations + diagramme en
    // un seul passage s'est montré non fiable dès ~34 classes (échecs intermittents rapportant
    // "aucun outil MCP exécuté" alors que des associations avaient bien été créées plus tôt dans la
    // même conversation) — le chemin chunké, avec ses contrôles déterministes par lot, est robuste
    // à cette taille et le surcoût (quelques appels LLM de plus) est négligeable face à la fiabilité.
    private static final int PHASE2_CHUNKING_CLASS_THRESHOLD = 25;
    private static final int PHASE2_CHUNKING_RELATION_THRESHOLD = 60;
    // 10 (not 20): each class needs 1 create + N addMember calls for its attributes/operations.
    // Larger chunks exhaust the agent's tool-call budget before any attribute is added.
    private static final int PHASE2_CLASSES_CHUNK_SIZE = 10;
    private static final int PHASE2_ASSOCIATIONS_CHUNK_SIZE = 25;

    /**
     * Vérifie directement dans le modèle (appel MCP déterministe, sans LLM) quelles classes du lot
     * sont réellement présentes. Un agent peut épuiser son budget d'appels d'outils avant d'avoir
     * créé toutes les classes demandées : sans ce contrôle, l'omission reste silencieuse et fait
     * échouer la phase d'associations bien plus loin.
     *
     * @return les noms attendus mais absents du modèle (jamais {@code null}).
     */
    private static List<String> findMissingClassesInModel(List<String> expectedClassNames) {
        List<String> missing = new ArrayList<>();
        if (expectedClassNames == null || expectedClassNames.isEmpty()) {
            return missing;
        }
        for (String className : expectedClassNames) {
            if (className == null || className.isBlank()) continue;
            if (!classExistsInModel(className)) {
                missing.add(className);
            }
        }
        return missing;
    }

    /**
     * Recherche une classe par nom exact via {@code uml_searchElements}. En cas d'erreur de
     * vérification (MCP indisponible, réponse illisible) on répond {@code true} : le contrôle ne
     * doit jamais bloquer un lot par lui-même, il ne sert qu'à détecter une omission avérée.
     */
    private static boolean classExistsInModel(String className) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode arguments = mapper.createObjectNode();
            arguments.put("name_query", className);
            arguments.put("type_filter", "class");

            String response = McpAssistantPool.sharedMcpClient().executeTool(ToolExecutionRequest.builder()
                    .id("verify-class-" + className)
                    .name("uml_searchElements")
                    .arguments(mapper.writeValueAsString(arguments))
                    .build());

            return responseContainsExactName(response, className);
        } catch (Exception e) {
            McpAssistantPool.debug("⚠️ Vérification de la classe '" + className + "' impossible : " + e.getMessage());
            return true;
        }
    }

    /**
     * {@code uml_searchElements} filtre par sous-chaîne : une recherche de « Recours » renvoie aussi
     * « RecoursRAPO ». On exige donc une correspondance exacte sur un champ {@code name}.
     */
    private static boolean responseContainsExactName(String response, String expectedName) {
        if (response == null || response.isBlank()) {
            return false;
        }
        try {
            return jsonContainsExactName(new ObjectMapper().readTree(response), expectedName);
        } catch (Exception e) {
            // Réponse non JSON : repli sur le nom entre guillemets, qui reste une égalité stricte.
            return response.contains("\"" + expectedName + "\"");
        }
    }

    private static boolean jsonContainsExactName(JsonNode node, String expectedName) {
        if (node == null) {
            return false;
        }
        if (node.isObject()) {
            JsonNode nameNode = node.get("name");
            if (nameNode != null && nameNode.isTextual() && expectedName.equalsIgnoreCase(nameNode.asText())) {
                return true;
            }
        }
        for (JsonNode child : node) {
            if (jsonContainsExactName(child, expectedName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Crée le diagramme de classes déterministiquement, à partir des classes réellement présentes
     * dans le modèle. Extrait de la seule branche "chunked" à l'origine : le mode non-chunké
     * (petits documents, < {@link #PHASE2_CHUNKING_CLASS_THRESHOLD} classes) renvoyait son résultat
     * sans jamais créer de diagramme, même quand le prompt LLM le demandait — un agent unique gérant
     * classes + attributs + associations + diagramme en un seul passage épuise son budget d'appels
     * d'outils avant cette dernière étape, sans qu'aucun contrôle déterministe ne le détecte.
     * Échoue silencieusement (retourne {@code null}) : un diagramme manquant n'invalide pas un
     * modèle déjà créé.
     */
    private static String attemptClassDiagramCreation(List<String> createdClassNames, String outputDirectory) {
        try {
            PooledUmlAssistant diagramAssistant = McpAssistantPool.newAssistant();
            String diagramPhase = "domain_model_phase_class_diagram";
            String diagramPrompt = UmlPromptBuilder.createClassDiagramPrompt(createdClassNames);
            String diagramResult = McpRetryHandler.executeAssistantWithMcpTraceWithRetry(
                    diagramAssistant, diagramPhase, diagramPrompt, outputDirectory, 2);
            diagramResult = McpRetryHandler.retryOnProjectOverviewOnly(
                    diagramAssistant, diagramPhase, diagramPrompt, outputDirectory, diagramResult, 2);
            return "### " + diagramPhase + System.lineSeparator() + diagramResult.trim();
        } catch (Exception e) {
            McpAssistantPool.debug("⚠️ Class diagram phase failed (non-fatal): " + e.getMessage());
            return null;
        }
    }

    /** Indique si une ligne de relation PlantUML référence l'un des noms de classes donnés. */
    private static boolean relationReferencesAnyOf(String relationLine, Set<String> classNames) {
        if (relationLine == null || classNames == null || classNames.isEmpty()) {
            return false;
        }
        for (String name : classNames) {
            if (name == null || name.isBlank()) continue;
            if (Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(relationLine).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Exécute un lot (chunk) de la phase domaine avec la chaîne complète de reprises sur incident.
     * Partagé par les lots de classes et d'associations, dont le traitement d'erreur est identique.
     */
    private static String runChunkPhaseWithRetries(
            PooledUmlAssistant assistant,
            String phaseName,
            String prompt,
            String outputDirectory) throws IOException {
        String result = McpRetryHandler.executeAssistantWithMcpTraceWithRetry(assistant, phaseName, prompt, outputDirectory, 2);
        result = McpRetryHandler.retryOnMissingRequirementTargetUuid(assistant, phaseName, prompt, outputDirectory, result, 2);
        result = McpRetryHandler.retryOnMissingModelingRequest(assistant, phaseName, prompt, outputDirectory, result, 2);
        result = McpRetryHandler.retryOnProjectOverviewOnly(assistant, phaseName, prompt, outputDirectory, result, 2);
        result = McpRetryHandler.retryOnMemberUuidNotFound(assistant, phaseName, prompt, outputDirectory, result, 2);
        result = McpRetryHandler.retryOnDuplicateClassesAmbiguous(assistant, phaseName, prompt, outputDirectory, result, 2);
        result = McpFailurePatterns.acceptSatisfaitOnlyFailure(phaseName, result);
        AgentResultProcessor.validateMcpExecutionResult(phaseName, result);
        return result;
    }

    static String executePhase2DomainModelWithChunking(
            PooledUmlAssistant assistant,
            String analysisResults,
            String requirementsResult,
            List<Requirement> parsedRequirements,
            List<String> requirementUUIDs,
            String defaultPrompt,
            String domainPackageUuid,
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
            classesResult = McpFailurePatterns.acceptSatisfaitOnlyFailure("domain_model_phase", classesResult);

            // Contrôle déterministe : l'agent unique gère classes + attributs + associations +
            // diagramme en un seul passage et peut épuiser son budget d'appels d'outils avant la
            // dernière étape (le diagramme) sans que rien ne le signale. On (re)tente donc toujours
            // la création du diagramme explicitement ; le prompt lui-même est idempotent (réutilise
            // un diagramme existant au lieu d'en créer un second si l'agent principal a déjà réussi).
            List<String> missing = findMissingClassesInModel(parts.classNames);
            List<String> createdClassNames = new ArrayList<>(parts.classNames);
            createdClassNames.removeAll(missing);
            String diagramReport = attemptClassDiagramCreation(createdClassNames, outputDirectory);
            if (diagramReport != null) {
                classesResult = classesResult + System.lineSeparator() + System.lineSeparator() + diagramReport;
            }
            return classesResult;
        }

        McpAssistantPool.debug("📦 domain_model_phase switched to chunked mode: "
                + parts.classBlocks.size() + " classes, " + parts.relationLines.size() + " relations.");

        // "Modèle de Domaine" est résolu une seule fois par l'appelant (voir LangchainService) et
        // transmis ici : chaque lot tourne avec un agent frais (newAssistant()) sans mémoire des
        // autres, donc sans un UUID partagé imposé, plusieurs lots créaient chacun leur propre
        // package racine via uml_createElement, dupliquant tout le modèle de domaine.
        McpAssistantPool.debug("📦 Package 'Modèle de Domaine' ancré sur uuid=" + domainPackageUuid
                + " — partagé par tous les lots de classes.");

        List<String> chunkReports = new ArrayList<>();
        List<String> chunkPhaseNames = new ArrayList<>();
        List<String> collectedUuids = new ArrayList<>();

        List<List<String>> classChunks = PlantUmlParser.splitIntoChunks(parts.classBlocks, PHASE2_CLASSES_CHUNK_SIZE);
        List<List<String>> classNameChunks = PlantUmlParser.splitIntoChunks(parts.classNames, PHASE2_CLASSES_CHUNK_SIZE);
        // Classes réellement présentes dans le modèle après exécution : c'est cette liste — et non
        // les noms issus du PlantUML — qui alimente les phases suivantes.
        List<String> createdClassNames = new ArrayList<>();
        List<String> unresolvedClassNames = new ArrayList<>();
        for (int i = 0; i < classChunks.size(); i++) {
            PooledUmlAssistant chunkAssistant = McpAssistantPool.newAssistant();
            String phaseName = "domain_model_phase_classes_chunk_" + (i + 1);
            String prompt = UmlPromptBuilder.createClassesChunkPrompt(
                    requirementsResult,
                    parsedRequirements,
                    requirementUUIDs,
                    classChunks.get(i),
                    i + 1,
                    classChunks.size(),
                    domainPackageUuid);
            String chunkResult = runChunkPhaseWithRetries(chunkAssistant, phaseName, prompt, outputDirectory);
            chunkReports.add("### " + phaseName + System.lineSeparator() + chunkResult.trim());
            chunkPhaseNames.add(phaseName);
            collectedUuids.addAll(AgentResultProcessor.extractUUIDs(chunkResult));

            // Contrôle déterministe : l'agent a-t-il vraiment créé les classes du lot ? Il peut
            // épuiser son budget d'outils sur les attributs et s'arrêter avant la dernière classe.
            List<String> expectedNames = i < classNameChunks.size() ? classNameChunks.get(i) : List.of();
            List<String> missing = findMissingClassesInModel(expectedNames);
            if (!missing.isEmpty()) {
                McpAssistantPool.debug("⚠️ " + phaseName + " : " + missing.size() + "/" + expectedNames.size()
                        + " classes absentes du modèle (" + String.join(", ", missing) + ") — relance ciblée.");
                List<String> missingBlocks = new ArrayList<>();
                for (String name : missing) {
                    int idx = parts.classNames.indexOf(name);
                    if (idx >= 0 && idx < parts.classBlocks.size()) {
                        missingBlocks.add(parts.classBlocks.get(idx));
                    }
                }
                if (!missingBlocks.isEmpty()) {
                    String recoveryPhase = phaseName + "_recover_missing_classes";
                    String recoveryPrompt = UmlPromptBuilder.createClassesChunkPrompt(
                            requirementsResult,
                            parsedRequirements,
                            requirementUUIDs,
                            missingBlocks,
                            i + 1,
                            classChunks.size(),
                            domainPackageUuid);
                    try {
                        String recoveryResult = runChunkPhaseWithRetries(
                                McpAssistantPool.newAssistant(), recoveryPhase, recoveryPrompt, outputDirectory);
                        chunkReports.add("### " + recoveryPhase + System.lineSeparator() + recoveryResult.trim());
                        chunkPhaseNames.add(recoveryPhase);
                        collectedUuids.addAll(AgentResultProcessor.extractUUIDs(recoveryResult));
                    } catch (Exception e) {
                        McpAssistantPool.debug("⚠️ " + recoveryPhase + " a échoué : " + e.getMessage());
                    }
                    missing = findMissingClassesInModel(expectedNames);
                }
            }

            for (String name : expectedNames) {
                if (missing.contains(name)) {
                    unresolvedClassNames.add(name);
                } else {
                    createdClassNames.add(name);
                }
            }
            if (!missing.isEmpty()) {
                McpAssistantPool.debug("❌ " + phaseName + " : classes toujours absentes après relance ("
                        + String.join(", ", missing) + "). Les relations qui les référencent seront ignorées.");
            }
        }

        // Les relations vers une classe absente échoueraient : on les écarte explicitement plutôt
        // que de laisser la phase d'associations planter sur une classe introuvable.
        List<String> relationLinesToCreate = parts.relationLines;
        if (!unresolvedClassNames.isEmpty()) {
            Set<String> unresolved = new LinkedHashSet<>(unresolvedClassNames);
            List<String> kept = new ArrayList<>();
            for (String line : parts.relationLines) {
                if (!relationReferencesAnyOf(line, unresolved)) {
                    kept.add(line);
                }
            }
            McpAssistantPool.debug("⚠️ " + (parts.relationLines.size() - kept.size()) + "/" + parts.relationLines.size()
                    + " relations ignorées car elles référencent des classes non créées.");
            relationLinesToCreate = kept;
        }

        List<List<String>> relationChunks = PlantUmlParser.splitIntoChunks(relationLinesToCreate, PHASE2_ASSOCIATIONS_CHUNK_SIZE);
        for (int i = 0; i < relationChunks.size(); i++) {
            PooledUmlAssistant chunkAssistant = McpAssistantPool.newAssistant();
            String phaseName = "domain_model_phase_associations_chunk_" + (i + 1);
            String prompt = UmlPromptBuilder.createAssociationsChunkPrompt(
                    requirementsResult,
                    requirementUUIDs,
                    createdClassNames,
                    relationChunks.get(i),
                    i + 1,
                    relationChunks.size());
            String chunkResult = runChunkPhaseWithRetries(chunkAssistant, phaseName, prompt, outputDirectory);
            chunkReports.add("### " + phaseName + System.lineSeparator() + chunkResult.trim());
            chunkPhaseNames.add(phaseName);
            collectedUuids.addAll(AgentResultProcessor.extractUUIDs(chunkResult));
        }

        // Phase 3C: the class diagram itself. Failures here are non-fatal — the model is already built.
        String diagramReport = attemptClassDiagramCreation(createdClassNames, outputDirectory);
        if (diagramReport != null) {
            chunkReports.add(diagramReport);
            chunkPhaseNames.add("domain_model_phase_class_diagram");
        }

        // L'as-built ne doit décrire que ce qui existe réellement dans le modèle.
        List<String> createdClassBlocks = new ArrayList<>();
        for (String name : createdClassNames) {
            int idx = parts.classNames.indexOf(name);
            if (idx >= 0 && idx < parts.classBlocks.size()) {
                createdClassBlocks.add(parts.classBlocks.get(idx));
            }
        }
        String asBuiltPlantUml = PlantUmlParser.buildAsBuiltDomainPlantUml(createdClassBlocks, relationLinesToCreate);
        StringBuilder aggregated = new StringBuilder();
        aggregated.append("=== DOMAIN MODEL CHUNKED EXECUTION ===").append(System.lineSeparator());
        aggregated.append("Chunk strategy applied because model volume exceeded single-pass safety limits.").append(System.lineSeparator());
        aggregated.append("Classes: ").append(createdClassBlocks.size()).append("/").append(parts.classBlocks.size())
                .append(", Relations: ").append(relationLinesToCreate.size()).append("/").append(parts.relationLines.size())
                .append(System.lineSeparator());
        if (!unresolvedClassNames.isEmpty()) {
            aggregated.append("⚠️ Classes non créées malgré relance (").append(unresolvedClassNames.size()).append(") : ")
                    .append(String.join(", ", unresolvedClassNames)).append(System.lineSeparator())
                    .append("Les relations les référençant ont été ignorées.").append(System.lineSeparator());
        }
        aggregated.append(System.lineSeparator());
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
            McpAssistantPool.saveDebugFile(promptAggregate.toString(), "domain_model_phase_prompt.txt", outputDirectory);
        }
        if (!traceAggregate.isEmpty()) {
            McpAssistantPool.saveDebugFile(traceAggregate.toString(), "domain_model_phase_mcp_trace.txt", outputDirectory);
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
            McpAssistantPool.debug("⚠️ Could not read chunk debug file " + path + ": " + e.getMessage());
        }
    }
}
