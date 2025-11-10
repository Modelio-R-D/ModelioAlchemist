package com.docaposte.modelioalchemist.langchain.impl;

import com.docaposte.modelioalchemist.langchain.impl.PolicyAwareAzureChatModel;
/**
 * Agent responsable de l'analyse du PlantUML et de la génération des éléments UML dans Modelio via MCP.
 * 
 * REFACTORISÉ : Utilise maintenant ModelioAlchemistLangchainService qui suit le pattern de ModelioBot
 * avec infrastructure partagée (client MCP, pool d'assistants) pour éviter les problèmes de transaction.
 */
public class ModelioMcpAgent {
    
    private final String mcpSseUrl;
    private final PolicyAwareAzureChatModel chatModel;
    
    public ModelioMcpAgent(String mcpSseUrl, PolicyAwareAzureChatModel chatModel) {
        this.mcpSseUrl = mcpSseUrl;
        this.chatModel = chatModel;
    }
    
    /**
     * Génère un modèle UML dans Modelio à partir du contenu PlantUML
     * en utilisant le service poolé LangchainService.
     */
    public String generateModelFromPlantUML(String plantUMLContent) {
        return LangchainService.generateModelFromPlantUML(
            plantUMLContent, 
            mcpSseUrl, 
            chatModel
        );
    }
    
    /**
     * Génère un modèle UML dans Modelio à partir du contenu PlantUML et des documents d'analyse
     * pour parser automatiquement les requirements.
     */
    public String generateModelFromPlantUMLWithRequirements(String plantUMLContent, String requirementsDocuments) {
        return LangchainService.generateModelFromPlantUML(
            plantUMLContent,
            requirementsDocuments,
            mcpSseUrl, 
            chatModel
        );
    }
    
    /**
     * Génère un modèle UML dans Modelio à partir du contenu PlantUML et des documents d'analyse
     * avec répertoire de sortie pour les fichiers de debug.
     */
    public String generateModelFromPlantUMLWithRequirements(String plantUMLContent, String requirementsDocuments, String outputDirectory) {
        return LangchainService.generateModelFromPlantUML(
            plantUMLContent,
            requirementsDocuments,
            outputDirectory,
            mcpSseUrl, 
            chatModel
        );
    }
}