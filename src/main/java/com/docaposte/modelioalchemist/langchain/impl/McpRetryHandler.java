package com.docaposte.modelioalchemist.langchain.impl;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Handles all MCP retry strategies for the UML model creation pipeline.
 * Each {@code retryOn*} method receives the current result, checks whether its pattern
 * matches, and if so fires one or more follow-up conversations with the assistant.
 */
class McpRetryHandler {

    // ------------------------------------------------------------------ low-level execution

    static String executeAssistantWithMcpTrace(
            PooledUmlAssistant pa,
            String phaseName,
            String prompt,
            String outputDirectory) throws IOException {
        if (outputDirectory != null && !outputDirectory.trim().isEmpty()) {
            saveDebugFile(prompt + System.lineSeparator(), phaseName + "_prompt.txt", outputDirectory);
        }
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

    static String executeAssistantWithMcpTraceWithRetry(
            PooledUmlAssistant pa,
            String phaseName,
            String prompt,
            String outputDirectory,
            int maxAttempts) throws IOException {
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
                if (!McpFailurePatterns.isNoToolCallFailure(e) || attempt == maxAttempts) {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("MCP_EXECUTION_FAILED: phase '" + phaseName + "' exceeded retry limit.");
    }

    // ------------------------------------------------------------------ retry strategies

    static String retryOnMissingRequirementTargetUuid(
            PooledUmlAssistant pa,
            String phaseName,
            String basePrompt,
            String outputDirectory,
            String initialResult,
            int maxAttempts) throws IOException {
        String result = initialResult;
        if (maxAttempts <= 1) {
            return result;
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!McpFailurePatterns.isMissingRequirementTargetUuidFailure(result)) {
                return result;
            }
            String retryPrompt;
            if (attempt < maxAttempts) {
                retryPrompt = basePrompt + System.lineSeparator() + System.lineSeparator()
                        + "RECOVERY RETRY #" + attempt + ": un ou plusieurs liens «Satisfait» ont échoué"
                        + " (exigence cible introuvable ou aucune exigence disponible)."
                        + " Consignes impératives de reprise:"
                        + System.lineSeparator()
                        + "1) Les liens «Satisfait» (traçabilité) sont OPTIONNELS — ils ne doivent JAMAIS bloquer la création des éléments structurels."
                        + System.lineSeparator()
                        + "2) Crée toutes les classes, attributs et opérations demandés SANS condition sur les liens de traçabilité."
                        + System.lineSeparator()
                        + "3) Si un UUID d'exigence est introuvable ou indisponible, saute CE lien et continue sans erreur."
                        + System.lineSeparator()
                        + "4) N'arrête JAMAIS la phase à cause d'un lien «Satisfait» manquant: retourne les résultats as-built complets."
                        + System.lineSeparator()
                        + "5) N'invente aucun UUID."
                        + System.lineSeparator()
                        + "⚠️ ANTI-DOUBLONS: certains éléments ont peut-être déjà été créés lors de la tentative précédente."
                        + " Avant de créer chaque classe, vérifiez avec `search_model` (type=class) qu'elle n'existe pas déjà."
                        + " Si elle existe, utilisez son UUID existant — ne la recréez pas.";
            } else {
                retryPrompt = basePrompt + System.lineSeparator() + System.lineSeparator()
                        + "RECOVERY FINAL #" + attempt + ": les liens «Satisfait» ont échoué à plusieurs reprises."
                        + " INTERDICTION ABSOLUE d'appeler `analyst_createRelation` dans cette reprise."
                        + " Consignes impératives:"
                        + System.lineSeparator()
                        + "1) NE PAS appeler `analyst_createRelation` pour quelque raison que ce soit."
                        + System.lineSeparator()
                        + "2) Crée UNIQUEMENT les éléments structurels: classes, attributs, opérations (uml_createElement, uml_addMember)."
                        + System.lineSeparator()
                        + "3) Retourne immédiatement un compte-rendu as-built avec les UUIDs réels des éléments créés."
                        + System.lineSeparator()
                        + "4) Aucune traçabilité, aucun lien, aucune relation dans cette reprise.";
            }
            debug("♻️ Retrying phase '" + phaseName + "' after missing requirement target UUID (attempt " + attempt + "/" + maxAttempts + ")");
            result = executeAssistantWithMcpTrace(pa, phaseName + "_recover_missing_target_" + attempt, retryPrompt, outputDirectory);
        }
        return result;
    }

    static String retryOnMissingModelingRequest(
            PooledUmlAssistant pa,
            String phaseName,
            String basePrompt,
            String outputDirectory,
            String initialResult,
            int maxAttempts) throws IOException {
        String result = initialResult;
        if (maxAttempts <= 1) {
            return result;
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!McpFailurePatterns.isMissingModelingRequestFailure(result)) {
                return result;
            }
            String retryPrompt = basePrompt + System.lineSeparator() + System.lineSeparator()
                    + "RECOVERY RETRY #" + attempt + ": la réponse précédente a déclaré qu'aucune demande de modélisation n'était fournie."
                    + " Voici la demande formelle à exécuter maintenant : créez exactement les éléments listés dans le bloc PlantUML ci-dessus,"
                    + " exécutez les outils MCP correspondants, puis retournez uniquement la sortie as-built."
                    + System.lineSeparator()
                    + "⚠️ ANTI-DOUBLONS : certains éléments de ce lot ont peut-être déjà été partiellement créés lors de la tentative précédente."
                    + " Avant de créer chaque classe, vérifiez d'abord avec `search_model` (type=class) qu'elle n'existe pas déjà."
                    + " Si elle existe, utilisez son UUID existant — ne la recréez pas.";
            debug("♻️ Retrying phase '" + phaseName + "' after missing modeling request failure (attempt " + attempt + "/" + maxAttempts + ")");
            result = executeAssistantWithMcpTrace(pa, phaseName, retryPrompt, outputDirectory);
            result = retryOnMissingRequirementTargetUuid(pa, phaseName, retryPrompt, outputDirectory, result, 2);
        }
        return result;
    }

    static String retryOnMemberUuidNotFound(
            PooledUmlAssistant pa,
            String phaseName,
            String basePrompt,
            String outputDirectory,
            String initialResult,
            int maxAttempts) throws IOException {
        String result = initialResult;
        if (maxAttempts <= 1 || !McpFailurePatterns.isMemberUuidNotFoundFailure(result)) {
            return result;
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!McpFailurePatterns.isMemberUuidNotFoundFailure(result)) {
                return result;
            }
            String retryPrompt = basePrompt + System.lineSeparator() + System.lineSeparator()
                    + "RECOVERY RETRY #" + attempt + ": un `uml_addMember` a échoué car l'UUID de la classe cible n'existe pas dans Modelio."
                    + " Cause probable : une réponse de `uml_createElement` a été ignorée ou mal interprétée."
                    + System.lineSeparator()
                    + "Consignes impératives :"
                    + System.lineSeparator()
                    + "1) Pour chaque classe du lot, recherche-la d'abord via `search_model` (type=class)."
                    + System.lineSeparator()
                    + "2) Si elle existe, utilise son UUID réel pour `uml_addMember`. Si elle n'existe pas, recrée-la avec `uml_createElement`."
                    + System.lineSeparator()
                    + "3) N'utilise JAMAIS un UUID issu de `analyst_queryItems` ou d'exigences pour `uml_addMember`."
                    + System.lineSeparator()
                    + "4) Interdiction absolue d'appeler `analyst_queryItems` dans cette reprise."
                    + System.lineSeparator()
                    + "5) Retourne uniquement le compte-rendu as-built des éléments créés/mis à jour.";
            debug("♻️ Retrying phase '" + phaseName + "' after member UUID not found (attempt " + attempt + "/" + maxAttempts + ")");
            result = executeAssistantWithMcpTrace(pa, phaseName + "_member_retry_" + attempt, retryPrompt, outputDirectory);
        }
        return result;
    }

    /**
     * Reprise pour une erreur auto-détectée d'UUID malformé (segment dupliqué, typo...) où l'agent
     * confirme lui-même qu'aucun outil de création n'a été exécuté — donc aucun effet de bord à
     * réparer. Observé en pratique : un agent retype correctement le même UUID sur 9 appels
     * consécutifs puis le corrompt au 10e ; un simple nouvel essai avec le même prompt suffit dans
     * l'immense majorité des cas, l'erreur étant une pure fluctuation d'aléa, pas un problème
     * structurel du prompt ou des données.
     */
    static String retryOnMalformedUuidNoSideEffect(
            PooledUmlAssistant pa,
            String phaseName,
            String basePrompt,
            String outputDirectory,
            String initialResult,
            int maxAttempts) throws IOException {
        String result = initialResult;
        if (maxAttempts <= 1 || !McpFailurePatterns.isMalformedUuidNoSideEffectFailure(result)) {
            return result;
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!McpFailurePatterns.isMalformedUuidNoSideEffectFailure(result)) {
                return result;
            }
            String retryPrompt = basePrompt + System.lineSeparator() + System.lineSeparator()
                    + "RECOVERY RETRY #" + attempt + ": la tentative précédente a échoué car un UUID a été mal retranscrit"
                    + " (segment dupliqué ou tronqué) dans un appel d'outil — aucune création n'a eu lieu, rien à corriger dans le modèle."
                    + System.lineSeparator()
                    + "Reprends EXACTEMENT la même demande depuis le début. Recopie chaque UUID caractère par caractère depuis le contexte"
                    + " ci-dessus avant de l'utiliser dans un appel d'outil ; ne le retype jamais de mémoire.";
            debug("♻️ Retrying phase '" + phaseName + "' after self-detected malformed UUID with no side effect (attempt " + attempt + "/" + maxAttempts + ")");
            result = executeAssistantWithMcpTrace(pa, phaseName + "_malformed_uuid_retry_" + attempt, retryPrompt, outputDirectory);
        }
        return result;
    }

    static String retryOnProjectOverviewOnly(
            PooledUmlAssistant pa,
            String phaseName,
            String basePrompt,
            String outputDirectory,
            String initialResult,
            int maxAttempts) throws IOException {
        String result = initialResult;
        if (maxAttempts <= 1) {
            return result;
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!McpFailurePatterns.isProjectOverviewOnlyFailure(result)) {
                return result;
            }
            String retryPrompt = basePrompt + System.lineSeparator() + System.lineSeparator()
                    + "RECOVERY RETRY #" + attempt + ": la réponse précédente n'a exécuté que `project_overview`."
                    + " Tu dois maintenant exécuter la demande utilisateur réelle: création des éléments listés dans le bloc PlantUML."
                    + " Commence directement par les outils de création (package/élément/attribut/relation) et ne refais pas d'overview.";
            debug("♻️ Retrying phase '" + phaseName + "' after project_overview-only failure (attempt " + attempt + "/" + maxAttempts + ")");
            result = executeAssistantWithMcpTrace(pa, phaseName, retryPrompt, outputDirectory);
            result = retryOnMissingRequirementTargetUuid(pa, phaseName, retryPrompt, outputDirectory, result, 2);
            result = retryOnMissingModelingRequest(pa, phaseName, retryPrompt, outputDirectory, result, 2);
        }
        return result;
    }

    static String retryOnDuplicateClassesAmbiguous(
            PooledUmlAssistant pa,
            String phaseName,
            String basePrompt,
            String outputDirectory,
            String initialResult,
            int maxAttempts) throws IOException {
        String result = initialResult;
        if (maxAttempts <= 1) {
            return result;
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!McpFailurePatterns.isDuplicateClassesAmbiguousFailure(result)) {
                return result;
            }
            Map<String, List<String>> duplicateUuids = McpFailurePatterns.extractDuplicateClassUuids(result);
            StringBuilder retryPromptBuilder = new StringBuilder(basePrompt)
                    .append(System.lineSeparator()).append(System.lineSeparator())
                    .append("RECOVERY RETRY #").append(attempt)
                    .append(": des classes en double existent déjà dans Modelio (résidu d'une exécution précédente).")
                    .append(" Règles impératives de déduplication à appliquer immédiatement via les outils MCP:")
                    .append(System.lineSeparator());
            if (!duplicateUuids.isEmpty()) {
                retryPromptBuilder.append("Doublons identifiés (UUIDs fournis directement — NE PAS les rechercher par nom):")
                        .append(System.lineSeparator());
                for (Map.Entry<String, List<String>> entry : duplicateUuids.entrySet()) {
                    List<String> uuids = entry.getValue();
                    retryPromptBuilder.append("  - ").append(entry.getKey())
                            .append(": UUID_CONSERVER=").append(uuids.get(0));
                    if (uuids.size() > 1) {
                        retryPromptBuilder.append(", UUID_SUPPRIMER=");
                        for (int ui = 1; ui < uuids.size(); ui++) {
                            if (ui > 1) retryPromptBuilder.append(" ET ");
                            retryPromptBuilder.append(uuids.get(ui));
                        }
                    }
                    retryPromptBuilder.append(System.lineSeparator());
                }
            }
            retryPromptBuilder
                    .append("1) Pour chaque classe ci-dessus, supprime TOUS les UUID_SUPPRIMER via uml_deleteElement.")
                    .append(System.lineSeparator())
                    .append("2) Conserve uniquement UUID_CONSERVER pour chaque classe.")
                    .append(System.lineSeparator())
                    .append("3) Après suppression des doublons, reprends la tâche originale et retourne les résultats as-built complets.")
                    .append(System.lineSeparator())
                    .append("N'attends pas de confirmation: applique ces règles directement avec les outils MCP.");
            String retryPrompt = retryPromptBuilder.toString();
            debug("♻️ Retrying phase '" + phaseName + "' after duplicate classes ambiguity (attempt " + attempt + "/" + maxAttempts + ")");
            result = executeAssistantWithMcpTrace(pa, phaseName + "_dedup_" + attempt, retryPrompt, outputDirectory);
            result = retryOnMissingRequirementTargetUuid(pa, phaseName, retryPrompt, outputDirectory, result, 2);
            result = retryOnMissingModelingRequest(pa, phaseName, retryPrompt, outputDirectory, result, 2);
        }
        return result;
    }

    // ------------------------------------------------------------------ utilities

    private static String formatToolExecutionTrace(PolicyAwareAzureChatModel.ToolExecutionTrace trace) {
        if (trace == null) {
            return "MCP_TRACE phase=unknown status=no-trace";
        }
        return trace.toSummary();
    }

    private static void saveDebugFile(String content, String filename, String outputDirectory) {
        try {
            String debugPath;
            if (outputDirectory != null && !outputDirectory.trim().isEmpty()) {
                debugPath = outputDirectory + "/" + filename;
            } else {
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

    private static void debug(String msg) {
        System.out.println("[LangchainService] " + msg);
    }
}
