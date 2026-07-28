package com.docaposte.modelioalchemist.langchain.impl;

final class OpenAiDefaults {

    static final String DEPLOYMENT = "gpt-5.2";
    static final double TEMPERATURE = 1.0;
    // "2026-03-17" is not a recognized Azure OpenAI API version and caused every request to be
    // rejected with a 404 "Resource not found" by the backend (HttpPolicies.capture() forces this
    // value onto every outgoing request, regardless of deployment). Verified working value below.
    static final String API_VERSION = "2024-06-01";

    /**
     * Maximum time (seconds) to wait for a single Azure OpenAI chat completion response.
     * Large PDFs (3+ MB) can produce prompts with 55k+ token contexts that take well over
     * 5 minutes to complete. Override with the AZURE_CHAT_TIMEOUT_SECONDS environment
     * variable or the azure.chat.timeout.seconds system property.
     * Default: 900 seconds (15 minutes).
     */
    static final int REQUEST_TIMEOUT_SECONDS = resolveTimeoutSeconds();

    private static int resolveTimeoutSeconds() {
        String env = System.getenv("AZURE_CHAT_TIMEOUT_SECONDS");
        if (env != null && !env.isBlank()) {
            try { return Integer.parseInt(env.trim()); } catch (NumberFormatException ignored) {}
        }
        String prop = System.getProperty("azure.chat.timeout.seconds");
        if (prop != null && !prop.isBlank()) {
            try { return Integer.parseInt(prop.trim()); } catch (NumberFormatException ignored) {}
        }
        return 900;
    }

    private OpenAiDefaults() {
    }
}
