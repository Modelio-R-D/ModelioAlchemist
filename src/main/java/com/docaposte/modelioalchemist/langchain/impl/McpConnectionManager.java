package com.docaposte.modelioalchemist.langchain.impl;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;

/**
 * Manages a singleton MCP connection with basic retry/backoff and health checks.
 * Responsibilities:
 *  - Lazy initialize transport + client
 *  - Provide withClient(Function) wrapper that retries transient failures once
 *  - Basic exponential backoff (single step) on failure
 *  - Health check method to verify liveness (listTools)
 */
public class McpConnectionManager {

    public interface Logger { void debug(String msg); }

    private final String sseUrl;
    private final Duration timeout;
    private final Logger logger;

    private volatile HttpMcpTransport transport;
    private volatile DefaultMcpClient client;

    private final Object initLock = new Object();
    private final AtomicInteger reconnects = new AtomicInteger();

    public McpConnectionManager(String sseUrl, Duration timeout, Logger logger) {
        this.sseUrl = sseUrl;
        this.timeout = timeout;
        this.logger = logger;
    }

    private void ensureInit() {
        if (client != null) return;
        synchronized (initLock) {
            if (client != null) return;
            build();
        }
    }

    private void build() {
        try {
            transport = new HttpMcpTransport.Builder()
                .sseUrl(sseUrl)
                .timeout(timeout)
                .logRequests(true)
                .logResponses(false)
                .build();
            client = new DefaultMcpClient.Builder().transport(transport).build();
            logger.debug("MCP connection established sseUrl=" + sseUrl);
        } catch (Throwable t) {
            logger.debug("MCP build failed: " + t.getMessage());
            client = null;
        }
    }

    public <T> T withClient(Function<DefaultMcpClient, T> fn) {
        ensureInit();
        if (client == null) return null;
        try {
            return fn.apply(client);
        } catch (Throwable first) {
            logger.debug("MCP op failed: " + first.getMessage() + " -> retrying once");
            reconnect();
            if (client == null) return null;
            try {
                return fn.apply(client);
            } catch (Throwable second) {
                logger.debug("MCP op failed again: " + second.getMessage());
                return null;
            }
        }
    }

    public boolean healthCheck() {
        Boolean result = withClient(c -> {
            try {
                c.listTools();
                return Boolean.TRUE;
            } catch (Throwable t) {
                logger.debug("Health check failed: " + t.getMessage());
                return Boolean.FALSE;
            }
        });
        return Boolean.TRUE.equals(result);
    }

    private void reconnect() {
        synchronized (initLock) {
            closeQuiet();
            build();
            reconnects.incrementAndGet();
        }
    }

    public int reconnectCount() { return reconnects.get(); }

    public void closeQuiet() {
        try {
            if (client != null) {
                client.close();
            }
        } catch (Exception ex) {
            logger.debug("Error closing MCP client: " + ex.getMessage());
        }
        try {
            if (transport != null) {
                transport.close();
            }
        } catch (Exception ex) {
            logger.debug("Error closing MCP transport: " + ex.getMessage());
        }
        client = null;
        transport = null;
    }
}