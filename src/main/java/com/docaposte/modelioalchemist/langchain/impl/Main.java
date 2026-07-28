package com.docaposte.modelioalchemist.langchain.impl;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -jar modelioalchemiste.jar <path-to-pdf>");
            throw new IllegalArgumentException("PDF path is required");
        }
        String pdfPath = args[0];
        runPipeline(pdfPath, "output", PipelineProgressListener.NONE, StageModelConfig.defaults());
    }

    /** Point d'entrée avec répertoire de sortie personnalisé (utilisé par Modelio). */
    public static void mainWithOutputDir(String[] args, String outputDir) throws Exception {
        mainWithOutputDir(args, outputDir, PipelineProgressListener.NONE, StageModelConfig.defaults());
    }

    /** Point d'entrée avec répertoire de sortie personnalisé et suivi de progression (utilisé par Modelio). */
    public static void mainWithOutputDir(String[] args, String outputDir, PipelineProgressListener progress) throws Exception {
        mainWithOutputDir(args, outputDir, progress, StageModelConfig.defaults());
    }

    /**
     * Main entry point used by the Modelio command handler.
     * Accepts per-stage deployment overrides read from the module parameter panel.
     */
    public static void mainWithOutputDir(String[] args, String outputDir, PipelineProgressListener progress, StageModelConfig stageConfig) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: mainWithOutputDir(args, outputDir) - PDF path required");
        }
        runPipeline(args[0], outputDir, progress, stageConfig);
    }

    /** Point d'entrée spécialisé pour TMA. */
    public static void tmaWithOutputDir(String[] args, String outputDir) throws Exception {
        tmaWithOutputDir(args, outputDir, PipelineProgressListener.NONE);
    }

    /** Point d'entrée spécialisé pour TMA avec suivi de progression. */
    public static void tmaWithOutputDir(String[] args, String outputDir, PipelineProgressListener progress) throws Exception {
        tmaWithOutputDir(args, outputDir, progress, StageModelConfig.defaults());
    }

    /** Point d'entrée spécialisé pour TMA avec suivi de progression et config de déploiements. */
    public static void tmaWithOutputDir(String[] args, String outputDir, PipelineProgressListener progress, StageModelConfig stageConfig) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: tmaWithOutputDir(args, outputDir) - PDF path required");
        }
        String pdfPath = args[0];
        System.out.println("[TMA-Main] 🚀 Starting TMA pipeline for: " + pdfPath);
        System.out.println("[TMA-Main] 📁 Output directory: " + outputDir);
        runPipeline(pdfPath, outputDir, progress, stageConfig);
        System.out.println("[TMA-Main] ✅ TMA pipeline completed");
    }

    /**
     * Core pipeline runner — wires together the LangchainService, ModelioMcpAgent and PipelineRunner.
     */
    private static void runPipeline(String pdfPath, String outputDir, PipelineProgressListener progress, StageModelConfig stageConfig) throws Exception {
        String apiKey = System.getenv("AZURE_OPENAI_AD_TOKEN");
        if (apiKey == null) {
            throw new IllegalStateException(
                "AZURE_OPENAI_AD_TOKEN environment variable is not set. " +
                "Please configure it before running the pipeline.");
        }
        String baseUrl = "https://apigatewayinnovation.azure-api.net/openai-api/deployments/" + OpenAiDefaults.DEPLOYMENT;
        String mcpUrl  = "http://localhost:8083/mcp";

        System.out.println("Using Azure OpenAI configuration:");
        System.out.println("  Base URL: " + baseUrl);
        System.out.println("  Default deployment: " + OpenAiDefaults.DEPLOYMENT);
        System.out.println("  API Version: " + OpenAiDefaults.API_VERSION);
        System.out.println("  MCP URL: " + mcpUrl);
        System.out.println("  Stage config: " + stageConfig.summary());

        LangchainService llm = new LangchainService(apiKey, baseUrl, null, stageConfig, true);

        // The MCP agentic stages use whatever deployment is configured for STAGE_MCP.
        PolicyAwareAzureChatModel mcpChatModel =
                llm.getChatModelForDeployment(stageConfig.deploymentFor(StageModelConfig.STAGE_MCP));

        ModelioMcpAgent mcp = new ModelioMcpAgent(mcpUrl, mcpChatModel);
        new PipelineRunner(llm, mcp).run(pdfPath, outputDir, progress);
    }
}

