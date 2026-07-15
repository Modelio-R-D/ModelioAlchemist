package com.docaposte.modelioalchemist.langchain.impl;

import com.docaposte.modelioalchemist.langchain.impl.PolicyAwareAzureChatModel;
import com.docaposte.modelioalchemist.langchain.impl.AzureEndpointResolver;
import com.docaposte.modelioalchemist.langchain.impl.HttpPolicies;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -jar modelioalchemiste.jar <path-to-pdf>");
            System.exit(1);
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
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: mainWithOutputDir(args, outputDir) - PDF path required");
        }
        String pdfPath = args[0];
        
        runPipeline(pdfPath, outputDir);
    }

    /**
     * Point d'entrée spécialisé pour TMA (Tierce Maintenance Applicative) avec répertoire de sortie personnalisé
     */
    public static void tmaWithOutputDir(String[] args, String outputDir) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: tmaWithOutputDir(args, outputDir) - PDF path required");
        }
        String pdfPath = args[0];
        
        System.out.println("[TMA-Main] 🚀 Starting TMA pipeline for: " + pdfPath);
        System.out.println("[TMA-Main] 📁 Output directory: " + outputDir);
        
        runTmaPipeline(pdfPath, outputDir);
        
        System.out.println("[TMA-Main] ✅ TMA pipeline completed");
    }

    /**
     * Pipeline TMA spécialisé avec répertoire de sortie configurable
     */
    private static void runTmaPipeline(String pdfPath, String outputDir) throws Exception {
        // Pour l'instant, utiliser le même pipeline principal 
        // TODO: Plus tard on pourra créer une logique spécialisée TMA si nécessaire
        runPipeline(pdfPath, outputDir);
    }

    /**
     * Pipeline principal avec répertoire de sortie configurable
     */
    private static void runPipeline(String pdfPath, String outputDir) throws Exception {

        // Configure via env or system properties
        // Now uses Azure OpenAI environment variables, same as ModelioBot
        String apiKey = System.getenv("AZURE_OPENAI_AD_TOKEN");
        String baseUrl = System.getenv("AZURE_OPENAI_BASE_URL");
        String deployment = System.getenv("AZURE_OPENAI_DEPLOYMENT");
        String mcpUrl = System.getenv("MODELIO_MCP_URL"); // e.g. http://localhost:8083/mcp

        if (apiKey == null) {
            System.err.println("Please set AZURE_OPENAI_AD_TOKEN environment variable.");
            System.exit(2);
        }
        if (baseUrl == null) {
            baseUrl = "https://apigatewayinnovation.azure-api.net/openai-api/deployments/gpt-4o"; // default
        }
        if (mcpUrl == null) {
            mcpUrl = "http://localhost:8083/mcp"; // default
        }

        System.out.println("Using Azure OpenAI configuration:");
        System.out.println("  Base URL: " + baseUrl);
        System.out.println("  Deployment: " + (deployment != null ? deployment : "default"));
        System.out.println("  MCP URL: " + mcpUrl);

        // Create LangchainService with Azure OpenAI support (compatibility mode for PipelineRunner)
        LangchainService llm = new LangchainService(apiKey, baseUrl, deployment, true); // enable debug
        
        // Create MCP agent (uses pooled architecture)
        ModelioMcpAgent mcp = new ModelioMcpAgent(mcpUrl, llm.getChatModel());
        
        // Create runner with compatibility architecture
        PipelineRunner runner = new PipelineRunner(llm, mcp);

        runner.run(pdfPath, outputDir);
    }
    
    /**
     * Crée le modèle de chat Azure OpenAI suivant le pattern de ModelioBot
     */
    private static PolicyAwareAzureChatModel createChatModel(String apiKey, String baseUrl, String deployment) {
        // Resolve Azure endpoint and deployment
        AzureEndpointResolver.AzureEndpointInfo info = AzureEndpointResolver.resolve(
            baseUrl, deployment != null ? deployment : "", "gpt-4o");
        
        // Build Azure OpenAI client
        com.azure.ai.openai.OpenAIAsyncClient client = buildClient(info, apiKey);
        
        // Create chat model with temperature 0.9 (same as ModelioBot)
        return new PolicyAwareAzureChatModel(client, info, 0.9);
    }
    
    /**
     * Construit le client Azure OpenAI
     */
    private static com.azure.ai.openai.OpenAIAsyncClient buildClient(AzureEndpointResolver.AzureEndpointInfo info, String aadToken) {
        com.azure.ai.openai.OpenAIClientBuilder builder = new com.azure.ai.openai.OpenAIClientBuilder()
            .endpoint(info.endpoint)
            .httpLogOptions(new com.azure.core.http.policy.HttpLogOptions().setLogLevel(com.azure.core.http.policy.HttpLogDetailLevel.BODY_AND_HEADERS))
            .addPolicy(HttpPolicies.auth(aadToken))
            .addPolicy(HttpPolicies.capture());
            
        if (!aadToken.isEmpty()) {
            builder.credential(HttpPolicies.staticTokenCredential(aadToken));
            System.out.println("Using token credential");
        } else {
            System.out.println("No AAD token provided; requests may fail due to missing auth");
        }
        
        return builder.buildAsyncClient();
    }
}
