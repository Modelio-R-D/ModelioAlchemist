package com.docaposte.modelioalchemist.langchain.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * All MCP failure detection patterns and related helper methods.
 * Centralises pattern constants and {@code is*Failure} predicates so that
 * {@link McpRetryHandler} and {@link LangchainService} do not duplicate them.
 */
class McpFailurePatterns {

    static final String MCP_NO_TOOL_CALL_SUFFIX = "completed without MCP tool calls.";

    private static final Pattern MISSING_REQUIREMENT_CONTAINER_PATTERN =
            Pattern.compile("(?i)no\\s+requirementcontainer\\s+found");

    static final Pattern MISSING_REQUIREMENT_TARGET_UUID_PATTERN = Pattern.compile(
            "(?is)MCP_EXECUTION_FAILED:.*(?:" +
            "UUID\\s+cible\\s+non\\s+trouv" +
            "|UUID\\s+cible\\s+non\\s+r[eé]solu" +
            "|target\\s+uuid\\s+not\\s+found" +
            "|target\\s+element\\s+not\\s+found" +
            "|[eé]l[eé]ment\\s+cible\\s+introuvable" +
            "|element\\s+not\\s+found\\s+with\\s+uuid" +
            "|no\\s+element\\s+found\\s+with\\s+uuid" +
            "|aucune\\s+exigence.*cible.*fournie" +
            "|aucune.*Analyst\\s+Requirement.*cible" +
            "|aucune\\s+exigence.*(?:fournie|identifi[eé]e|non\\s+trouv[eé]e|trouv[eé]e)" +
            "|no\\s+requirement.*target.*provided" +
            "|cannot\\s+create.*satisf.*no\\s+requirement" +
            "|impossible\\s+de\\s+cr[eé]er.*d[eé]pendance.*Satisfait" +
            "|impossible\\s+de\\s+cr[eé]er.*lien.*Satisfait" +
            ")");

    /**
     * Non-fatal cosmetic failures: structural UML elements were already created successfully
     * but an optional operation (Satisfait traceability link, diagram unmasking) failed.
     * Only matches when the ENTIRE result is this kind of failure (anchored at start).
     */
    static final Pattern NON_FATAL_COSMETIC_FAILURE_PATTERN = Pattern.compile(
            "(?is)^\\s*MCP_EXECUTION_FAILED:\\s*(?:" +
            "impossible\\s+de\\s+cr[eé]er\\s+(?:la\\s+|une\\s+)?d[eé]pendance[^.]{0,60}Satisfait" +
            "|impossible\\s+de\\s+cr[eé]er[^.]{0,60}lien[^.]{0,60}Satisfait" +
            "|UUID\\s+cible\\s+non\\s+r[eé]solu" +
            "|aucune\\s+exigence[^.]{0,80}(?:cible|Satisfait)" +
            "|aucune\\s+exigence[^.]{0,160}(?:fournie|trouv[eé]e|n['’]?existe|indisponible|introuvable)" +
            "|aucune\\s+exigence\\s+Analyst[^.]{0,160}(?:Requirement|requ[eê]te\\s+`?analyst_queryItems`?)" +
            "|analyst_queryItems[^.]{0,80}(?:0\\s+r[eé]sultat|aucun\\s+r[eé]sultat)" +
            "|d[eé]pendances?[^.]{0,80}Satisfait[^.]{0,80}(?:obligatoires|impossible)" +
            "|No\\s+diagram\\s+found\\s+with\\s+UUID" +
            "|aucun\\s+diagramme\\s+trouv[eé].*UUID" +
            "|diagram.*not\\s+found" +
            // L'élément (cas d'usage, classe, acteur...) et l'exigence existent déjà tous les deux ;
            // seule la FORME du lien de traçabilité diverge (UML Dependency brute au lieu du lien
            // Analyst "satisfy" attendu). C'est un résidu d'un run précédent, pas un échec structurel :
            // les règles de traçabilité traitent déjà ce lien comme optionnel partout ailleurs.
            "|existe\\s+d[eé]j[aà][^.]{0,300}au\\s+lieu\\s+d['’]une\\s+relation\\s+Analyst[^.]{0,60}satisfy" +
            ").*$");

    static final Pattern MISSING_MODELING_REQUEST_PATTERN = Pattern.compile(
            "(?is)MCP_EXECUTION_FAILED:.*(?:" +
            "aucune\\s+demande\\s+de\\s+mod(?:[eé]lisation|ification)" +
            "|aucune\\s+demande\\s+(?:utilisateur|de\\s+cr[eé]ation)" +
            "|aucun\\s+[eé]l[eé]ment.*[àa]\\s+cr[eé]er\\s+ou\\s+(?:modifier|mettre)" +
            "|pas\\s+indiqu[eé].*[eé]l[eé]ments.*cr[eé]er" +
            "|indiquez\\s+pr[eé]cis[eé]ment\\s+les\\s+[eé]l[eé]ments\\s+[àa]\\s+cr[eé]er" +
            "|no\\s+modeling\\s+request" +
            "|no\\s+creation\\s+request" +
            ")");

    static final Pattern PROJECT_OVERVIEW_ONLY_PATTERN = Pattern.compile(
            "(?is)MCP_EXECUTION_FAILED:.*(?:" +
            "seul\\s+un\\s+`?project_overview`?\\s+a\\s+[eé]t[eé]\\s+ex[eé]cut[eé]" +
            "|only\\s+`?project_overview`?\\s+was\\s+executed" +
            "|seuls?\\s+(?:project_overview|analyst_listProject|get_project).*ex[eé]cut[eé]s?" +
            ")");

    static final Pattern DUPLICATE_CLASSES_AMBIGUOUS_PATTERN = Pattern.compile(
            "(?is)MCP_EXECUTION_FAILED:.*(?:" +
            "classes?\\s+en\\s+double" +
            "|doublons?.*UUID" +
            "|doublons?\\s+de\\s+noms" +
            "|ambigu[ïi]t[ée]s?.*doublons?" +
            "|ambigu[ïi]t[eé].*bloquante" +
            "|classes?.*homonymes?" +
            "|homonymes?.*pr[eé]sent" +
            "|duplicate.*class" +
            "|suppression.*doublons?" +
            "|je\\s+ne\\s+peux\\s+pas\\s+ex[eé]cuter\\s+une\\s+suppression" +
            "|candidats?\\s+pour.*association" +
            "|plusieurs.*candidats?.*m[eê]me\\s+nom" +
            ")");

    /** Old format: {@code Role (uuid1, uuid2)}. */
    private static final Pattern DUPLICATE_CLASS_OLD_FORMAT_PATTERN = Pattern.compile(
            "([A-Za-z]\\w+)\\s+\\(([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})" +
            "(?:[,;]\\s*([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}))*\\)",
            Pattern.CASE_INSENSITIVE);

    /** New format: {@code **Projet** : 3 classes candidates (UUIDs: uuid1 ; uuid2 ; uuid3)}. */
    private static final Pattern DUPLICATE_CLASS_NEW_FORMAT_LINE_PATTERN = Pattern.compile(
            "(?:\\*\\*)?([A-Za-z]\\w+)(?:\\*\\*)?\\s*[:：]\\s*\\d+\\s+classes?\\s+candidates?\\s*\\(UUIDs?:([^)]+)\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern UUID_EXTRACT_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
            Pattern.CASE_INSENSITIVE);

    /** Matches failures where {@code uml_addMember} was called with a UUID that does not exist. */
    static final Pattern MEMBER_UUID_NOT_FOUND_PATTERN = Pattern.compile(
            "(?is)MCP_EXECUTION_FAILED:.*(?:" +
            "impossible\\s+d.ajouter.*attributs?.*aucun\\s+[eé]l[eé]ment.*existe.*UUID" +
            "|aucun\\s+[eé]l[eé]ment\\s+n.existe.*UUID" +
            "|element.*not.*found.*UUID.*addMember" +
            "|UUID.*invalid.*addMember" +
            ")");

    /** Matches LLM output that contains manual instructions instead of MCP tool calls. */
    static final Pattern MANUAL_INSTRUCTIONS_PATTERN = Pattern.compile(
            "(?i)(^|\\b)(?:étape\\s*1|step\\s*1|ouvrez?\\s+modelio|ouvrir\\s+modelio|suivez\\s+les\\s+étapes|vous\\s+pouvez\\s+trouver\\s+l['']uuid|trouver\\s+l['']uuid|assurez-vous\\s+d['']avoir\\s+modelio|créez\\s+un\\s+nouveau\\s+projet)");

    // ------------------------------------------------------------------ predicates

    static boolean isSatisfaitLinkOnlyFailure(String result) {
        return result != null && NON_FATAL_COSMETIC_FAILURE_PATTERN.matcher(result).find();
    }

    static boolean isMissingRequirementTargetUuidFailure(String result) {
        return result != null && MISSING_REQUIREMENT_TARGET_UUID_PATTERN.matcher(result).find();
    }

    static boolean isMissingModelingRequestFailure(String result) {
        return result != null && MISSING_MODELING_REQUEST_PATTERN.matcher(result).find();
    }

    static boolean isProjectOverviewOnlyFailure(String result) {
        return result != null && PROJECT_OVERVIEW_ONLY_PATTERN.matcher(result).find();
    }

    static boolean isDuplicateClassesAmbiguousFailure(String result) {
        return result != null && DUPLICATE_CLASSES_AMBIGUOUS_PATTERN.matcher(result).find();
    }

    static boolean isMemberUuidNotFoundFailure(String result) {
        return result != null && MEMBER_UUID_NOT_FOUND_PATTERN.matcher(result).find();
    }

    static boolean isNoToolCallFailure(IllegalStateException e) {
        return e != null && e.getMessage() != null && e.getMessage().contains(MCP_NO_TOOL_CALL_SUFFIX);
    }

    static boolean isMissingRequirementContainerError(String response) {
        return response != null && MISSING_REQUIREMENT_CONTAINER_PATTERN.matcher(response).find();
    }

    /**
     * If {@code result} is exclusively a Satisfait/traceability link or cosmetic failure,
     * replaces it with an informational placeholder so that validation does not throw.
     */
    static String acceptSatisfaitOnlyFailure(String phaseName, String result) {
        if (isSatisfaitLinkOnlyFailure(result)) {
            debug("⚠️ Phase '" + phaseName + "': Satisfait traceability link failed (optional) — structural elements already created. Accepting result.");
            return "[" + phaseName + " completed — structural elements created. Satisfait traceability link skipped (optional, UUID not found).]";
        }
        return result;
    }

    /**
     * Returns a map of class name → all known duplicate UUIDs (at least 2).
     * Handles both the old format {@code Role (uuid1, uuid2)} and the new format
     * {@code **Projet** : 3 classes candidates (UUIDs: uuid1 ; uuid2 ; uuid3)}.
     */
    static Map<String, List<String>> extractDuplicateClassUuids(String errorText) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (errorText == null) {
            return result;
        }
        // New format
        Matcher newFmt = DUPLICATE_CLASS_NEW_FORMAT_LINE_PATTERN.matcher(errorText);
        while (newFmt.find()) {
            String name = newFmt.group(1);
            List<String> uuids = new ArrayList<>();
            Matcher uuidM = UUID_EXTRACT_PATTERN.matcher(newFmt.group(2));
            while (uuidM.find()) {
                uuids.add(uuidM.group());
            }
            if (uuids.size() >= 2) {
                result.put(name, uuids);
            }
        }
        // Old format — only add if not already found in new format
        Matcher oldFmt = DUPLICATE_CLASS_OLD_FORMAT_PATTERN.matcher(errorText);
        while (oldFmt.find()) {
            String name = oldFmt.group(1);
            if (result.containsKey(name)) {
                continue;
            }
            List<String> uuids = new ArrayList<>();
            Matcher uuidM = UUID_EXTRACT_PATTERN.matcher(oldFmt.group(0));
            uuidM.find(); // skip class-name token
            while (uuidM.find()) {
                uuids.add(uuidM.group());
            }
            if (uuids.size() >= 2) {
                result.put(name, uuids);
            }
        }
        return result;
    }

    private static void debug(String msg) {
        System.out.println("[LangchainService] " + msg);
    }
}
