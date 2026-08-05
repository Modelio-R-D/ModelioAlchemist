package com.docaposte.modelioalchemist.langchain.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

/**
 * Tracks and manages comprehensive pipeline execution metrics.
 * Collects timing data, filtering statistics, validation results, and MCP operation counts.
 */
public class PipelineMetrics {
    
    private String sourceDocument;
    private final Map<String, Long> timings = new LinkedHashMap<>();
    private FilteringMetrics filteringMetrics;
    private ClassificationValidationMetrics classificationValidationMetrics;
    private ContextValidationMetrics contextValidationMetrics;
    private SourceLocationMetrics sourceLocationMetrics;
    private McpRequirementsMetrics mcpRequirementsMetrics;
    private McpSatisfyLinksMetrics mcpSatisfyLinksMetrics;
    private ClassModelElementsMetrics classModelElementsMetrics;
    
    // Timing markers
    private long pipelineStartTime;
    
    public void setPipelineStartTime(long startTime) {
        this.pipelineStartTime = startTime;
    }
    
    public void recordStageTiming(String stageName, long startTime) {
        long duration = System.currentTimeMillis() - startTime;
        timings.put(stageName, duration);
    }

    /**
     * Enregistre une durée déjà mesurée. Utile pour les étapes exécutées en parallèle, dont la durée
     * propre doit être chronométrée dans leur propre thread puis reportée ici depuis le thread principal
     * ({@code timings} n'est pas thread-safe).
     */
    public void recordStageDuration(String stageName, long durationMs) {
        timings.put(stageName, durationMs);
    }

    public Map<String, Long> getTimings() {
        return Collections.unmodifiableMap(timings);
    }

    public String buildTimingSummary() {
        return buildTimingSummary(System.currentTimeMillis());
    }

    public String buildTimingSummary(long now) {
        StringBuilder summary = new StringBuilder();
        summary.append("📊 Pipeline timing summary\n");
        for (Map.Entry<String, Long> entry : timings.entrySet()) {
            summary.append("   - ")
                .append(formatStageName(entry.getKey()))
                .append(": ")
                .append(formatDuration(entry.getValue()))
                .append(" (")
                .append(entry.getValue())
                .append(" ms)\n");
        }
        if (pipelineStartTime > 0) {
            long totalTime = now - pipelineStartTime;
            summary.append("   - total: ")
                .append(formatDuration(totalTime))
                .append(" (")
                .append(totalTime)
                .append(" ms)\n");
        }
        return summary.toString().trim();
    }

    private String formatStageName(String stageName) {
        return stageName.replace('_', ' ');
    }

    private String formatDuration(long durationMs) {
        if (durationMs < 1000) {
            return durationMs + " ms";
        }
        long seconds = durationMs / 1000;
        long millis = durationMs % 1000;
        if (millis == 0) {
            return seconds + " s";
        }
        return String.format(Locale.ROOT, "%d.%03d s", seconds, millis);
    }
    
    public void setSourceDocument(String sourceDocument) {
        this.sourceDocument = sourceDocument;
    }
    
    public void setFilteringMetrics(int total, int retained, int rejected) {
        this.filteringMetrics = new FilteringMetrics(total, retained, rejected);
    }
    
    public void setClassificationValidationMetrics(int extractedCount, int classifiedCount,
                                                    int missingCount, int extraCount, boolean isValid) {
        this.classificationValidationMetrics = new ClassificationValidationMetrics(
            extractedCount, classifiedCount, missingCount, extraCount, isValid);
    }

    /**
     * Détecte l'incohérence : classification_validation rapporte 0 exigence classifiée alors que
     * des exigences ont bien été créées avec succès dans Modelio ailleurs dans le run. Sans ce
     * garde-fou, is_valid restait "true" par défaut (rien à comparer) et masquait silencieusement
     * un problème de parsing en amont. Appelé après que mcp_requirements soit connu (étape
     * ultérieure du pipeline), donc a posteriori sur les métriques déjà enregistrées.
     */
    public void reconcileClassificationValidation(int mcpRequirementsCreatedSuccessfully) {
        if (classificationValidationMetrics == null) {
            return;
        }
        if (classificationValidationMetrics.classifiedCount == 0 && mcpRequirementsCreatedSuccessfully > 0) {
            classificationValidationMetrics.isValid = false;
            classificationValidationMetrics.coherenceWarning =
                "classified_count=0 mais " + mcpRequirementsCreatedSuccessfully
                + " exigence(s) créées avec succès dans mcp_requirements : extraction JSON probablement en échec, pas une validation réussie.";
        }
    }
    
    public void setContextValidationMetrics(int totalRequirements, int contextualizedRequirements,
                                           Map<String, Integer> categoryDistribution, int missingReferencesCount) {
        this.contextValidationMetrics = new ContextValidationMetrics(
            totalRequirements, contextualizedRequirements, categoryDistribution, missingReferencesCount);
    }
    
    public void setSourceLocationMetrics(int fromLlm, int fromDeterministicFallback, int stillMissing) {
        this.sourceLocationMetrics = new SourceLocationMetrics(fromLlm, fromDeterministicFallback, stillMissing);
    }
    
    public void setMcpRequirementsMetrics(int attempted, int createdSuccessfully, int failed) {
        this.mcpRequirementsMetrics = new McpRequirementsMetrics(attempted, createdSuccessfully, failed);
    }
    
    public void setMcpSatisfyLinksMetrics(int umlElementsCreated, int satisfyRelationsAttempted, int satisfyRelationsConfirmed) {
        this.mcpSatisfyLinksMetrics = new McpSatisfyLinksMetrics(umlElementsCreated, satisfyRelationsAttempted, satisfyRelationsConfirmed);
    }

    public void setClassModelElementsMetrics(int classesCreated, int attributesCreated, int associationsCreated, int packagesCreated) {
        this.classModelElementsMetrics = new ClassModelElementsMetrics(classesCreated, attributesCreated, associationsCreated, packagesCreated);
    }
    
    public ObjectNode toJson() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        
        root.put("source_document", sourceDocument);
        root.put("build_timestamp", BuildInfo.buildTimestamp());
        root.put("build_location", BuildInfo.sourceLocation());
        
        // Timings
        ObjectNode timingsNode = mapper.createObjectNode();
        for (Map.Entry<String, Long> entry : timings.entrySet()) {
            timingsNode.put(entry.getKey(), entry.getValue());
        }
        if (pipelineStartTime > 0) {
            long totalTime = System.currentTimeMillis() - pipelineStartTime;
            timingsNode.put("total", totalTime);
        }
        root.set("timings_ms", timingsNode);
        
        // Filtering
        if (filteringMetrics != null) {
            root.set("filtering", filteringMetrics.toJson());
        }
        
        // Classification Validation
        if (classificationValidationMetrics != null) {
            root.set("classification_validation", classificationValidationMetrics.toJson());
        }
        
        // Context Validation
        if (contextValidationMetrics != null) {
            root.set("context_validation", contextValidationMetrics.toJson());
        }
        
        // Source Location
        if (sourceLocationMetrics != null) {
            root.set("source_location", sourceLocationMetrics.toJson());
        }
        
        // MCP Requirements
        if (mcpRequirementsMetrics != null) {
            root.set("mcp_requirements", mcpRequirementsMetrics.toJson());
        }
        
        // MCP Satisfy Links
        if (mcpSatisfyLinksMetrics != null) {
            root.set("mcp_satisfy_links", mcpSatisfyLinksMetrics.toJson());
        }

        // Class Model Elements
        if (classModelElementsMetrics != null) {
            root.set("class_model_elements", classModelElementsMetrics.toJson());
        }

        return root;
    }
    
    // Inner classes for different metric categories
    
    static class FilteringMetrics {
        int total;
        int retained;
        int rejected;
        
        FilteringMetrics(int total, int retained, int rejected) {
            this.total = total;
            this.retained = retained;
            this.rejected = rejected;
        }
        
        ObjectNode toJson() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("total_items_analyzed", total);
            node.put("requirements_retained", retained);
            node.put("items_rejected", rejected);
            // Le LLM ne renseigne pas toujours total_items_analyzed correctement (observé à 0 sur
            // certains runs réels alors que retained/rejected sont cohérents) : on retombe sur
            // retained+rejected pour ne jamais laisser le taux absent, et on le borne à [0,100] pour
            // ne jamais publier une valeur aberrante (>100%) en cas d'incohérence amont.
            int effectiveTotal = total > 0 ? total : (retained + rejected);
            if (effectiveTotal > 0) {
                double rate = (retained * 100.0) / effectiveTotal;
                node.put("retention_rate_pct", Math.max(0.0, Math.min(100.0, rate)));
            }
            return node;
        }
    }
    
    static class ClassificationValidationMetrics {
        int extractedCount;
        int classifiedCount;
        int missingCount;
        int extraCount;
        boolean isValid;
        String coherenceWarning;

        ClassificationValidationMetrics(int extractedCount, int classifiedCount,
                                        int missingCount, int extraCount, boolean isValid) {
            this.extractedCount = extractedCount;
            this.classifiedCount = classifiedCount;
            this.missingCount = missingCount;
            this.extraCount = extraCount;
            this.isValid = isValid;
        }
        
        ObjectNode toJson() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("extracted_count", extractedCount);
            node.put("classified_count", classifiedCount);
            node.put("missing_requirements", missingCount);
            node.put("extra_requirements", extraCount);
            node.put("is_valid", isValid);
            if (coherenceWarning != null) {
                node.put("coherence_warning", coherenceWarning);
            }
            return node;
        }
    }

    static class ContextValidationMetrics {
        int totalRequirements;
        int contextualizedRequirements;
        Map<String, Integer> categoryDistribution;
        int missingReferencesCount;
        
        ContextValidationMetrics(int totalRequirements, int contextualizedRequirements,
                               Map<String, Integer> categoryDistribution, int missingReferencesCount) {
            this.totalRequirements = totalRequirements;
            this.contextualizedRequirements = contextualizedRequirements;
            this.categoryDistribution = categoryDistribution;
            this.missingReferencesCount = missingReferencesCount;
        }
        
        ObjectNode toJson() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("total_requirements", totalRequirements);
            node.put("contextualized_requirements", contextualizedRequirements);
            if (totalRequirements > 0) {
                node.put("contextRate_pct", (contextualizedRequirements * 100.0) / totalRequirements);
            }
            ObjectNode catDist = mapper.createObjectNode();
            if (categoryDistribution != null) {
                for (Map.Entry<String, Integer> entry : categoryDistribution.entrySet()) {
                    catDist.put(entry.getKey(), entry.getValue());
                }
            }
            node.set("category_distribution", catDist);
            node.put("missing_references", missingReferencesCount);
            return node;
        }
    }
    
    static class SourceLocationMetrics {
        int fromLlm;
        int fromDeterministicFallback;
        int stillMissing;
        
        SourceLocationMetrics(int fromLlm, int fromDeterministicFallback, int stillMissing) {
            this.fromLlm = fromLlm;
            this.fromDeterministicFallback = fromDeterministicFallback;
            this.stillMissing = stillMissing;
        }
        
        ObjectNode toJson() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("from_llm", fromLlm);
            node.put("from_deterministic_fallback", fromDeterministicFallback);
            node.put("still_missing", stillMissing);
            return node;
        }
    }
    
    static class McpRequirementsMetrics {
        int attempted;
        int createdSuccessfully;
        int failed;
        
        McpRequirementsMetrics(int attempted, int createdSuccessfully, int failed) {
            this.attempted = attempted;
            this.createdSuccessfully = createdSuccessfully;
            this.failed = failed;
        }
        
        ObjectNode toJson() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("attempted", attempted);
            node.put("created_successfully", createdSuccessfully);
            node.put("failed", failed);
            return node;
        }
    }
    
    static class ClassModelElementsMetrics {
        int classesCreated;
        int attributesCreated;
        int associationsCreated;
        int packagesCreated;

        ClassModelElementsMetrics(int classesCreated, int attributesCreated, int associationsCreated, int packagesCreated) {
            this.classesCreated = classesCreated;
            this.attributesCreated = attributesCreated;
            this.associationsCreated = associationsCreated;
            this.packagesCreated = packagesCreated;
        }

        ObjectNode toJson() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("classes_created", classesCreated);
            node.put("attributes_created", attributesCreated);
            node.put("associations_created", associationsCreated);
            node.put("packages_created", packagesCreated);
            return node;
        }
    }

    static class McpSatisfyLinksMetrics {
        int umlElementsCreated;
        int satisfyRelationsAttempted;
        int satisfyRelationsConfirmed;
        
        McpSatisfyLinksMetrics(int umlElementsCreated, int satisfyRelationsAttempted, int satisfyRelationsConfirmed) {
            this.umlElementsCreated = umlElementsCreated;
            this.satisfyRelationsAttempted = satisfyRelationsAttempted;
            this.satisfyRelationsConfirmed = satisfyRelationsConfirmed;
        }
        
        ObjectNode toJson() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode node = mapper.createObjectNode();
            node.put("uml_elements_created", umlElementsCreated);
            node.put("satisfy_relations_attempted", satisfyRelationsAttempted);
            node.put("satisfy_relations_confirmed", satisfyRelationsConfirmed);
            return node;
        }
    }
}
