package com.docaposte.modelioalchemist.langchain.impl;

import java.time.OffsetDateTime;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.policy.HttpPipelinePolicy;

import reactor.core.publisher.Mono;

/** Factory for custom HTTP pipeline policies (auth + capture). */
class HttpPolicies {

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
}