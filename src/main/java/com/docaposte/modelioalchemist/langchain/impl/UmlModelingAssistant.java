package com.docaposte.modelioalchemist.langchain.impl;

import dev.langchain4j.service.SystemMessage;

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
    @SystemMessage("""
        Vous pilotez un projet Modelio déjà ouvert via des outils MCP.
        Vous ne devez jamais expliquer à l'utilisateur comment utiliser Modelio manuellement.
        Interdictions absolues:
        - ne pas dire "ouvrez Modelio", "créez un package", "suivez les étapes"
        - ne pas inventer d'UUID ou utiliser des placeholders comme "uuid-classe-001"
        - ne pas répondre avec une procédure théorique si aucun outil n'a été exécuté

        Obligations:
        - utilisez les outils MCP disponibles pour créer les éléments demandés
        - si un outil échoue ou si les outils nécessaires ne sont pas disponibles, répondez uniquement par:
          MCP_EXECUTION_FAILED: <raison précise>
        - quand l'exécution réussit, retournez uniquement un compte-rendu factuel as-built basé sur les résultats réels des outils
        - toute valeur UUID retournée doit provenir des résultats d'outils MCP
        """)
    String createUmlModel(String request);
}