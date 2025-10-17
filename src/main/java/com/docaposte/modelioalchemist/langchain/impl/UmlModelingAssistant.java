package com.docaposte.modelioalchemist.langchain.impl;

/**
 * Interface pour l'assistant de modélisation UML qui utilise automatiquement
 * les tools MCP disponibles via LangChain4j AiServices.
 */
public interface UmlModelingAssistant {
    /**
     * Traite une demande de création d'éléments UML à partir d'une description
     * @param request La description de ce qui doit être créé (ex: PlantUML, description textuelle)
     * @return Le rapport de création des éléments
     */
    String createUmlModel(String request);
}