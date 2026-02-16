package com.docaposte.modelioalchemist.langchain.impl;

/**
 * Structure pour représenter une exigence extraite des documents d'analyse
 */
public class Requirement {
    public final String id;
    public final String title;
    public final String description;
    public final String category;
    public final String priority;
    
    public Requirement(String id, String title, String description, String category, String priority) {
        this.id = id != null ? id : "";
        this.title = title != null ? title : "";
        this.description = description != null ? description : "";
        this.category = category != null ? category : "Fonctionnel";
        this.priority = priority != null ? priority : "Moyenne";
    }
    
    public Requirement(String id, String description) {
        this(id, id, description, "Fonctionnel", "Moyenne");
    }
    
    @Override
    public String toString() {
        return String.format("%s: %s - %s", id, title, description);
    }
    
    /**
     * Getters pour accès sécurisé
     */
    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getCategory() { return category; }
    public String getPriority() { return priority; }
    
    /**
     * Méthode utilitaire pour créer une représentation courte
     */
    public String toShortString() {
        return String.format("%s: %s", id, description.length() > 50 ? 
            description.substring(0, 50) + "..." : description);
    }
}