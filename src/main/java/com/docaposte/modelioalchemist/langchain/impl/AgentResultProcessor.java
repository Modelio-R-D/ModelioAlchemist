package com.docaposte.modelioalchemist.langchain.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure post-processing of agent output strings: UUID/JSON extraction, structural fallback
 * synthesis, and result validation. Extracted from {@code LangchainService}; needs no static
 * MCP state beyond debug logging.
 */
final class AgentResultProcessor {

    private AgentResultProcessor() {}

    private static final Pattern REAL_UUID_PATTERN = Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");
    private static final Pattern PLACEHOLDER_UUID_VALUE_PATTERN = Pattern.compile("\"[^\"]*uuid[^\"]*\"\\s*:\\s*\"uuid-[^\"]+\"", Pattern.CASE_INSENSITIVE);

    /**
     * Extrait automatiquement les UUIDs depuis un output d'agent
     */
    static List<String> extractUUIDs(String agentOutput) {
        List<String> uuids = new ArrayList<>();
        if (agentOutput == null) return uuids;

        Matcher matcher = REAL_UUID_PATTERN.matcher(agentOutput);

        while (matcher.find()) {
            uuids.add(matcher.group());
        }

        McpAssistantPool.debug("📋 Extracted " + uuids.size() + " UUIDs from agent output");
        return uuids;
    }

    /**
     * Extrait les UUIDs des exigences uniquement depuis la structure JSON dédiée.
     * Évite de fournir des UUIDs de package/conteneur qui cassent analyst_createRelation(satisfy).
     */
    static List<String> extractRequirementUUIDs(String requirementsOutput) {
        if (requirementsOutput == null || requirementsOutput.isBlank()) {
            return List.of();
        }

        String requirementsJson = extractJSONStructure(requirementsOutput, "requirements_created", "exigences_creees");
        if (requirementsJson == null || requirementsJson.isBlank()) {
            return extractUUIDs(requirementsOutput);
        }

        try {
            JsonNode root = new ObjectMapper().readTree(requirementsJson);
            JsonNode created = root.path("requirements_created");
            if (!created.isArray()) {
                created = root.path("exigences_creees");
            }

            if (!created.isArray()) {
                return extractUUIDs(requirementsOutput);
            }

            List<String> requirementUuids = new ArrayList<>();
            for (JsonNode requirementNode : created) {
                String uuid = requirementNode.path("uuid").asText(null);
                if (uuid != null && REAL_UUID_PATTERN.matcher(uuid).matches()) {
                    requirementUuids.add(uuid);
                }
            }

            if (!requirementUuids.isEmpty()) {
                McpAssistantPool.debug("📋 Extracted " + requirementUuids.size() + " requirement-only UUIDs from requirements JSON");
                return requirementUuids;
            }
        } catch (Exception e) {
            McpAssistantPool.debug("⚠️ Could not parse requirement-only UUIDs from JSON: " + e.getMessage());
        }

        return extractUUIDs(requirementsOutput);
    }

    /**
     * Extrait la structure JSON depuis un output d'agent
     */
    static String extractJSONStructure(String agentOutput, String... jsonKeys) {
        if (agentOutput == null || jsonKeys == null || jsonKeys.length == 0) return null;

        try {
            // Chercher le bloc JSON avec la clé spécifiée
            int jsonStart = agentOutput.indexOf("{");
            int jsonEnd = agentOutput.lastIndexOf("}");

            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                String potentialJson = agentOutput.substring(jsonStart, jsonEnd + 1);

                for (String jsonKey : jsonKeys) {
                    if (jsonKey != null && potentialJson.contains("\"" + jsonKey + "\"")) {
                        McpAssistantPool.debug("📊 Extracted JSON structure for key: " + jsonKey);
                        return potentialJson;
                    }
                }
            }

            McpAssistantPool.debug("⚠️ No JSON structure found for keys: " + String.join(", ", jsonKeys));
            return null;

        } catch (Exception e) {
            McpAssistantPool.debug("❌ Error extracting JSON structure: " + e.getMessage());
            return null;
        }
    }

    static String ensureStructuredRequirementsResult(String result) {
        if (extractJSONStructure(result, "requirements_created", "exigences_creees") != null) {
            return result;
        }
        if (result == null || result.trim().isEmpty()) {
            return result;
        }
        if (result.trim().startsWith("MCP_EXECUTION_FAILED:") || result.trim().startsWith("❌") || result.trim().startsWith("[error:")) {
            return result;
        }

        List<String> realUuids = extractUUIDs(result);

        // If we have real UUIDs, MCP work was done - synthesize minimal JSON structure
        if (!realUuids.isEmpty()) {
            String synthesizedJson = synthesizeMinimalRequirementsJson(realUuids);
            if (synthesizedJson != null) {
                McpAssistantPool.debug("⚠️ requirements_phase returned no structured JSON; synthesized fallback requirements_created payload from " + realUuids.size() + " UUIDs");
                return result
                        + System.lineSeparator()
                        + System.lineSeparator()
                        + "```json"
                        + System.lineSeparator()
                        + synthesizedJson
                        + System.lineSeparator()
                        + "```";
            }
        }

        return result;
    }

    private static String synthesizeMinimalRequirementsJson(List<String> realUuids) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            ArrayNode requirementsArray = root.putArray("requirements_created");

            // Heuristic: the first UUID is likely the package, the rest are requirements
            for (int i = (realUuids.isEmpty() ? 0 : 1); i < realUuids.size(); i++) {
                ObjectNode req = requirementsArray.addObject();
                req.put("uuid", realUuids.get(i));
                req.put("synthesized", true);
            }

            root.put("total_requirements", requirementsArray.size());
            if (!realUuids.isEmpty()) {
                root.put("package_uuid", realUuids.get(0));
                root.put("container_uuid", realUuids.get(0));
            }

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            McpAssistantPool.debug("⚠️ Could not synthesize minimal requirements_created JSON: " + e.getMessage());
            return null;
        }
    }

    static String ensureStructuredDomainModelResult(String result) {
        if (extractJSONStructure(result, "domain_model_created", "modele_domaine_cree") != null) {
            return result;
        }
        if (result == null || result.trim().isEmpty()) {
            return result;
        }
        if (result.trim().startsWith("MCP_EXECUTION_FAILED:") || result.trim().startsWith("❌") || result.trim().startsWith("[error:")) {
            return result;
        }

        List<String> realUuids = extractUUIDs(result);

        // Fallback 1: PlantUML + UUIDs → synthesize full JSON from PlantUML
        String asBuiltPlantUml = PlantUmlParser.extractPlantUMLDiagram(result, "MODELE_DOMAINE_AS_BUILT");
        if (asBuiltPlantUml == null) {
            asBuiltPlantUml = PlantUmlParser.extractPlantUMLDiagram(result, "AS_BUILT_DOMAIN_MODEL");
        }
        if (asBuiltPlantUml != null && PlantUMLAnalyzer.isValidPlantUML(asBuiltPlantUml) && !realUuids.isEmpty()) {
            String synthesizedJson = PlantUmlParser.synthesizeDomainModelJson(asBuiltPlantUml, realUuids);
            if (synthesizedJson != null) {
                McpAssistantPool.debug("⚠️ domain_model_phase returned no structured JSON; synthesized fallback domain_model_created payload from as-built PlantUML");
                return result
                        + System.lineSeparator()
                        + System.lineSeparator()
                        + "```json"
                        + System.lineSeparator()
                        + synthesizedJson
                        + System.lineSeparator()
                        + "```";
            }
        }

        // Fallback 2: UUIDs present but no PlantUML → synthesize minimal JSON from UUIDs
        // This covers the case where MCP tools executed (UUIDs prove it) but the LLM
        // didn't produce a PlantUML diagram or structured JSON in its final output.
        if (!realUuids.isEmpty()) {
            String synthesizedJson = PlantUmlParser.synthesizeMinimalDomainModelJson(realUuids);
            if (synthesizedJson != null) {
                McpAssistantPool.debug("⚠️ domain_model_phase returned no structured JSON or PlantUML; synthesized minimal domain_model_created payload from " + realUuids.size() + " UUIDs");
                return result
                        + System.lineSeparator()
                        + System.lineSeparator()
                        + "```json"
                        + System.lineSeparator()
                        + synthesizedJson
                        + System.lineSeparator()
                        + "```";
            }
        }

        return result;
    }

    static String ensureStructuredUseCasesResult(String result) {
        if (extractJSONStructure(result, "use_cases_created") != null) {
            return result;
        }
        if (result == null || result.trim().isEmpty()) {
            return result;
        }
        if (result.trim().startsWith("MCP_EXECUTION_FAILED:") || result.trim().startsWith("❌") || result.trim().startsWith("[error:")) {
            return result;
        }

        List<String> realUuids = extractUUIDs(result);

        // Fallback 1: PlantUML + UUIDs → synthesize a full JSON payload from the as-built output.
        String useCasesPlantUml = PlantUmlParser.extractPlantUMLDiagram(result, "USE_CASES_DIAGRAM");
        if (useCasesPlantUml != null && PlantUMLAnalyzer.isValidPlantUML(useCasesPlantUml) && !realUuids.isEmpty()) {
            String synthesizedJson = PlantUmlParser.synthesizeUseCasesJson(useCasesPlantUml, realUuids);
            if (synthesizedJson != null) {
                McpAssistantPool.debug("⚠️ use_cases_phase returned no structured JSON; synthesized fallback use_cases_created payload from as-built PlantUML");
                return result
                        + System.lineSeparator()
                        + System.lineSeparator()
                        + "```json"
                        + System.lineSeparator()
                        + synthesizedJson
                        + System.lineSeparator()
                        + "```";
            }
        }

        // Fallback 2: UUIDs present but no PlantUML → synthesize a minimal JSON payload from UUIDs.
        if (!realUuids.isEmpty()) {
            String synthesizedJson = PlantUmlParser.synthesizeMinimalUseCasesJson(realUuids);
            if (synthesizedJson != null) {
                McpAssistantPool.debug("⚠️ use_cases_phase returned no structured JSON or PlantUML; synthesized minimal use_cases_created payload from " + realUuids.size() + " UUIDs");
                return result
                        + System.lineSeparator()
                        + System.lineSeparator()
                        + "```json"
                        + System.lineSeparator()
                        + synthesizedJson
                        + System.lineSeparator()
                        + "```";
            }
        }

        return result;
    }

    static void validateMcpExecutionResult(String phaseName, String result, String... expectedJsonKeys) {
        if (result == null || result.trim().isEmpty()) {
            throw new IllegalStateException("No output returned for phase '" + phaseName + "'");
        }

        String trimmed = result.trim();
        if (trimmed.contains("Satisfait traceability link skipped")) {
            McpAssistantPool.debug("⚠️ Phase '" + phaseName + "' accepted with optional Satisfait traceability skipped.");
            return;
        }
        if (trimmed.startsWith("MCP_EXECUTION_FAILED:") || trimmed.startsWith("❌") || trimmed.startsWith("[error:")) {
            throw new IllegalStateException(trimmed);
        }

        if (McpFailurePatterns.MANUAL_INSTRUCTIONS_PATTERN.matcher(result).find()) {
            throw new IllegalStateException(
                    "The LLM returned manual instructions instead of executing MCP tools during phase '" + phaseName + "'");
        }

        if (PLACEHOLDER_UUID_VALUE_PATTERN.matcher(result).find() && !REAL_UUID_PATTERN.matcher(result).find()) {
            throw new IllegalStateException(
                    "The LLM returned placeholder UUID values instead of real MCP UUIDs during phase '" + phaseName + "'");
        }

        // More lenient JSON validation: if we have real UUIDs, MCP work was done even if JSON is malformed
        if (expectedJsonKeys != null && expectedJsonKeys.length > 0) {
            String jsonStructure = extractJSONStructure(result, expectedJsonKeys);
            if (jsonStructure == null) {
                // Check if MCP work was actually done (evidenced by real UUIDs)
                List<String> realUuids = extractUUIDs(result);
                if (realUuids.isEmpty()) {
                    // No UUIDs and no JSON = genuine failure
                    throw new IllegalStateException(
                            "No structured JSON result was returned for phase '" + phaseName + "'");
                }
                // UUIDs present = MCP work was done, just output formatting issue
                McpAssistantPool.debug("⚠️ Phase '" + phaseName + "' missing JSON structure but has " + realUuids.size() + " UUIDs (MCP work confirmed)");
            }
        }
    }
}
