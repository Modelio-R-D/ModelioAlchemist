package com.docaposte.modelioalchemist.langchain.impl;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * MCP client wrapper to interface with Modelio MCP server for UML element creation.
 */
public class ModelioMcpClient {

    private final McpClient mcpClient;
    private final String sseUrl;
    private final ObjectMapper objectMapper;

    public ModelioMcpClient(String sseUrl) {
        this.sseUrl = sseUrl;
        this.objectMapper = new ObjectMapper();
        
        // Initialize MCP transport and client
        HttpMcpTransport transport = new HttpMcpTransport.Builder()
                .sseUrl(sseUrl)
                .timeout(Duration.ofSeconds(60))
                .logRequests(true)
                .logResponses(false)
                .build();
                
        this.mcpClient = new DefaultMcpClient.Builder()
                .transport(transport)
                .build();
    }

    public String createUmlClass(String name, String packageUuid) throws Exception {
        try {
            // Create tool execution request for class creation using the correct tool name
            // and parameters format expected by the MCP server
            Map<String, Object> argumentsMap = Map.of(
                "name", name,
                "metaclassType", "Standard.Class"  // Standard UML Class metaclass
            );
            
            // Convert Map to JSON string
            String argumentsJson = objectMapper.writeValueAsString(argumentsMap);
            
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .name("createUmlElement")  // Correct tool name from the MCP server
                    .arguments(argumentsJson)  // JSON string instead of Map
                    .build();
                    
            // Execute the tool call
            String result = mcpClient.executeTool(request);
            return result;
            
        } catch (Exception e) {
            throw new Exception("MCP create failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Create a generic UML element with specified metaclass type
     */
    public String createUmlElement(String name, String metaclassType) throws Exception {
        try {
            Map<String, Object> argumentsMap = Map.of(
                "name", name,
                "metaclassType", metaclassType
            );
            
            // Convert Map to JSON string
            String argumentsJson = objectMapper.writeValueAsString(argumentsMap);
            
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .name("createUmlElement")
                    .arguments(argumentsJson)  // JSON string instead of Map
                    .build();
                    
            String result = mcpClient.executeTool(request);
            return result;
            
        } catch (Exception e) {
            throw new Exception("MCP create failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * List available MCP tools for debugging
     */
    public void listAvailableTools() {
        try {
            System.out.println("=== Available MCP Tools ===");
            mcpClient.listTools().forEach(tool -> 
                System.out.println("Tool: " + tool.name() + " - " + tool.description())
            );
            System.out.println("============================");
        } catch (Exception e) {
            System.err.println("Failed to list MCP tools: " + e.getMessage());
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
