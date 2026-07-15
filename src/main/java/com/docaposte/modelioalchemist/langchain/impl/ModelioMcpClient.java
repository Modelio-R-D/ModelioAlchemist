package com.docaposte.modelioalchemist.langchain.impl;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.time.Duration;
import java.util.List;

/**
 * Minimal MCP client wrapper following ModelioBot architecture.
 * No hardcoded tool methods - only dynamic tool discovery via McpToolProvider.
 */
public class ModelioMcpClient {

    private final McpClient mcpClient;
    private final McpToolProvider toolProvider;

    public ModelioMcpClient(String sseUrl) {
        // Initialize MCP transport and client
        StreamableHttpMcpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url(sseUrl)
                .timeout(Duration.ofSeconds(60))
                .logRequests(true)
                .logResponses(false)
                .build();
                
        this.mcpClient = new DefaultMcpClient.Builder()
                .transport(transport)
                .build();
                
        // Create tool provider for dynamic tool discovery (follows ModelioBot pattern)
        this.toolProvider = McpToolProvider.builder()
                .mcpClients(mcpClient)
                .build();
    }

    /**
     * Get the MCP tool provider for LangChain4j integration
     */
    public McpToolProvider getToolProvider() {
        return toolProvider;
    }

    /**
     * List available MCP tools dynamically
     */
    public List<ToolSpecification> listAvailableTools() {
        try {
            List<ToolSpecification> tools = mcpClient.listTools();
            System.out.println("=== Available MCP Tools ===");
            tools.forEach(tool -> 
                System.out.println("Tool: " + tool.name() + " - " + tool.description())
            );
            System.out.println("============================");
            return tools;
        } catch (Exception e) {
            System.err.println("Failed to list MCP tools: " + e.getMessage());
            return List.of();
        }
    }
    
    /**
     * Close the MCP client and release resources
     */
    public void close() {
        try {
            if (mcpClient != null) {
                mcpClient.close();
            }
        } catch (Exception e) {
            System.err.println("Error closing MCP client: " + e.getMessage());
        }
    }
}
