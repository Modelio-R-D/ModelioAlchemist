package com.docaposte.modelioalchemist.langchain.impl;

/** Resolves Azure OpenAI endpoint & deployment from a raw base URL and optional explicit deployment. */
class AzureEndpointResolver {

    static class AzureEndpointInfo {
        final String endpoint;
        final String deployment;
        AzureEndpointInfo(String endpoint, String deployment) { this.endpoint = endpoint; this.deployment = deployment; }
    }

    static AzureEndpointInfo resolve(String rawBaseUrl, String explicitDeployment, String defaultDeployment) {
        String endpoint; String deployment;
        final String marker = "/deployments/";
        if (rawBaseUrl.contains(marker) && (explicitDeployment == null || explicitDeployment.isEmpty())) {
            int idx = rawBaseUrl.indexOf(marker);
            endpoint = rawBaseUrl.substring(0, idx);
            String rest = rawBaseUrl.substring(idx + marker.length());
            int slash = rest.indexOf('/');
            deployment = (slash > -1) ? rest.substring(0, slash) : rest;
        } else {
            endpoint = trimTrailingSlash(rawBaseUrl);
            deployment = (explicitDeployment == null || explicitDeployment.isEmpty()) ? defaultDeployment : explicitDeployment;
        }
        if (deployment == null || deployment.trim().isEmpty()) deployment = defaultDeployment;
        // Normalize
        
        if (endpoint.endsWith("/openai-api")) endpoint = endpoint.substring(0, endpoint.length() - "/openai-api".length());
        endpoint = trimTrailingSlash(endpoint);
        return new AzureEndpointInfo(endpoint, deployment);
    }

    private static String trimTrailingSlash(String s) { return (s != null && s.endsWith("/")) ? s.substring(0, s.length()-1) : s; }
}