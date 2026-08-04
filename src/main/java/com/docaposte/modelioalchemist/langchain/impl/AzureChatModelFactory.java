package com.docaposte.modelioalchemist.langchain.impl;

import java.time.Duration;

/**
 * Builds Azure OpenAI async clients for instance-level (per-deployment) chat model construction.
 * Stateless and independent of the shared/static MCP orchestration state in
 * {@link McpAssistantPool}. Extracted from {@code LangchainService}.
 */
final class AzureChatModelFactory {

    private AzureChatModelFactory() {}

    /**
     * Construit le client Azure OpenAI (méthode utilitaire)
     */
    static com.azure.ai.openai.OpenAIAsyncClient buildClient(AzureEndpointResolver.AzureEndpointInfo info, String aadToken) {
        // Note: we intentionally do NOT call builder.credential(...) here. Doing so makes the Azure
        // SDK attach an additional "Authorization: Bearer <aadToken>" header via its
        // BearerTokenAuthenticationPolicy, using our raw APIM subscription key as if it were a real
        // AAD JWT. Some API Management operations (e.g. newer deployments behind stricter policies)
        // reject that malformed Authorization header with a 404, even though the same
        // Ocp-Apim-Subscription-Key-only request succeeds (this is exactly what ModelioAI does).
        // Auth is handled entirely by HttpPolicies.auth() below, which sets Ocp-Apim-Subscription-Key.
        // The Azure SDK's default Netty HttpClient has a 60s response timeout, which is too
        // short for large prompts (140K+ chars) against slower deployments/proxies. Raise it
        // to match the request-level timeouts used in PolicyAwareAzureChatModel.
        com.azure.core.http.HttpClient httpClient = new com.azure.core.http.netty.NettyAsyncHttpClientBuilder()
                .responseTimeout(Duration.ofSeconds(OpenAiDefaults.REQUEST_TIMEOUT_SECONDS))
                .build();

        com.azure.ai.openai.OpenAIClientBuilder builder = new com.azure.ai.openai.OpenAIClientBuilder()
            .endpoint(info.endpoint)
            .httpClient(httpClient)
            .httpLogOptions(HttpPolicies.httpLogOptions())
            .addPolicy(HttpPolicies.auth(aadToken))
            .addPolicy(HttpPolicies.capture());

        if (aadToken.isEmpty()) {
            McpAssistantPool.debug("No AAD token provided; requests may fail due to missing auth");
        }

        return builder.buildAsyncClient();
    }
}
