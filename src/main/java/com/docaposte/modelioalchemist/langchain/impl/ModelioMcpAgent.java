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
    
    /**
     * Crée les exigences dans Modelio à partir des exigences filtrées
     */
    public String createRequirementsInModelio(String filteredRequirementsJson, String outputDirectory) {
        return createRequirementsInModelio(filteredRequirementsJson, outputDirectory, null);
    }

    /**
     * Crée les exigences dans Modelio à partir des exigences filtrées avec nom de document source.
     */
    public String createRequirementsInModelio(String filteredRequirementsJson, String outputDirectory, String sourceDocumentName) {
        return LangchainService.createRequirementsInModelio(
            filteredRequirementsJson,
            outputDirectory,
            sourceDocumentName,
            mcpSseUrl,
            chatModel
        );
    }
    
    /**
     * Crée le modèle de classes UML dans Modelio à partir des exigences analysées
     */
    public String createUmlClassModel(String analysisResults, String outputDirectory) {
        return createUmlClassModel(analysisResults, null, outputDirectory);
    }

    /**
     * Crée le modèle de classes UML dans Modelio à partir des exigences analysées.
     * Si des exigences ont déjà été créées dans Modelio (par exemple via
     * {@link #createRequirementsInModelio(String, String, String)}), passez le rapport
     * correspondant dans {@code existingRequirementsReport} pour éviter de les recréer en double.
     */
    public String createUmlClassModel(String analysisResults, String existingRequirementsReport, String outputDirectory) {
        return LangchainService.createUmlClassModel(
            analysisResults,
            existingRequirementsReport,
            outputDirectory,
            mcpSseUrl,
            chatModel
        );
    }
}