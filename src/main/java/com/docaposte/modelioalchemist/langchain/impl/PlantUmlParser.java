package com.docaposte.modelioalchemist.langchain.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilities for parsing PlantUML diagrams and synthesising JSON summaries of
 * UML model elements. All methods are stateless and pure (no LangchainService state).
 */
class PlantUmlParser {

    // ------------------------------------------------------------------ DomainPlantUmlParts

    static final class DomainPlantUmlParts {
        final List<String> classBlocks;
        final List<String> relationLines;
        final List<String> classNames;

        DomainPlantUmlParts(List<String> classBlocks, List<String> relationLines, List<String> classNames) {
            this.classBlocks = classBlocks;
            this.relationLines = relationLines;
            this.classNames = classNames;
        }
    }

    // ------------------------------------------------------------------ diagram extraction / preparation

    static String extractPlantUMLDiagram(String agentOutput, String diagramName) {
        if (agentOutput == null || diagramName == null) return null;
        try {
            String startMarker = "@startuml " + diagramName;
            int start = agentOutput.indexOf(startMarker);
            if (start == -1) {
                start = agentOutput.indexOf("@startuml");
            }
            if (start != -1) {
                int end = agentOutput.indexOf("@enduml", start);
                if (end != -1) {
                    String diagram = agentOutput.substring(start, end + "@enduml".length());
                    debug("🎯 Extracted PlantUML diagram: " + diagramName);
                    return diagram;
                }
            }
            debug("⚠️ No PlantUML diagram found for: " + diagramName);
            return null;
        } catch (Exception e) {
            debug("❌ Error extracting PlantUML diagram: " + e.getMessage());
            return null;
        }
    }

    static String preparePlantUmlForPrompt(String analysisResults) {
        if (analysisResults == null || analysisResults.isBlank()) {
            return "";
        }
        String extractedDiagram = extractPlantUMLDiagram(analysisResults, "PROMPT_INPUT");
        if (extractedDiagram != null && !extractedDiagram.isBlank()) {
            return PlantUMLAnalyzer.cleanPlantUMLCode(extractedDiagram);
        }
        String cleaned = PlantUMLAnalyzer.cleanPlantUMLCode(analysisResults);
        if (PlantUMLAnalyzer.isValidPlantUML(cleaned)) {
            return cleaned;
        }
        return analysisResults.trim();
    }

    // ------------------------------------------------------------------ domain model parsing

    static DomainPlantUmlParts extractDomainPlantUmlParts(String plantUml) {
        if (plantUml == null || plantUml.isBlank()) {
            return new DomainPlantUmlParts(List.of(), List.of(), List.of());
        }
        List<String> classBlocks = new ArrayList<>();
        List<String> relationLines = new ArrayList<>();
        List<String> classNames = new ArrayList<>();
        StringBuilder currentClassBlock = null;
        int braceDepth = 0;

        for (String rawLine : plantUml.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty() || line.startsWith("'") || line.startsWith("//")) continue;
            if (line.startsWith("@startuml") || line.startsWith("@enduml")) continue;

            if (currentClassBlock != null) {
                currentClassBlock.append(line).append(System.lineSeparator());
                braceDepth += countOccurrences(line, '{') - countOccurrences(line, '}');
                if (braceDepth <= 0) {
                    classBlocks.add(currentClassBlock.toString().trim());
                    currentClassBlock = null;
                    braceDepth = 0;
                }
                continue;
            }

            String className = extractPlantUmlClassifierName(line);
            if (className != null && !className.isBlank()) {
                classNames.add(className);
                currentClassBlock = new StringBuilder();
                currentClassBlock.append(line).append(System.lineSeparator());
                braceDepth = countOccurrences(line, '{') - countOccurrences(line, '}');
                if (braceDepth <= 0) {
                    classBlocks.add(currentClassBlock.toString().trim());
                    currentClassBlock = null;
                    braceDepth = 0;
                }
                continue;
            }

            if (detectPlantUmlRelationToken(line) != null) {
                relationLines.add(line);
            }
        }

        // Deduplicate class blocks by name (preserving order)
        List<String> uniqueClassNames = new ArrayList<>();
        List<String> uniqueClassBlocks = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();
        for (int i = 0; i < classNames.size(); i++) {
            String name = classNames.get(i);
            if (seenNames.add(name)) {
                uniqueClassNames.add(name);
                uniqueClassBlocks.add(classBlocks.get(i));
            }
        }
        return new DomainPlantUmlParts(uniqueClassBlocks, relationLines, uniqueClassNames);
    }

    static String buildAsBuiltDomainPlantUml(List<String> classBlocks, List<String> relationLines) {
        if ((classBlocks == null || classBlocks.isEmpty()) && (relationLines == null || relationLines.isEmpty())) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("@startuml AS_BUILT_DOMAIN_MODEL").append(System.lineSeparator());
        if (classBlocks != null) {
            for (String classBlock : classBlocks) {
                builder.append(classBlock).append(System.lineSeparator());
            }
        }
        if (relationLines != null) {
            for (String relationLine : relationLines) {
                builder.append(relationLine).append(System.lineSeparator());
            }
        }
        builder.append("@enduml");
        return builder.toString();
    }

    // ------------------------------------------------------------------ JSON synthesis

    static String synthesizeDomainModelJsonWithFallback(String plantUml, List<String> realUuids) {
        if (plantUml != null && realUuids != null && !realUuids.isEmpty()) {
            String json = synthesizeDomainModelJson(plantUml, realUuids);
            if (json != null) return json;
        }
        if (realUuids != null && !realUuids.isEmpty()) {
            return synthesizeMinimalDomainModelJson(realUuids);
        }
        return null;
    }

    static String synthesizeDomainModelJson(String plantUml, List<String> realUuids) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            ObjectNode domainModel = root.putObject("domain_model_created");
            domainModel.put("package_uuid", realUuids.get(0));
            domainModel.put("synthesized_from_as_built_output", true);
            appendClassesFromPlantUml(domainModel.putArray("classes"), plantUml);
            appendAssociationsFromPlantUml(domainModel.putArray("associations"), plantUml);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            debug("⚠️ Could not synthesize domain_model_created JSON: " + e.getMessage());
            return null;
        }
    }

    static String synthesizeMinimalDomainModelJson(List<String> realUuids) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            ObjectNode domainModel = root.putObject("domain_model_created");
            domainModel.put("synthesized_from_uuids_only", true);
            domainModel.put("uuid_count", realUuids.size());
            if (!realUuids.isEmpty()) {
                domainModel.put("package_uuid", realUuids.get(0));
            }
            ArrayNode classes = domainModel.putArray("classes");
            for (int i = 1; i < realUuids.size(); i++) {
                ObjectNode cls = classes.addObject();
                cls.put("uuid", realUuids.get(i));
                cls.put("synthesized", true);
                cls.putArray("attributs");
                cls.putArray("exigences_liees");
            }
            domainModel.putArray("associations");
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            debug("⚠️ Could not synthesize minimal domain_model_created JSON: " + e.getMessage());
            return null;
        }
    }

    static String synthesizeUseCasesJson(String plantUml, List<String> realUuids) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            ObjectNode useCases = root.putObject("use_cases_created");
            useCases.put("package_uuid", realUuids.get(0));
            useCases.put("synthesized_from_as_built_output", true);
            appendActorsFromPlantUml(useCases.putArray("actors"), plantUml);
            appendUseCasesFromPlantUml(useCases.putArray("use_cases"), plantUml);
            appendUseCaseRelationsFromPlantUml(useCases.putArray("relations"), plantUml);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            debug("⚠️ Could not synthesize use_cases_created JSON: " + e.getMessage());
            return null;
        }
    }

    static String synthesizeMinimalUseCasesJson(List<String> realUuids) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode root = mapper.createObjectNode();
            ObjectNode useCases = root.putObject("use_cases_created");
            useCases.put("synthesized_from_uuids_only", true);
            useCases.put("uuid_count", realUuids.size());
            if (!realUuids.isEmpty()) {
                useCases.put("package_uuid", realUuids.get(0));
            }
            ArrayNode actors = useCases.putArray("actors");
            ArrayNode useCasesArray = useCases.putArray("use_cases");
            useCases.putArray("relations");
            for (int i = 1; i < realUuids.size(); i++) {
                ObjectNode item = (i % 2 == 1 ? actors : useCasesArray).addObject();
                item.put("uuid", realUuids.get(i));
                item.put("synthesized", true);
            }
            useCases.putArray("uncovered_requirements");
            useCases.put("coverage_rate", 0.0);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            debug("⚠️ Could not synthesize minimal use_cases_created JSON: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ PlantUML → JSON helpers

    static void appendClassesFromPlantUml(ArrayNode classes, String plantUml) {
        Set<String> seenClassNames = new LinkedHashSet<>();
        ObjectNode currentClass = null;
        for (String rawLine : plantUml.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty() || line.startsWith("'") || line.startsWith("//")) continue;
            if (line.startsWith("@startuml") || line.startsWith("@enduml")) continue;
            if (line.startsWith("}")) { currentClass = null; continue; }
            String className = extractPlantUmlClassifierName(line);
            if (className != null) {
                if (seenClassNames.add(className)) {
                    currentClass = classes.addObject();
                    currentClass.put("nom", className);
                    currentClass.putNull("uuid");
                    currentClass.putArray("attributs");
                    currentClass.putArray("exigences_liees");
                } else {
                    currentClass = null;
                }
                if (!line.contains("{")) currentClass = null;
                continue;
            }
            if (currentClass != null && line.contains(":") && !line.contains("(")) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    String attributeName = cleanupPlantUmlAttributeToken(parts[0]);
                    String attributeType = cleanupPlantUmlAttributeToken(parts[1]);
                    if (!attributeName.isEmpty() && !attributeType.isEmpty()) {
                        ObjectNode attribute = ((ArrayNode) currentClass.get("attributs")).addObject();
                        attribute.put("nom", attributeName);
                        attribute.put("type", attributeType);
                    }
                }
            }
        }
    }

    static void appendAssociationsFromPlantUml(ArrayNode associations, String plantUml) {
        for (String rawLine : plantUml.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty() || line.startsWith("'") || line.startsWith("//")) continue;
            String relationToken = detectPlantUmlRelationToken(line);
            if (relationToken == null) continue;
            String relationWithoutLabel = line;
            String relationLabel = null;
            int labelSeparator = line.indexOf(':');
            if (labelSeparator >= 0) {
                relationWithoutLabel = line.substring(0, labelSeparator).trim();
                relationLabel = line.substring(labelSeparator + 1).trim();
            }
            String[] endpoints = relationWithoutLabel.split(Pattern.quote(relationToken), 2);
            if (endpoints.length != 2) continue;
            String from = cleanupPlantUmlRelationEndpoint(endpoints[0]);
            String to   = cleanupPlantUmlRelationEndpoint(endpoints[1]);
            if (from.isEmpty() || to.isEmpty()) continue;
            ObjectNode association = associations.addObject();
            association.put("de", from);
            association.put("vers", to);
            association.put("type", mapPlantUmlRelationType(relationToken));
            if (relationLabel != null && !relationLabel.isEmpty()) {
                association.put("nom", relationLabel);
            }
            String cardinality = extractPlantUmlCardinality(relationWithoutLabel);
            if (cardinality == null || cardinality.isBlank()) {
                association.putNull("cardinalite");
            } else {
                association.put("cardinalite", cardinality);
            }
        }
    }

    static void appendActorsFromPlantUml(ArrayNode actors, String plantUml) {
        Set<String> seen = new LinkedHashSet<>();
        for (String rawLine : plantUml.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (!line.contains("actor ")) continue;
            String actorName = extractActorNameFromPlantUml(line);
            if (actorName != null && seen.add(actorName)) {
                ObjectNode actor = actors.addObject();
                actor.put("name", actorName);
                actor.putNull("uuid");
                actor.put("synthesized", true);
            }
        }
    }

    static void appendUseCasesFromPlantUml(ArrayNode useCases, String plantUml) {
        Set<String> seen = new LinkedHashSet<>();
        for (String rawLine : plantUml.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (!line.contains("usecase ")) continue;
            String name = extractUseCaseNameFromPlantUml(line);
            if (name != null && seen.add(name)) {
                ObjectNode uc = useCases.addObject();
                uc.put("name", name);
                uc.putNull("uuid");
                uc.put("synthesized", true);
                uc.putArray("actors");
                uc.putArray("linked_requirements");
                uc.putArray("domain_classes_used");
            }
        }
    }

    static void appendUseCaseRelationsFromPlantUml(ArrayNode relations, String plantUml) {
        for (String rawLine : plantUml.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty() || line.startsWith("'") || line.startsWith("//")) continue;
            if (!(line.contains("-->") || line.contains("<--") || line.contains("..>") || line.contains("<.."))) continue;
            String relationToken = detectPlantUmlRelationToken(line);
            if (relationToken == null) continue;
            String relationWithoutLabel = line;
            String relationLabel = null;
            int labelSeparator = line.indexOf(':');
            if (labelSeparator >= 0) {
                relationWithoutLabel = line.substring(0, labelSeparator).trim();
                relationLabel = line.substring(labelSeparator + 1).trim();
            }
            String[] endpoints = relationWithoutLabel.split(Pattern.quote(relationToken), 2);
            if (endpoints.length != 2) continue;
            String from = cleanupPlantUmlRelationEndpoint(endpoints[0]);
            String to   = cleanupPlantUmlRelationEndpoint(endpoints[1]);
            if (from.isEmpty() || to.isEmpty()) continue;
            ObjectNode relation = relations.addObject();
            relation.put("from", from);
            relation.put("to", to);
            relation.put("type", mapPlantUmlRelationType(relationToken));
            if (relationLabel != null && !relationLabel.isEmpty()) {
                relation.put("label", relationLabel);
            }
        }
    }

    // ------------------------------------------------------------------ low-level PlantUML helpers

    static String extractPlantUmlClassifierName(String line) {
        String working = line;
        if (working.startsWith("abstract ")) {
            working = working.substring("abstract ".length()).trim();
        }
        String[] prefixes = {"class ", "interface ", "enum ", "entity "};
        String matchedPrefix = null;
        for (String prefix : prefixes) {
            if (working.startsWith(prefix)) { matchedPrefix = prefix; break; }
        }
        if (matchedPrefix == null) return null;
        working = working.substring(matchedPrefix.length()).trim();
        if (working.isEmpty()) return null;
        if (working.startsWith("\"")) {
            int endQuote = working.indexOf('"', 1);
            if (endQuote > 1) return working.substring(1, endQuote).trim();
        }
        int cutIndex = working.length();
        for (String delimiter : new String[]{" {", " as ", " <<", " extends ", " implements "}) {
            int index = working.indexOf(delimiter);
            if (index >= 0 && index < cutIndex) cutIndex = index;
        }
        String name = working.substring(0, cutIndex).trim();
        if (name.endsWith("{")) name = name.substring(0, name.length() - 1).trim();
        return name;
    }

    static String cleanupPlantUmlAttributeToken(String token) {
        if (token == null) return "";
        String cleaned = token.trim()
                .replace("+", "").replace("-", "").replace("#", "").replace("~", "").trim();
        int genericStart = cleaned.indexOf('{');
        if (genericStart >= 0) cleaned = cleaned.substring(0, genericStart).trim();
        return cleaned;
    }

    static String detectPlantUmlRelationToken(String line) {
        for (String token : new String[]{"<|--", "--|>", "*--", "--*", "o--", "--o", "<--", "-->", "..>", "<..", "..", "--"}) {
            if (line.contains(token)) return token;
        }
        return null;
    }

    static String cleanupPlantUmlRelationEndpoint(String endpoint) {
        if (endpoint == null) return "";
        String cleaned = endpoint.replaceAll("\"[^\"]*\"", " ")
                .replaceAll("\\b(left|right|up|down|hidden)\\b", " ").trim();
        Matcher matcher = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_.-]*").matcher(cleaned);
        String lastMatch = "";
        while (matcher.find()) lastMatch = matcher.group();
        return lastMatch;
    }

    static String mapPlantUmlRelationType(String relationToken) {
        return switch (relationToken) {
            case "<|--", "--|>" -> "Generalization";
            case "*--", "--*"   -> "Composition";
            case "o--", "--o"   -> "Aggregation";
            case "..>", "<..", ".." -> "Dependency";
            default             -> "Association";
        };
    }

    static String extractPlantUmlCardinality(String relationLine) {
        if (relationLine == null || relationLine.isBlank()) return null;
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(relationLine);
        List<String> values = new ArrayList<>();
        while (matcher.find()) values.add(matcher.group(1).trim());
        return values.isEmpty() ? null : String.join(" / ", values);
    }

    static String extractActorNameFromPlantUml(String line) {
        if (line == null) return null;
        String working = line.substring("actor ".length()).trim();
        if (working.isEmpty()) return null;
        if (working.startsWith("\"")) {
            int endQuote = working.indexOf('"', 1);
            if (endQuote > 1) return working.substring(1, endQuote).trim();
        }
        int asIndex = working.indexOf(" as ");
        if (asIndex >= 0) working = working.substring(0, asIndex).trim();
        return working.isEmpty() ? null : working;
    }

    static String extractUseCaseNameFromPlantUml(String line) {
        if (line == null) return null;
        int firstQuote = line.indexOf('"');
        int secondQuote = firstQuote >= 0 ? line.indexOf('"', firstQuote + 1) : -1;
        if (firstQuote >= 0 && secondQuote > firstQuote) {
            return line.substring(firstQuote + 1, secondQuote).trim();
        }
        String[] tokens = line.split("\\s+");
        return tokens.length >= 2 ? tokens[tokens.length - 1].trim() : null;
    }

    // ------------------------------------------------------------------ generic utilities

    /** Splits {@code input} into consecutive sublists of at most {@code chunkSize} elements. */
    static <T> List<List<T>> splitIntoChunks(List<T> input, int chunkSize) {
        if (input == null || input.isEmpty()) return List.of();
        if (chunkSize <= 0) return List.of(input);
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < input.size(); i += chunkSize) {
            chunks.add(new ArrayList<>(input.subList(i, Math.min(input.size(), i + chunkSize))));
        }
        return chunks;
    }

    static <T> List<T> deduplicatePreservingOrder(List<T> values) {
        if (values == null || values.isEmpty()) return List.of();
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    static int countOccurrences(String line, char c) {
        if (line == null || line.isEmpty()) return 0;
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == c) count++;
        }
        return count;
    }

    private static void debug(String msg) {
        System.out.println("[LangchainService] " + msg);
    }
}
