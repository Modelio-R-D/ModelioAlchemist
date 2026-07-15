package com.docaposte.modelioalchemist.langchain.impl;

import java.time.OffsetDateTime;
import java.util.Locale;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.http.policy.HttpPipelinePolicy;

import reactor.core.publisher.Mono;

/** Factory for custom HTTP pipeline policies (auth + capture). */
class HttpPolicies {

    private static final String HTTP_LOG_LEVEL_ENV = "MODELIO_ALCHEMIST_HTTP_LOG_LEVEL";

    static HttpPipelinePolicy auth(String aadToken) {
        return (context, next) -> {
            if (aadToken != null && !aadToken.isEmpty()) {
                context.getHttpRequest().getHeaders().set(HttpHeaderName.fromString("Ocp-Apim-Subscription-Key"), aadToken);
            }
            context.getHttpRequest().getHeaders().remove(HttpHeaderName.fromString("api-key"));
            return next.process();
        };
    }

    static HttpPipelinePolicy capture() {
        return (context, next) -> {
            try {
                String original = context.getHttpRequest().getUrl().toString();
                String adjusted = original;
                if (original.contains("/openai/deployments/")) adjusted = original.replace("/openai/deployments/", "/openai-api/deployments/");
                if (!adjusted.equals(original)) { try { context.getHttpRequest().setUrl(adjusted); } catch (Throwable ignore) {} }
            } catch (Throwable ignore) {}
            return next.process();
        };
    }

    static TokenCredential staticTokenCredential(String token) {
        return (TokenRequestContext requestContext) -> Mono.just(new AccessToken(token, OffsetDateTime.now().plusMinutes(50)));
    }

    static HttpLogOptions httpLogOptions() {
        String configured = System.getenv(HTTP_LOG_LEVEL_ENV);
        HttpLogDetailLevel level = parseHttpLogLevel(configured);
        return new HttpLogOptions().setLogLevel(level);
    }

    private static HttpLogDetailLevel parseHttpLogLevel(String configuredLevel) {
        if (configuredLevel == null || configuredLevel.isBlank()) {
            return HttpLogDetailLevel.NONE;
        }

        try {
            return HttpLogDetailLevel.valueOf(configuredLevel.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return HttpLogDetailLevel.NONE;
        }
    }
}