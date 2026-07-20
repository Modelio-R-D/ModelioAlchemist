# ModelioAlchemist

Accelerate UML modeling in Modelio 6.1.1 by letting LangChain4j + Azure OpenAI ingest procurement PDFs, extract validated requirements, and drive the Modelio MCP server to build requirements, domain models, and use-case diagrams automatically.

## Key Capabilities
- **Document-to-model automation** – `PipelineRunner` parses PDF tenders with PDFBox, filters/classifies requirements with multi-stage prompts, and hands PlantUML + context to the MCP tooling pipeline.
- **Three-phase UML synthesis** – `LangchainService` orchestrates requirement creation, class modeling, and use-case generation through pooled `UmlModelingAssistant` instances so complex models stay consistent.
- **TMA specialization** – `TmaPipelineRunner` focuses on Tierce Maintenance Applicative dossiers, structuring SLA/competency requirements and pushing them straight into Modelio without PlantUML detours.
- **MCP-native execution** – `ModelioMcpAgent` uses the Model Context Protocol over SSE to call the same tools exposed by the Modelio MCP Server module, ensuring every element is created via official APIs.
- **Validation & diagnostics** – `RequirementsValidator` and `RequirementContextValidator` quantify coverage so you know when the LLM pipeline lost context, while rich logs/live reports land in the project-specific output folder.

## How It Works
1. **Trigger a command in Modelio** – `GenerateFromPdfCommand` or `GenerateExigencesForTMA` (see module GUI) asks you to pick a PDF and computes a project-scoped `modelioalchemist-output` (or `tma-analysis-output`) directory.
2. **AI pipeline** – `Main` wires `LangchainService` to Azure OpenAI (`PolicyAwareAzureChatModel`) and, if needed, the MCP client. It runs:
	- PDF extraction (`PdfExtractor`).
	- Requirement extraction + filtering + domain classification (`PipelineRunner`).
	- Validation (`RequirementsValidator`, `RequirementContextValidator`).
	- Domain/UML synthesis (multi-phase prompts in `LangchainService`).
3. **Model creation** – `ModelioMcpAgent` calls the MCP tools to create packages, requirements, classes, associations, actors, and use cases directly inside the open Modelio project.
4. **Artifacts** – Every stage stores trace files (JSON, text, PlantUML, reports) under the computed output folder for reproducibility.

## Repository Tour
- `src/main/java/com/docaposte/modelioalchemist/handlers` – Modelio command handlers that collect context and invoke the pipeline.
- `src/main/java/com/docaposte/modelioalchemist/langchain/impl` – Core LangChain4j integration, pooling logic, PDF tooling, validators, PlantUML utilities, and MCP bridge.
- `src/main/java/com/docaposte/modelioalchemist/tma` – TMA-focused extractor and runner.
- `documents/` – Sample cahiers des charges, processed extracts, and generated analyses you can reuse for tests.
- `assembly.xml` + `pom.xml` – Build descriptors that package the module into a `.jmdac` archive.

## Prerequisites
- **Modelio** 6.1.1 (module `binaryversion` in `module.xml`).
- **Java** 17 (configured via `maven-compiler-plugin`).
- **Maven** 3.9+.
- **Modelio MCP Server** module running in the same workspace (provides the Streamable HTTP endpoint consumed by `ModelioMcpAgent`).
- **Azure OpenAI access** (API gateway token or Azure AD token) compatible with LangChain4j 1.3.x.

## Build & Install
```bash
mvn clean package
```

The build copies all runtime dependencies to `target/lib/`, assembles `target/ModelioAlchemist_0.0.00.jmdac`, then renames the ZIP automatically. Install the module by copying the `.jmdac` into your Modelio `modules/` directory (or importing it through *Configuration ➜ Modules*).

## Configure AI & MCP Access
`Main` and `LangchainService` expect the following environment variables before you run the pipeline (from Modelio or the CLI):

| Variable | Required | Purpose |
| --- | --- | --- |
| `AZURE_OPENAI_AD_TOKEN` | ✅ | Subscription key or AAD token used by `HttpPolicies.auth()` to hit your Azure OpenAI / API Gateway endpoint. |
| `AZURE_OPENAI_BASE_URL` | ➖ (default `https://apigatewayinnovation.azure-api.net/openai-api/deployments/gpt-5-4-mini`) | Base URL passed to `AzureEndpointResolver`. |
| `AZURE_OPENAI_DEPLOYMENT` | ➖ | Deployment name when the base URL does not embed it (default: `gpt-5-4-mini`). |
| `MODELIO_MCP_URL` | ➖ (default `http://localhost:8083/mcp`) | Streamable HTTP endpoint exposed by the Modelio MCP server module. |
| `MODELIO_ALCHEMIST_HTTP_LOG_LEVEL` | ➖ (default `NONE`) | Azure HTTP log verbosity (`NONE`, `BASIC`, `HEADERS`, `BODY_AND_HEADERS`). Keep `NONE` for normal runs; increase only for troubleshooting. |

The module now targets Azure OpenAI API version `2026-03-17` and uses a default chat temperature of `1.0`.

These can be defined globally, via a startup script, or inside Modelio’s launcher `.bat`/`.sh` so that the module inherits them.

## Using the Module inside Modelio
1. **Install + activate** ModelioAlchemist in your project.
2. **Ensure the MCP server** module is running (the MCP status console should show `/mcp` ready).
3. **Configure Azure variables** in the environment used to launch Modelio.
4. **Open a project** and right-click any element (package, root, etc.) to access the module’s contextual commands:

### `Générer à partir d'un cahier de charge`
1. Choose a PDF when prompted.
2. The pipeline creates `modelioalchemist-output` inside the Modelio project directory (falls back to `%USERPROFILE%/modelioalchemist-output`).
3. Watch live status dialogs and check the output folder for:
	- `extracted_text.txt`, `extracted_agent_text.txt` – raw vs. curated text.
	- `filtered_requirements.json` – JSON that drives requirement creation, including precise origin metadata (`original_ref`, `source_location`, `source_quote`) used when creating Modelio requirements.
	- `classified.json`, `*_report.txt` – category-specific analyses (technique, rssi, fonctionnel, rse, ecoconception).
	- `requirements_validation.txt`, `context_validation.txt` – coverage diagnostics.
	- `uml_model_3phase_report.txt`, PlantUML exports, and MCP execution logs.

### `Générer les exigences pour AO TMA`
1. Pick a PDF or TXT TMA dossier.
2. Outputs land in `tma-analysis-output` under the current project (or `%USERPROFILE%`).
3. The `TmaPipelineRunner` writes:
	- `extracted_raw_text.txt`, `extracted_cleaned_text.txt`.
	- `tma_requirements_analysis.txt` (prompted extraction).
	- `structured_tma_requirements.txt` (ready-to-import format).
	- `modelio_mcp_tma_report.txt` (trace of requirement creation via MCP).

All elements created in Modelio remain linked to the current selection (packages, requirements folders, etc.) through the context computed in the handlers.

## CLI / Pipeline Usage
You can run the AI pipeline without Modelio to verify prompts or generate offline artifacts:

```bash
# After mvn clean package
java -cp "target/lib/*;target/modelioalchemist-0.0.00.jar" \
	  com.docaposte.modelioalchemist.langchain.impl.Main \
	  path/to/requirements.pdf
```

`Main.main()` uses the defaults listed earlier and writes to `output/`. `Main.mainWithOutputDir()` and `Main.tmaWithOutputDir()` are the entry points used by Modelio – you can call them from your own launcher if you need scripted batches.

## Output Artifacts
The pipeline consistently drops machine-readable traces so you can feed downstream QA steps or auditors:

- **Requirements JSON:** `filtered_requirements.json` plus category splits in `classified.json`.
- **Context & validation:** `requirements_validation.txt`, `context_validation.txt` with coverage stats and recommendations.
- **Generated diagrams:** PlantUML snippets for as-built domain and use-case diagrams, along with textual summaries in `*_report.txt`.
- **MCP execution logs:** `requirements_creation_report.txt`, `uml_model_3phase_report.txt`, `modelio_mcp_tma_report.txt` describing each tool invocation and returned UUIDs.

## Troubleshooting
- **Missing Azure token** – the pipeline stops early with “Please set AZURE_OPENAI_AD_TOKEN” (see `Main`). Export the env var before launching Modelio.
- **MCP connection failures** – `LangchainService` logs “Impossible de se connecter au serveur MCP Modelio”; verify the MCP server module is active and that `MODELIO_MCP_URL` points to the right host/port.
- **HTTP request/response logs are too verbose** – ensure `MODELIO_ALCHEMIST_HTTP_LOG_LEVEL` is unset (or set to `NONE`). Use `BASIC`/`HEADERS`/`BODY_AND_HEADERS` only for temporary diagnostics.
- **Empty requirement sets** – inspect `filtered_requirements.json` and `*_report.txt`; adjust the source PDF quality or tweak prompts in `PipelineRunner`/`TmaRequirementsExtractor` if your corpus uses different markers.
- **Output directory issues** – handlers fall back to `%USERPROFILE%` when they cannot resolve the project root. Ensure your Modelio project is writable if you rely on in-project folders.

## Contributing
- Keep code in English/French consistently (commands are localized, prompts are in French by design).
- Reuse the pooled `LangchainService` instead of instantiating new MCP clients.
- Add sample inputs/outputs under `documents/` so reviewers can replay scenarios.
- Run `mvn clean package` before submitting to ensure the `.jmdac` assembles correctly.
