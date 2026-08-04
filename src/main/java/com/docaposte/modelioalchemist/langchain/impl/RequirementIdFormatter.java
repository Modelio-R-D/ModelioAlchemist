package com.docaposte.modelioalchemist.langchain.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Requirement id/origin formatting helpers shared by {@code LangchainService} and
 * {@code RequirementCreationService}.
 */
final class RequirementIdFormatter {

    private RequirementIdFormatter() {}

    private static final String CANONICAL_REQUIREMENT_PREFIX = "EXG";
    private static final Pattern REQUIREMENT_ID_PATTERN = Pattern.compile("(?i)(?:REQ|EXG|EX)[-_\\s]?(\\d{1,6})");

    static String formatRequirementId(int numericId) {
        int safeId = Math.max(1, numericId);
        return CANONICAL_REQUIREMENT_PREFIX + "-" + String.format("%03d", safeId);
    }

    static String normalizeRequirementId(String rawId, int fallbackIndex) {
        if (rawId != null) {
            Matcher idMatcher = REQUIREMENT_ID_PATTERN.matcher(rawId.trim());
            if (idMatcher.find()) {
                return formatRequirementId(Integer.parseInt(idMatcher.group(1)));
            }
        }
        return formatRequirementId(fallbackIndex);
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
