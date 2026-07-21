package com.docaposte.modelioalchemist.langchain.impl;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -jar modelioalchemiste.jar <path-to-pdf>");
            throw new IllegalArgumentException("PDF path is required");
        }
        String pdfPath = args[0];
        
        // Utiliser le répertoire de sortie par défaut
        String outputDir = "output";
        
        runPipeline(pdfPath, outputDir);
    }

    /**
     * Point d'entrée avec répertoire de sortie personnalisé (utilisé par Modelio)
     */
    public static void mainWithOutputDir(String[] args, String outputDir) throws Exception {
        mainWithOutputDir(args, outputDir, PipelineProgressListener.NONE);
    }

    /**
     * Point d'entrée avec répertoire de sortie personnalisé et suivi de progression (utilisé par Modelio)
     */
    public static void mainWithOutputDir(String[] args, String outputDir, PipelineProgressListener progress) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: mainWithOutputDir(args, outputDir) - PDF path required");
        }
        String pdfPath = args[0];
        
        runPipeline(pdfPath, outputDir, progress);
    }

    /**
     * Point d'entrée spécialisé pour TMA (Tierce Maintenance Applicative) avec répertoire de sortie personnalisé
     */
    public static void tmaWithOutputDir(String[] args, String outputDir) throws Exception {
        tmaWithOutputDir(args, outputDir, PipelineProgressListener.NONE);
    }

    /**
     * Point d'entrée spécialisé pour TMA avec répertoire de sortie personnalisé et suivi de progression
     */
    public static void tmaWithOutputDir(String[] args, String outputDir, PipelineProgressListener progress) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: tmaWithOutputDir(args, outputDir) - PDF path required");
        }
        String pdfPath = args[0];
        
        System.out.println("[TMA-Main] 🚀 Starting TMA pipeline for: " + pdfPath);
        System.out.println("[TMA-Main] 📁 Output directory: " + outputDir);
        
        runTmaPipeline(pdfPath, outputDir, progress);
        
        System.out.println("[TMA-Main] ✅ TMA pipeline completed");
    }

    /**
     * Pipeline TMA spécialisé avec répertoire de sortie configurable
     */
    private static void runTmaPipeline(String pdfPath, String outputDir, PipelineProgressListener progress) throws Exception {
        // Pour l'instant, utiliser le même pipeline principal 
        // TODO: Plus tard on pourra créer une logique spécialisée TMA si nécessaire
        runPipeline(pdfPath, outputDir, progress);
    }

    /**
     * Pipeline principal avec répertoire de sortie configurable
     */
    private static void runPipeline(String pdfPath, String outputDir) throws Exception {
        runPipeline(pdfPath, outputDir, PipelineProgressListener.NONE);
    }

    /**
     * Pipeline principal avec répertoire de sortie configurable et suivi de progression
     */
    private static void runPipeline(String pdfPath, String outputDir, PipelineProgressListener progress) throws Exception {

        // Configure via env or system properties
        // Now uses Azure OpenAI environment variables, same as ModelioBot
        String apiKey = System.getenv("AZURE_OPENAI_AD_TOKEN");
        String baseUrl = null;// = System.getenv("AZURE_OPENAI_BASE_URL");
        String deployment = null;// = System.getenv("AZURE_OPENAI_DEPLOYMENT");
        String mcpUrl = null;// = System.getenv("MODELIO_MCP_URL"); // e.g. http://localhost:8083/mcp

        if (apiKey == null) {
            throw new IllegalStateException(
                "AZURE_OPENAI_AD_TOKEN environment variable is not set. " +
                "Please configure it before running the pipeline.");
        }
        if (baseUrl == null) {
            baseUrl = "https://apigatewayinnovation.azure-api.net/openai-api/deployments/" + OpenAiDefaults.DEPLOYMENT; // default
        }
        if (mcpUrl == null) {
            mcpUrl = "http://localhost:8083/mcp"; // default
        }

        System.out.println("Using Azure OpenAI configuration:");
        System.out.println("  Base URL: " + baseUrl);
        System.out.println("  Deployment: " + (deployment != null ? deployment : OpenAiDefaults.DEPLOYMENT + " (default)"));
        System.out.println("  API Version: " + OpenAiDefaults.API_VERSION);
        System.out.println("  MCP URL: " + mcpUrl);

        // Create LangchainService with Azure OpenAI support (compatibility mode for PipelineRunner)
        LangchainService llm = new LangchainService(apiKey, baseUrl, deployment, true); // enable debug
        
        // Create MCP agent (uses pooled architecture)
        ModelioMcpAgent mcp = new ModelioMcpAgent(mcpUrl, llm.getChatModel());
        
        // Create runner with compatibility architecture
        PipelineRunner runner = new PipelineRunner(llm, mcp);

        runner.run(pdfPath, outputDir, progress);
    }
    
    /**
     * Crée le modèle de chat Azure OpenAI suivant le pattern de ModelioBot
     */
    private static PolicyAwareAzureChatModel createChatModel(String apiKey, String baseUrl, String deployment) {
        // Resolve Azure endpoint and deployment
        AzureEndpointResolver.AzureEndpointInfo info = AzureEndpointResolver.resolve(
            baseUrl, deployment != null ? deployment : "", OpenAiDefaults.DEPLOYMENT);
        
        // Build Azure OpenAI client
        com.azure.ai.openai.OpenAIAsyncClient client = buildClient(info, apiKey);
        
        // Create chat model with project defaults
        return new PolicyAwareAzureChatModel(client, info, OpenAiDefaults.TEMPERATURE);
    }
    
    /**
     * Construit le client Azure OpenAI
     */
    private static com.azure.ai.openai.OpenAIAsyncClient buildClient(AzureEndpointResolver.AzureEndpointInfo info, String aadToken) {
        // See matching note in LangchainService.buildClient(): we deliberately skip builder.credential(...)
        // to avoid an extra Authorization header (built from a non-JWT APIM key) that some API
        // Management operations reject with a 404. Auth is handled by HttpPolicies.auth() below.
        com.azure.ai.openai.OpenAIClientBuilder builder = new com.azure.ai.openai.OpenAIClientBuilder()
            .endpoint(info.endpoint)
            .httpLogOptions(HttpPolicies.httpLogOptions())
            .addPolicy(HttpPolicies.auth(aadToken))
            .addPolicy(HttpPolicies.capture());

        if (aadToken.isEmpty()) {
            System.out.println("No AAD token provided; requests may fail due to missing auth");
        }

        return builder.buildAsyncClient();
    }
}
