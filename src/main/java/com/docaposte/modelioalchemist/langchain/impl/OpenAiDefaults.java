package com.docaposte.modelioalchemist.langchain.impl;

final class OpenAiDefaults {

    static final String DEPLOYMENT = "gpt-5.2";
    static final double TEMPERATURE = 1.0;
    // "2026-03-17" is not a recognized Azure OpenAI API version and caused every request to be
    // rejected with a 404 "Resource not found" by the backend (HttpPolicies.capture() forces this
    // value onto every outgoing request, regardless of deployment). Verified working value below.
    static final String API_VERSION = "2024-06-01";

    private OpenAiDefaults() {
    }
}
