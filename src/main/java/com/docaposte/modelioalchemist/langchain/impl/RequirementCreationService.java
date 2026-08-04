package com.docaposte.modelioalchemist.langchain.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Direct MCP requirement/container creation, shared by {@code LangchainService#createRequirementsInModelio}
 * and the PHASE 1 requirements step of {@code LangchainService#createUmlClassModel}.
 */
final class RequirementCreationService {

    private RequirementCreationService() {}

    private static final String DEFAULT_REQUIREMENT_CONTAINER_NAME = "ModelioAlchemist Requirements";
    private static final String DEFAULT_REQUIREMENT_CONTAINER_DEFINITION =
            "Automatically created by ModelioAlchemist to store imported requirements.";
    private static final Pattern REAL_UUID_PATTERN = Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");

    static String createRequirementsDirectlyViaMcp(List<Requirement> requirements, String outputDirectory) throws IOException, InterruptedException {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode createdRequirements = mapper.createArrayNode();
        ArrayNode failedRequirements = mapper.createArrayNode();
        StringBuilder executionTrace = new StringBuilder();
        String requirementContainerUuid = null;

        McpAssistantPool.debug("📝 Creating " + requirements.size() + " requirements in Modelio via direct MCP calls...");

        for (int i = 0; i < requirements.size(); i++) {
            Requirement requirement = requirements.get(i);
            String response = createRequirementElement(requirement, requirementContainerUuid, mapper, executionTrace);

            if (requirementContainerUuid == null && McpFailurePatterns.isMissingRequirementContainerError(response)) {
                McpAssistantPool.debug("📁 No RequirementContainer found. Creating one automatically before retrying " + requirement.id);
                requirementContainerUuid = createRequirementContainer(outputDirectory, mapper, executionTrace);
                response = createRequirementElement(requirement, requirementContainerUuid, mapper, executionTrace);
            }

            McpAssistantPool.debug("MCP direct analyst_createElement for " + requirement.id + " -> " + McpAssistantPool.truncate(response, 400));

            String createdUuid = extractFirstRealUuid(response);
            if (createdUuid == null) {
                ObjectNode failedRequirement = failedRequirements.addObject();
                failedRequirement.put("id", requirement.id);
                failedRequirement.put("description", requirement.description);
                failedRequirement.put("error", extractMcpErrorMessage(response, "No UUID returned by analyst_createElement"));
                failedRequirement.put("raw_response", McpAssistantPool.truncate(response, 1200));
                break;
            }

            ObjectNode createdRequirement = createdRequirements.addObject();
            createdRequirement.put("id", requirement.id);
            createdRequirement.put("uuid", createdUuid);
            createdRequirement.put("description", requirement.description);
            createdRequirement.put("category", requirement.category);
            createdRequirement.put("priority", requirement.priority);
            if (requirementContainerUuid != null) {
                createdRequirement.put("container_uuid", requirementContainerUuid);
            }
            createdRequirement.put("origin", requirement.origin);

            if (i < requirements.size() - 1) {
                // Give Modelio a brief moment to process Analyst-side refreshes between transactions.
                // Was previously 250ms; that added ~seconds of pure idle wait for documents with many
                // requirements (e.g. 10s+ for 40 requirements). 40ms is still enough slack for the
                // refresh while cutting that overhead by ~6x.
                TimeUnit.MILLISECONDS.sleep(40);
            }
        }

        if (outputDirectory != null) {
            Files.writeString(Path.of(outputDirectory).resolve("requirements_direct_mcp_trace.txt"), executionTrace.toString());
        }

        ObjectNode report = mapper.createObjectNode();
        report.set("requirements_created", createdRequirements);
        report.put("total_requirements", createdRequirements.size());
        if (requirementContainerUuid == null) {
            report.putNull("package_uuid");
            report.putNull("container_uuid");
        } else {
            report.put("package_uuid", requirementContainerUuid);
            report.put("container_uuid", requirementContainerUuid);
        }
        if (!failedRequirements.isEmpty()) {
            report.set("failed_requirements", failedRequirements);
        }

        String reportJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        if (!failedRequirements.isEmpty() || createdRequirements.size() != requirements.size()) {
            throw new IllegalStateException("MCP direct requirement creation stopped after "
                    + createdRequirements.size() + "/" + requirements.size() + " items. Partial report:\n" + reportJson);
        }

        return reportJson;
    }

    private static String createRequirementElement(Requirement requirement, String containerUuid, ObjectMapper mapper,
            StringBuilder executionTrace) throws IOException {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("type", "Requirement");
        arguments.put("name", requirement.id);
        arguments.put("definition", requirement.description);
        if (containerUuid != null && !containerUuid.isBlank()) {
            arguments.put("container_uuid", containerUuid);
        }
        ObjectNode analystProperties = arguments.putObject("analyst_properties");
        analystProperties.put("categorie", requirement.category);
        analystProperties.put("priorité", requirement.priority);
        if (requirement.origin != null && !requirement.origin.isBlank()) {
            // "Origin" (capital O) is the exact analyst property key defined by the Modelio
            // Analyst project's Requirement stereotype in the target environment. Property keys
            // set via analyst_properties are stored verbatim (no case-insensitive fallback), so
            // they must match the stereotype definition precisely or the value is written under
            // an unrecognized key and never shows up in Modelio's "Origin" field.
            analystProperties.put("Origin", requirement.origin);
            // Keep legacy lower/French-case keys too for backward compatibility with
            // environments whose stereotype defines the property differently.
            analystProperties.put("origin", requirement.origin);
            analystProperties.put("origine", requirement.origin);
        }

        return executeAnalystTool("REQ " + requirement.id, "create-requirement-" + requirement.id, "analyst_createElement",
                arguments, mapper, executionTrace);
    }

    private static String createRequirementContainer(String outputDirectory, ObjectMapper mapper, StringBuilder executionTrace)
            throws IOException {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("type", "RequirementContainer");
        arguments.put("name", DEFAULT_REQUIREMENT_CONTAINER_NAME);
        arguments.put("definition", DEFAULT_REQUIREMENT_CONTAINER_DEFINITION);
        // Explicitly set the container as modifiable to allow deletion and editing of contained elements
        arguments.put("isModifiable", true);

        String response = executeAnalystTool("CONTAINER auto-create", "create-requirement-container-" + System.nanoTime(),
                "analyst_createContainer", arguments, mapper, executionTrace);
        McpAssistantPool.debug("MCP direct analyst_createContainer -> " + McpAssistantPool.truncate(response, 400));

        String containerUuid = extractFirstRealUuid(response);
        if (containerUuid == null) {
            throw new IllegalStateException("Unable to create RequirementContainer automatically. "
                    + extractMcpErrorMessage(response, "No UUID returned by analyst_createContainer"));
        }

        if (outputDirectory != null) {
            Files.writeString(Path.of(outputDirectory).resolve("requirements_container_creation_report.txt"), response);
        }

        return containerUuid;
    }

    private static String executeAnalystTool(String traceLabel, String requestId, String toolName, ObjectNode arguments,
            ObjectMapper mapper, StringBuilder executionTrace) throws IOException {
        String argumentsJson = mapper.writeValueAsString(arguments);
        executionTrace.append(traceLabel).append(" args=").append(argumentsJson).append(System.lineSeparator());

        String response = McpAssistantPool.sharedMcpClient().executeTool(ToolExecutionRequest.builder()
                .id(requestId)
                .name(toolName)
                .arguments(argumentsJson)
                .build());
        executionTrace.append(traceLabel).append(" response=")
                .append(McpAssistantPool.truncate(response, 1200))
                .append(System.lineSeparator())
                .append(System.lineSeparator());
        return response;
    }

    private static String extractMcpErrorMessage(String response, String fallbackMessage) {
        if (response == null || response.isBlank()) {
            return fallbackMessage;
        }

        try {
            JsonNode root = new ObjectMapper().readTree(response);
            if (root.path("isError").asBoolean(false)) {
                String message = root.path("message").asText(null);
                if (message != null && !message.isBlank()) {
                    return message;
                }
            }
        } catch (Exception e) {
            McpAssistantPool.debug("⚠️ Could not parse MCP error payload: " + e.getMessage());
        }

        return fallbackMessage + " | response=" + McpAssistantPool.truncate(response, 400);
    }

    static String extractFirstRealUuid(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher matcher = REAL_UUID_PATTERN.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }
}
