package com.docaposte.modelioalchemist.langchain.impl;

import java.util.List;
import java.util.ArrayList;

/**
 * Utilitaires pour l'analyse et la validation du code PlantUML.
 */
public class PlantUMLAnalyzer {
    
    /**
     * Valide que le code PlantUML est bien formé.
     * 
     * @param plantUmlCode Le code PlantUML à valider
     * @return true si le code semble valide, false sinon
     */
    public static boolean isValidPlantUML(String plantUmlCode) {
        if (plantUmlCode == null || plantUmlCode.trim().isEmpty()) {
            return false;
        }
        
        String trimmed = plantUmlCode.trim();
        
        // Vérifier qu'il commence par @startuml et finit par @enduml
        if (!trimmed.startsWith("@startuml") || !trimmed.endsWith("@enduml")) {
            return false;
        }
        
        // Vérifier qu'il contient au moins une classe
        return trimmed.contains("class ");
    }
    
    /**
     * Extrait un résumé textuel du contenu PlantUML.
     * 
     * @param plantUmlCode Le code PlantUML à analyser
     * @return Un résumé des éléments trouvés
     */
    public static String analyzePlantUMLContent(String plantUmlCode) {
        if (!isValidPlantUML(plantUmlCode)) {
            return "Code PlantUML invalide ou vide";
        }
        
        StringBuilder summary = new StringBuilder();
        summary.append("=== Analyse du contenu PlantUML ===\n");
        
        // Compter les classes
        long classCount = plantUmlCode.lines()
            .filter(line -> line.trim().startsWith("class "))
            .count();
        summary.append("Nombre de classes détectées : ").append(classCount).append("\n");
        
        // Compter les relations
        long relationCount = plantUmlCode.lines()
            .filter(line -> line.contains("--") || line.contains("-->") || 
                           line.contains("|>") || line.contains("*--") || line.contains("o--"))
            .count();
        summary.append("Nombre de relations détectées : ").append(relationCount).append("\n");
        
        // Lister les noms de classes
        List<String> classNames = new ArrayList<>();
        plantUmlCode.lines()
            .filter(line -> line.trim().startsWith("class "))
            .forEach(line -> {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    String className = parts[1].replaceAll("[{].*", ""); // Enlever {
                    classNames.add(className);
                }
            });
        
        if (!classNames.isEmpty()) {
            summary.append("Classes trouvées : ").append(String.join(", ", classNames)).append("\n");
        }
        
        return summary.toString();
    }
    
    /**
     * Nettoie et normalise le code PlantUML.
     * 
     * @param plantUmlCode Le code PlantUML brut
     * @return Le code PlantUML nettoyé
     */
    public static String cleanPlantUMLCode(String plantUmlCode) {
        if (plantUmlCode == null) {
            return "";
        }
        
        // Enlever les balises markdown si présentes
        String cleaned = plantUmlCode.replaceAll("```plantuml\\s*", "").replaceAll("```\\s*$", "");
        
        // Enlever les espaces en début et fin
        cleaned = cleaned.trim();
        
        // Ajouter @startuml et @enduml si manquants
        if (!cleaned.startsWith("@startuml")) {
            cleaned = "@startuml\n" + cleaned;
        }
        if (!cleaned.endsWith("@enduml")) {
            cleaned = cleaned + "\n@enduml";
        }
        
        return cleaned;
    }
}