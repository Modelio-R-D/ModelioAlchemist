package com.docaposte.modelioalchemist.langchain.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Utilitaire pour valider l'exhaustivité du traitement des exigences
 * et s'assurer qu'aucune exigence n'est perdue dans le pipeline.
 */
public class RequirementsValidator {
    
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern REQUIREMENT_PATTERN = Pattern.compile("EX-\\d+");
    
    /**
     * Valide que toutes les exigences extraites sont présentes dans la classification
     */
    public static ValidationResult validateClassification(String extractedText, String classifiedJson) {
        try {
            Set<String> extractedReqs = extractRequirementIds(extractedText);
            Set<String> classifiedReqs = extractRequirementIdsFromJson(classifiedJson);
            
            Set<String> missingReqs = new HashSet<>(extractedReqs);
            missingReqs.removeAll(classifiedReqs);
            
            Set<String> extraReqs = new HashSet<>(classifiedReqs);
            extraReqs.removeAll(extractedReqs);

            // Une extraction vide rend missingReqs vide par construction : sans ce garde-fou la
            // validation « réussit » alors qu'aucune comparaison n'a réellement eu lieu.
            boolean comparisonWasPossible = !extractedReqs.isEmpty() || classifiedReqs.isEmpty();

            return new ValidationResult(
                extractedReqs.size(),
                classifiedReqs.size(),
                missingReqs,
                extraReqs,
                missingReqs.isEmpty() && comparisonWasPossible,
                comparisonWasPossible ? null
                    : "Aucun identifiant EX-XXX trouvé dans le texte source alors que "
                      + classifiedReqs.size() + " exigence(s) sont classifiées : comparaison impossible."
            );
            
        } catch (Exception e) {
            return new ValidationResult(0, 0, Set.of(), Set.of(), false, "Erreur de validation: " + e.getMessage());
        }
    }
    
    /**
     * Génère un rapport de validation détaillé
     */
    public static String generateValidationReport(ValidationResult result) {
        StringBuilder report = new StringBuilder();
        
        report.append("=== RAPPORT DE VALIDATION DES EXIGENCES ===\n\n");
        
        report.append("Statistiques :\n");
        report.append("- Exigences extraites : ").append(result.extractedCount).append("\n");
        report.append("- Exigences classifiées : ").append(result.classifiedCount).append("\n");
        report.append("- Validation : ").append(result.isValid ? "✅ SUCCÈS" : "❌ ÉCHEC").append("\n\n");
        
        if (!result.missingRequirements.isEmpty()) {
            report.append("🚨 EXIGENCES MANQUANTES (").append(result.missingRequirements.size()).append(") :\n");
            result.missingRequirements.forEach(req -> 
                report.append("   - ").append(req).append("\n")
            );
            report.append("\n");
        }
        
        if (!result.extraRequirements.isEmpty()) {
            report.append("⚠️ EXIGENCES SUPPLÉMENTAIRES (").append(result.extraRequirements.size()).append(") :\n");
            result.extraRequirements.forEach(req -> 
                report.append("   - ").append(req).append("\n")
            );
            report.append("\n");
        }
        
        if (result.errorMessage != null) {
            report.append("❌ ERREUR : ").append(result.errorMessage).append("\n\n");
        }
        
        if (result.isValid) {
            report.append("✅ Toutes les exigences ont été correctement traitées.\n");
        } else {
            report.append("❌ Des exigences ont été perdues ou mal traitées. Révision nécessaire.\n");
        }
        
        return report.toString();
    }
    
    /**
     * Extrait les identifiants d'exigences (EX-XXX) d'un texte
     */
    private static Set<String> extractRequirementIds(String text) {
        Set<String> requirements = new HashSet<>();
        Matcher matcher = REQUIREMENT_PATTERN.matcher(text);
        while (matcher.find()) {
            requirements.add(matcher.group());
        }
        return requirements;
    }
    
    /**
     * Extrait les identifiants d'exigences d'un JSON classifié
     * FIX: Handles both old format (text strings) and new format (objects with requirement_id field)
     */
    private static Set<String> extractRequirementIdsFromJson(String json) throws Exception {
        Set<String> requirements = new HashSet<>();
        JsonNode root = mapper.readTree(json);
        
        // Parcourir toutes les catégories
        root.fields().forEachRemaining(entry -> {
            JsonNode categoryNode = entry.getValue();
            if (categoryNode.isArray()) {
                categoryNode.forEach(reqNode -> {
                    // New format: objects with requirement_id or original_ref fields
                    if (reqNode.isObject()) {
                        if (reqNode.has("requirement_id")) {
                            String reqId = reqNode.get("requirement_id").asText();
                            Matcher matcher = REQUIREMENT_PATTERN.matcher(reqId);
                            while (matcher.find()) {
                                requirements.add(matcher.group());
                            }
                        }
                        if (reqNode.has("original_ref")) {
                            String origRef = reqNode.get("original_ref").asText();
                            Matcher matcher = REQUIREMENT_PATTERN.matcher(origRef);
                            while (matcher.find()) {
                                requirements.add(matcher.group());
                            }
                        }
                    } else {
                        // Old format: plain text strings
                        String reqText = reqNode.asText();
                        Matcher matcher = REQUIREMENT_PATTERN.matcher(reqText);
                        while (matcher.find()) {
                            requirements.add(matcher.group());
                        }
                    }
                });
            }
        });
        
        return requirements;
    }
    
    /**
     * Résultat de la validation
     */
    public static class ValidationResult {
        public final int extractedCount;
        public final int classifiedCount;
        public final Set<String> missingRequirements;
        public final Set<String> extraRequirements;
        public final boolean isValid;
        public final String errorMessage;
        
        public ValidationResult(int extractedCount, int classifiedCount, 
                              Set<String> missingRequirements, Set<String> extraRequirements, 
                              boolean isValid) {
            this(extractedCount, classifiedCount, missingRequirements, extraRequirements, isValid, null);
        }
        
        public ValidationResult(int extractedCount, int classifiedCount, 
                              Set<String> missingRequirements, Set<String> extraRequirements, 
                              boolean isValid, String errorMessage) {
            this.extractedCount = extractedCount;
            this.classifiedCount = classifiedCount;
            this.missingRequirements = missingRequirements;
            this.extraRequirements = extraRequirements;
            this.isValid = isValid;
            this.errorMessage = errorMessage;
        }
    }
}