package com.docaposte.modelioalchemist.langchain.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-pipeline-stage model configuration.
 *
 * Each stage can target a different Azure OpenAI deployment, allowing fast/cheap models for
 * extraction stages and high-capability models for agentic (MCP) stages.
 *
 * <p>Instances are built by the Modelio command handler from the module's parameter panel
 * (Configure module → "LLM Deployments per pipeline stage"). Leaving a field blank means
 * "use the default deployment".
 *
 * <p>Stage name constants:
 * <ul>
 *   <li>{@link #STAGE_EXTRACT}  — Stage 2: extractor agent
 *   <li>{@link #STAGE_FILTER}   — Stage 3: requirements filter
 *   <li>{@link #STAGE_CLASSIFY} — Stage 4: classifier
 *   <li>{@link #STAGE_DOMAIN}   — Stages 7-11: parallel domain analysis
 *   <li>{@link #STAGE_PLANTUML} — Stage 12: PlantUML generation
 *   <li>{@link #STAGE_MCP}      — Stages 13-14: MCP agentic (requirements + UML class model)
 * </ul>
 */
public final class StageModelConfig {

    public static final String STAGE_EXTRACT  = "extract";
    public static final String STAGE_FILTER   = "filter";
    public static final String STAGE_CLASSIFY = "classify";
    public static final String STAGE_DOMAIN   = "domain";
    public static final String STAGE_PLANTUML = "plantuml";
    public static final String STAGE_MCP      = "mcp";

    /** Module parameter IDs — must match module.xml. */
    public static final String PARAM_EXTRACT  = "deployment.stage.extract";
    public static final String PARAM_FILTER   = "deployment.stage.filter";
    public static final String PARAM_CLASSIFY = "deployment.stage.classify";
    public static final String PARAM_DOMAIN   = "deployment.stage.domain";
    public static final String PARAM_PLANTUML = "deployment.stage.plantuml";
    public static final String PARAM_MCP      = "deployment.stage.mcp";

    /**
     * Code-level defaults used when a module parameter has not been explicitly saved
     * (Modelio's defaultValue in module.xml is only a UI hint, not a runtime value).
     * These must stay in sync with the defaultValue attributes in module.xml.
     */
    private static final Map<String, String> STAGE_DEFAULTS;
    static {
        Map<String, String> m = new HashMap<>();
        m.put(STAGE_EXTRACT,  "gpt-5.4-mini");
        m.put(STAGE_FILTER,   "gpt-5.4-mini");
        m.put(STAGE_CLASSIFY, "gpt-5.2");
        m.put(STAGE_DOMAIN,   "gpt-5.4-mini");
        m.put(STAGE_PLANTUML, "gpt-5.2");
        m.put(STAGE_MCP,      "gpt-5.2");
        STAGE_DEFAULTS = Collections.unmodifiableMap(m);
    }

    private final Map<String, String> stageToDeployment;

    private StageModelConfig(Map<String, String> stageToDeployment) {
        this.stageToDeployment = stageToDeployment;
    }

    /**
     * Returns a config where every stage uses the global default deployment.
     */
    public static StageModelConfig defaults() {
        return new StageModelConfig(new HashMap<>());
    }

    /**
     * Builds a config from raw string values read from the Modelio module parameters.
     * Any value that is null or blank is treated as "use default".
     *
     * @param extract  deployment for the extractor stage (may be null/blank)
     * @param filter   deployment for the filter stage
     * @param classify deployment for the classifier stage
     * @param domain   deployment for the domain-analysis stages
     * @param plantuml deployment for the PlantUML-generation stage
     * @param mcp      deployment for the MCP agentic stages
     */
    public static StageModelConfig of(
            String extract, String filter, String classify,
            String domain, String plantuml, String mcp) {
        Map<String, String> map = new HashMap<>();
        put(map, STAGE_EXTRACT,  extract);
        put(map, STAGE_FILTER,   filter);
        put(map, STAGE_CLASSIFY, classify);
        put(map, STAGE_DOMAIN,   domain);
        put(map, STAGE_PLANTUML, plantuml);
        put(map, STAGE_MCP,      mcp);
        return new StageModelConfig(map);
    }

    private static void put(Map<String, String> map, String stage, String value) {
        if (value != null && !value.isBlank()) {
            map.put(stage, value.trim());
        }
    }

    /**
     * Returns the deployment name configured for {@code stageName}.
     * Lookup order:
     * <ol>
     *   <li>Explicit override from the module parameter panel (non-blank)
     *   <li>Code-level default for that stage (from {@code STAGE_DEFAULTS})
     *   <li>Global {@link OpenAiDefaults#DEPLOYMENT} as last resort
     * </ol>
     */
    public String deploymentFor(String stageName) {
        if (stageName == null) return OpenAiDefaults.DEPLOYMENT;
        String override = stageToDeployment.get(stageName);
        if (override != null && !override.isBlank()) return override;
        return STAGE_DEFAULTS.getOrDefault(stageName, OpenAiDefaults.DEPLOYMENT);
    }

    /** Returns a human-readable summary of the effective deployment per stage. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (String stage : new String[]{STAGE_EXTRACT, STAGE_FILTER, STAGE_CLASSIFY, STAGE_DOMAIN, STAGE_PLANTUML, STAGE_MCP}) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(stage).append("→").append(deploymentFor(stage));
        }
        return sb.toString();
    }
}
