package com.docaposte.modelioalchemist.langchain.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/**
 * Validateur de contextualisation des exigences pour s'assurer que :
 * - Les références originales sont préservées
 * - Le contexte métier est maintenu
 * - Les regroupements thématiques sont cohérents
 * - Les interdépendances sont identifiées
 */
public class RequirementContextValidator {

    private static final ObjectMapper mapper = new ObjectMapper();
    
    public static class ContextValidationResult {
        public boolean isValid = true;
        public List<String> missingContexts = new ArrayList<>();
        public List<String> missingReferences = new ArrayList<>();
        public List<String> inconsistentGroupings = new ArrayList<>();
        public List<String> recommendations = new ArrayList<>();
        public int totalRequirements = 0;
        public int contextualizedRequirements = 0;
        public Map<String, Integer> categoryDistribution = new HashMap<>();
    }
    
    /**
     * Valide la contextualisation des exigences après classification enrichie
     */
    public static ContextValidationResult validateContextualization(
            String originalExtractedText, 
            String classifiedJson) {
        
        ContextValidationResult result = new ContextValidationResult();
        
        try {
            JsonNode classifiedRoot = mapper.readTree(classifiedJson);
            
            // Analyser chaque catégorie
            for (String category : Arrays.asList("technique", "rssi", "fonctionnel", "rse", "ecoconception")) {
                if (classifiedRoot.has(category)) {
                    JsonNode categoryNode = classifiedRoot.get(category);
                    if (categoryNode.isArray()) {
                        int categoryCount = 0;
                        for (JsonNode req : categoryNode) {
                            result.totalRequirements++;
                            categoryCount++;
                            
                            // Vérifier la structure enrichie
                            validateRequirementStructure(req, result, category);
                        }
                        result.categoryDistribution.put(category, categoryCount);
                    } else if (categoryNode.isTextual()) {
                        // Format ancien (fallback) : compter les lignes
                        String[] lines = categoryNode.asText().split("\n");
                        for (String line : lines) {
                            if (!line.trim().isEmpty()) {
                                result.totalRequirements++;
                            }
                        }
                        result.categoryDistribution.put(category, lines.length);
                    }
                }
            }
            
            // Analyser les liens transversaux si présents
            if (classifiedRoot.has("cross_category_links")) {
                validateCrossCategoryLinks(classifiedRoot.get("cross_category_links"), result);
            }
            
            // Calculer le taux de contextualisation
            if (result.totalRequirements > 0) {
                result.contextualizedRequirements = result.totalRequirements - result.missingContexts.size();
            }
            
            // Générer des recommandations
            generateRecommendations(result);
            
        } catch (Exception e) {
            result.isValid = false;
            result.recommendations.add("Erreur lors de l'analyse JSON : " + e.getMessage());
        }
        
        return result;
    }
    
    private static void validateRequirementStructure(JsonNode req, ContextValidationResult result, String category) {
        // Vérifier si c'est le nouveau format enrichi ou l'ancien format
        if (req.isObject()) {
            // Nouveau format enrichi : objet JSON avec métadonnées
            validateEnrichedRequirementStructure(req, result, category);
        } else if (req.isTextual()) {
            // Ancien format : string simple
            validateSimpleRequirementStructure(req, result, category);
        }
    }
    
    private static void validateEnrichedRequirementStructure(JsonNode req, ContextValidationResult result, String category) {
        // Vérifier les champs obligatoires pour la contextualisation enrichie
        if (!req.has("requirement_id") || req.get("requirement_id").asText().isEmpty()) {
            result.missingReferences.add("Exigence sans ID dans catégorie " + category);
            result.isValid = false;
        }
        
        if (!req.has("business_context") || req.get("business_context").asText().isEmpty()) {
            result.missingContexts.add("Exigence " + getReqId(req) + " sans contexte métier");
        }
        
        if (!req.has("description") || req.get("description").asText().isEmpty()) {
            result.missingContexts.add("Exigence " + getReqId(req) + " sans description");
            result.isValid = false;
        }
        
        // Vérifier la présence du domaine technique pour les exigences techniques
        if ("technique".equals(category)) {
            if (!req.has("technical_domain") || req.get("technical_domain").asText().isEmpty()) {
                result.missingContexts.add("Exigence technique " + getReqId(req) + " sans domaine technique spécifié");
            }
        }
    }
    
    private static void validateSimpleRequirementStructure(JsonNode req, ContextValidationResult result, String category) {
        // Validation basique pour l'ancien format
        String reqText = req.asText();
        if (reqText.isEmpty()) {
            result.missingContexts.add("Exigence vide détectée dans catégorie " + category);
            result.isValid = false;
        }
        // Pour l'ancien format, on considère qu'il manque de contextualisation
        result.missingContexts.add("Format ancien détecté - contextualisation limitée");
    }
    
    private static void validateCrossCategoryLinks(JsonNode links, ContextValidationResult result) {
        if (links.isArray()) {
            for (JsonNode link : links) {
                if (!link.has("source") || !link.has("target") || !link.has("relationship")) {
                    result.inconsistentGroupings.add("Lien transversal incomplet détecté");
                }
            }
        }
    }
    
    private static String getReqId(JsonNode req) {
        return req.has("requirement_id") ? req.get("requirement_id").asText() : "UNKNOWN";
    }
    
    private static void generateRecommendations(ContextValidationResult result) {
        if (result.missingContexts.size() > 0) {
            result.recommendations.add("⚠️ " + result.missingContexts.size() + 
                " exigences manquent de contexte métier - enrichir la contextualisation");
        }
        
        if (result.missingReferences.size() > 0) {
            result.recommendations.add("❌ " + result.missingReferences.size() + 
                " exigences sans référence - vérifier l'identification");
        }
        
        // Vérifier l'équilibrage des catégories
        int maxCategorySize = result.categoryDistribution.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int minCategorySize = result.categoryDistribution.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        
        if (maxCategorySize > 0 && minCategorySize == 0) {
            result.recommendations.add("📊 Déséquilibre détecté - certaines catégories sont vides, vérifier la classification");
        }
        
        if (result.contextualizedRequirements > 0) {
            double contextRate = (double) result.contextualizedRequirements / result.totalRequirements * 100;
            if (contextRate < 80) {
                result.recommendations.add("📈 Taux de contextualisation faible (" + 
                    String.format("%.1f", contextRate) + "%) - améliorer l'enrichissement contextuel");
            } else {
                result.recommendations.add("✅ Bon taux de contextualisation (" + 
                    String.format("%.1f", contextRate) + "%)");
            }
        }
    }
    
    /**
     * Génère un rapport détaillé de validation de contextualisation
     */
    public static String generateContextValidationReport(ContextValidationResult result) {
        StringBuilder report = new StringBuilder();
        
        report.append("=== VALIDATION DE CONTEXTUALISATION DES EXIGENCES ===\n\n");
        
        report.append("📊 STATISTIQUES GÉNÉRALES :\n");
        report.append("   - Exigences totales : ").append(result.totalRequirements).append("\n");
        report.append("   - Exigences contextualisées : ").append(result.contextualizedRequirements).append("\n");
        if (result.totalRequirements > 0) {
            double rate = (double) result.contextualizedRequirements / result.totalRequirements * 100;
            report.append("   - Taux de contextualisation : ").append(String.format("%.1f%%", rate)).append("\n");
        }
        report.append("\n");
        
        report.append("📋 RÉPARTITION PAR CATÉGORIE :\n");
        result.categoryDistribution.forEach((category, count) -> 
            report.append("   - ").append(category.toUpperCase()).append(" : ").append(count).append(" exigences\n"));
        report.append("\n");
        
        if (!result.missingContexts.isEmpty()) {
            report.append("⚠️ CONTEXTES MANQUANTS :\n");
            result.missingContexts.forEach(context -> 
                report.append("   - ").append(context).append("\n"));
            report.append("\n");
        }
        
        if (!result.missingReferences.isEmpty()) {
            report.append("❌ RÉFÉRENCES MANQUANTES :\n");
            result.missingReferences.forEach(ref -> 
                report.append("   - ").append(ref).append("\n"));
            report.append("\n");
        }
        
        if (!result.inconsistentGroupings.isEmpty()) {
            report.append("🔗 PROBLÈMES DE LIENS :\n");
            result.inconsistentGroupings.forEach(grouping -> 
                report.append("   - ").append(grouping).append("\n"));
            report.append("\n");
        }
        
        report.append("💡 RECOMMANDATIONS :\n");
        result.recommendations.forEach(rec -> 
            report.append("   ").append(rec).append("\n"));
        
        report.append("\n=== STATUT GLOBAL : ").append(result.isValid ? "✅ VALIDE" : "❌ À CORRIGER").append(" ===");
        
        return report.toString();
    }
}