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
        - RÈGLE ABSOLUE DE TRAÇABILITÉ : seuls les CAS D'USAGE peuvent être reliés aux exigences
          par une dépendance «Satisfait». N'en créez jamais depuis une classe, un acteur, un package
          ou tout autre élément UML.
        - Quand des UUIDs d'exigence valides sont fournis dans la demande, chaque cas d'usage créé
          doit indiquer explicitement quel(s) identifiant(s) d'exigence il satisfait et matérialiser
          ce lien par un appel MCP `analyst_createRelation` :
          relation_type="satisfy", source_uuid=<UUID du cas d'usage>, target_uuid=<UUID de l'exigence satisfaite>,
          module_name="ModelerModule"
          Mentionner le lien dans un texte ou un JSON de sortie ne suffit jamais : la relation «Satisfait»
          doit exister réellement dans le modèle Modelio, créée via cet appel d'outil.
        """)
    String createUmlModel(String request);
}