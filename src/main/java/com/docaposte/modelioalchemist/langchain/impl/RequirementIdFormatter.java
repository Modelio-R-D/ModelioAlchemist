package com.docaposte.modelioalchemist.langchain.impl;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Requirement id/origin formatting helpers shared by {@code LangchainService} and
 * {@code RequirementCreationService}.
 * <p>
 * Ids follow {@code EXG-<CAT>-###}, where {@code <CAT>} is a 3-letter French-based category code
 * and {@code ###} increments per category (so "the 3rd security requirement" reads directly as
 * {@code EXG-RSS-003} instead of a category-blind sequence number). Numbering is always freshly
 * assigned from a per-category counter — any numeric suffix on the requirement's original raw id
 * (assigned earlier in the pipeline, before its category was known) is not reused, since it can't
 * be made to agree with per-category numbering after the fact.
 */
final class RequirementIdFormatter {

    private RequirementIdFormatter() {}

    private static final String CANONICAL_REQUIREMENT_PREFIX = "EXG";
    private static final String FALLBACK_CATEGORY_CODE = "GEN";

    /**
     * Maps a free-text category string (as assigned by the filtering stage's own LLM call, not
     * constrained to a fixed enum) to a 3-letter code. Matching is accent/case-insensitive keyword
     * containment, checked in a fixed order chosen to disambiguate overlapping terms (e.g. a
     * category mentioning both "sécurité" and "RSE" resolves to security, checked first).
     */
    static String categoryCode(String category) {
        if (category == null || category.isBlank()) {
            return FALLBACK_CATEGORY_CODE;
        }
        String normalized = stripAccents(category).toLowerCase();
        // Ordre volontaire : les catégories de la phase de filtrage sont du texte libre (pas un
        // enum fixe) et débordent largement des 5 catégories de la phase de classification — ex.
        // observés en pratique : "Maintenance corrective", "Organisation / Gouvernance",
        // "Contraintes projet", "Support / Assistance", "Architecture / Accès". Les termes les plus
        // spécifiques sont vérifiés en premier pour éviter qu'un mot générique ("architecture") ne
        // capture à tort une catégorie hybride qui a un signal plus précis ("accès" → sécurité).
        if (normalized.contains("secu") || normalized.contains("rssi") || normalized.contains("acces")
                || normalized.contains("habilitation") || normalized.contains("authentif")
                || normalized.contains("confidentialit") || normalized.contains("chiffr")
                || normalized.contains("reglement") || normalized.contains("conformit") || normalized.contains("rgpd")) {
            return "RSS";
        }
        if (normalized.contains("rse") || normalized.contains("societ") || normalized.contains("gouvernance")
                || normalized.contains("ethique") || normalized.contains("responsabilit")
                || normalized.contains("accessibilit") || normalized.contains("inclusion")) {
            return "RSE";
        }
        if (normalized.contains("eco") || normalized.contains("durab") || normalized.contains("environnement")) {
            return "ECO";
        }
        if (normalized.contains("maintenance")) {
            return "MCO"; // Maintien en Condition Opérationnelle — terme standard en IT français.
        }
        if (normalized.contains("support") || normalized.contains("assistance")) {
            return "SUP";
        }
        if (normalized.contains("contrainte") || normalized.contains("contractuel") || normalized.contains("perimetre")) {
            return "CTR";
        }
        if (normalized.contains("organisation") || normalized.contains("transition")) {
            return "ORG";
        }
        if (normalized.contains("fonction") || normalized.contains("metier") || normalized.contains("regle de gestion")
                || normalized.contains("processus") || normalized.contains("ihm") || normalized.contains("ergonomi")
                || normalized.equals("ux") || normalized.contains("/ ux") || normalized.contains("ux /")) {
            return "FON";
        }
        if (normalized.contains("techniq") || normalized.contains("integrat") || normalized.contains("architecture")
                || normalized.contains("infrastructure") || normalized.contains("performance")
                || normalized.contains("volumetrie") || normalized.contains("scalabilit") || normalized.contains("qualite")
                || normalized.contains("logiciel")) {
            return "TEC";
        }
        return FALLBACK_CATEGORY_CODE;
    }

    private static String stripAccents(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "");
    }

    static String formatRequirementId(String category, int perCategorySeq) {
        int safeSeq = Math.max(1, perCategorySeq);
        return CANONICAL_REQUIREMENT_PREFIX + "-" + categoryCode(category) + "-" + String.format("%03d", safeSeq);
    }

    /**
     * Assigns the next id for {@code category}, incrementing that category's counter in
     * {@code perCategoryCounters}. Callers create one fresh, empty map per batch of requirements
     * (e.g. per document) so numbering restarts at 001 for each category within that batch.
     */
    static String nextRequirementId(Map<String, Integer> perCategoryCounters, String category) {
        String code = categoryCode(category);
        int seq = perCategoryCounters.merge(code, 1, Integer::sum);
        return formatRequirementId(category, seq);
    }

    static String buildRequirementOrigin(JsonNode reqNode, String sourceDocumentName, String fallbackDescription) {
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
        if (!sourceQuote.isEmpty()) originParts.add("quote=" + McpAssistantPool.truncate(sourceQuote.replaceAll("\\s+", " "), 240));
        if (originParts.isEmpty()) {
            String fallback = fallbackDescription == null ? "" : fallbackDescription.replaceAll("\\s+", " ").trim();
            if (!fallback.isEmpty()) originParts.add("description=" + McpAssistantPool.truncate(fallback, 120));
        }
        return String.join(" | ", originParts);
    }
}
