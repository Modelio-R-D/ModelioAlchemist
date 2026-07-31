package com.docaposte.modelioalchemist.tma;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.docaposte.modelioalchemist.langchain.impl.LangchainService;
import com.docaposte.modelioalchemist.langchain.impl.ModelioMcpAgent;
import com.docaposte.modelioalchemist.langchain.impl.PdfExtractor;
import com.docaposte.modelioalchemist.langchain.impl.PipelineProgressListener;
import com.docaposte.modelioalchemist.langchain.impl.PipelineMetrics;

/**
 * Pipeline spécialisé pour l'analyse des cahiers des charges TMA (Tierce Maintenance Applicative).
 * 
 * Contrairement au pipeline classique qui génère des modèles UML, ce pipeline se concentre sur :
 * - L'extraction exhaustive des exigences TMA (SLA, compétences, organisationnelles, techniques, etc.)
 * - La structuration des exigences selon la méthodologie TMA
 * - La création directe des requirements dans Modelio sans passage par PlantUML
 */
public class TmaPipelineRunner {
    
    private static final boolean DEBUG = true;
    
    private final LangchainService llm;
    private final ModelioMcpAgent mcp;
    
    public TmaPipelineRunner(LangchainService llm, ModelioMcpAgent mcp) {
        this.llm = llm;
        this.mcp = mcp;
    }
    
    private static void debug(String message) {
        if (DEBUG) {
            System.out.println("[TmaPipelineRunner] " + message);
        }
    }
    
    public void run(String pdfPath, String outputDirectory) throws Exception {
        run(pdfPath, outputDirectory, PipelineProgressListener.NONE);
    }

    /**
     * Exécute le pipeline d'analyse TMA complet - même pattern que Main.java
     */
    public void run(String pdfPath, String outputDirectory, PipelineProgressListener progress) throws Exception {
        if (progress == null) {
            progress = PipelineProgressListener.NONE;
        }
        PipelineMetrics metrics = new PipelineMetrics();
        metrics.setPipelineStartTime(System.currentTimeMillis());
        debug("🚀 Starting TMA requirements analysis pipeline");
        debug("📄 PDF: " + pdfPath);
        debug("📁 Output: " + outputDirectory);
        
        if (pdfPath == null || pdfPath.trim().isEmpty()) {
            throw new IllegalArgumentException("PDF path cannot be null or empty");
        }
        
        if (outputDirectory == null || outputDirectory.trim().isEmpty()) {
            throw new IllegalArgumentException("Output directory cannot be null or empty");
        }

        Path outDir = Paths.get(outputDirectory);
        Files.createDirectories(outDir);

        // Fixed number of stages reported to the UI: extract, clean, expert analysis,
        // structure requirements, create in Modelio, finalize.
        final int totalSteps = 6;
        int step = 0;
        
        try {            // Étape 1: Extraction du texte du PDF - MÊME MÉTHODE que PipelineRunner
            progress.onStep(++step, totalSteps, "progress.tma.extractText");
            long extractTextStartTime = System.currentTimeMillis();
            debug("📖 Step 1: Extracting raw text from PDF (same as main pipeline)...");
            String rawText;
            try {
                rawText = PdfExtractor.extractText(pdfPath);
                if (rawText == null || rawText.trim().isEmpty()) {
                    throw new IllegalArgumentException("No text could be extracted from PDF: " + pdfPath);
                }
                Files.writeString(outDir.resolve("extracted_raw_text.txt"), rawText);
                debug("✅ Raw text extracted: " + rawText.length() + " characters");
            } catch (Exception e) {
                String errorMsg = "❌ PDF extraction failed: " + e.getMessage();
                debug(errorMsg);
                Files.writeString(outDir.resolve("pdf_extraction_error.txt"), errorMsg + "\n\nStack trace:\n" + getStackTrace(e));
                throw new RuntimeException("Failed to extract text from PDF", e);
            } finally {
                metrics.recordStageTiming("extract_text", extractTextStartTime);
            }
            
            // Étape 2: Agent extracteur pour nettoyer le texte (même pattern que main pipeline)
            debug("🧹 Step 2: Cleaning extracted text with extractor agent...");
            progress.onStep(++step, totalSteps, "progress.tma.cleanText");
            long cleanTextStartTime = System.currentTimeMillis();
            String extractorPrompt = """
                Vous êtes un expert en extraction de documents techniques TMA (Tierce Maintenance Applicative). 
                Votre mission est d'extraire TOUTES les informations importantes du document, en particulier :
                
                1. TOUTES les exigences de niveau de service (SLA, délais, disponibilité)
                2. TOUTES les compétences techniques requises
                3. TOUTES les contraintes organisationnelles et humaines
                4. TOUS les aspects de performance et monitoring
                5. Toutes les prestations techniques demandées
                6. Tous les aspects de pilotage et reporting
                
                IMPORTANT: Ne résumez pas, ne filtrez pas, ne perdez aucune information.
                Conservez les détails techniques, les références, les normes mentionnées.
                Structurez le texte de manière claire mais gardez l'exhaustivité.
                
                Texte à traiter :
                """ + rawText;
                
            String extractedText;
            try {
                extractedText = llm.runPrompt(extractorPrompt, "");
                if (extractedText == null || extractedText.trim().isEmpty()) {
                    debug("⚠️ Warning: Text extraction returned empty result, using raw text");
                    extractedText = rawText; // Fallback to raw text
                }
                Files.writeString(outDir.resolve("extracted_cleaned_text.txt"), extractedText);
                debug("✅ Text cleaned: " + extractedText.length() + " characters");
            } catch (Exception e) {
                String errorMsg = "❌ Text cleaning failed: " + e.getMessage();
                debug(errorMsg);
                debug("🔄 Falling back to raw text");
                extractedText = rawText; // Fallback to raw text
                Files.writeString(outDir.resolve("text_cleaning_error.txt"), errorMsg + "\n\nStack trace:\n" + getStackTrace(e));
                Files.writeString(outDir.resolve("extracted_cleaned_text.txt"), extractedText);
            } finally {
                metrics.recordStageTiming("clean_text", cleanTextStartTime);
            }
            
            // Étape 3: Analyse spécialisée TMA avec le prompt expert
            debug("🔍 Step 3: TMA expert analysis...");
            progress.onStep(++step, totalSteps, "progress.tma.expertAnalysis");
            long expertAnalysisStartTime = System.currentTimeMillis();
            TmaRequirementsExtractor extractor = new TmaRequirementsExtractor();
            String tmaAnalysis;
            try {
                tmaAnalysis = extractor.analyzeTmaRequirements(extractedText, llm);
                if (tmaAnalysis == null || tmaAnalysis.trim().isEmpty()) {
                    throw new RuntimeException("TMA analysis returned empty result");
                }
                Files.writeString(outDir.resolve("tma_requirements_analysis.txt"), tmaAnalysis);
                debug("✅ TMA analysis completed: " + tmaAnalysis.length() + " characters");
            } catch (Exception e) {
                String errorMsg = "❌ TMA analysis failed: " + e.getMessage();
                debug(errorMsg);
                Files.writeString(outDir.resolve("tma_analysis_error.txt"), errorMsg + "\n\nStack trace:\n" + getStackTrace(e));
                throw new RuntimeException("Failed to analyze TMA requirements", e);
            } finally {
                metrics.recordStageTiming("expert_analysis", expertAnalysisStartTime);
            }
            
            // Étape 4: Structuration des exigences
            debug("📋 Step 4: Structuring TMA requirements...");
            progress.onStep(++step, totalSteps, "progress.tma.structureRequirements");
            long structureRequirementsStartTime = System.currentTimeMillis();
            String structuredRequirements;
            try {
                structuredRequirements = extractor.structureRequirements(tmaAnalysis, llm);
                if (structuredRequirements == null || structuredRequirements.trim().isEmpty()) {
                    throw new RuntimeException("Requirements structuring returned empty result");
                }
                Files.writeString(outDir.resolve("structured_tma_requirements.txt"), structuredRequirements);
                debug("✅ Requirements structured: " + structuredRequirements.length() + " characters");
            } catch (Exception e) {
                String errorMsg = "❌ Requirements structuring failed: " + e.getMessage();
                debug(errorMsg);
                Files.writeString(outDir.resolve("structuring_error.txt"), errorMsg + "\n\nStack trace:\n" + getStackTrace(e));
                throw new RuntimeException("Failed to structure TMA requirements", e);
            } finally {
                metrics.recordStageTiming("structure_requirements", structureRequirementsStartTime);
            }
            
            // Étape 5: Création des requirements dans Modelio via MCP
            debug("🏗️  Step 5: Creating requirements in Modelio...");
            progress.onStep(++step, totalSteps, "progress.tma.createRequirements");
            long createRequirementsStartTime = System.currentTimeMillis();
            try {
                // Validation avant envoi à Modelio
                if (structuredRequirements.trim().isEmpty()) {
                    throw new RuntimeException("No structured requirements available for Modelio creation");
                }
                
                // Créer un "pseudo-PlantUML" minimal pour déclencher la création des requirements
                String pseudoUml = "@startuml\nnote \"TMA Requirements Model\" as N1\n@enduml";
                
                debug("📤 Sending " + structuredRequirements.length() + " chars to Modelio MCP...");
                
                // Appel avec les requirements TMA structurés
                String mcpReport = mcp.generateModelFromPlantUMLWithRequirements(
                    pseudoUml, 
                    structuredRequirements, 
                    outputDirectory
                );
                
                if (mcpReport == null || mcpReport.trim().isEmpty()) {
                    debug("⚠️ Warning: MCP returned empty report");
                    mcpReport = "MCP execution completed but no detailed report was generated.";
                } else if (mcpReport.startsWith("❌") ||
                           mcpReport.startsWith("MCP_EXECUTION_FAILED:") ||
                           mcpReport.startsWith("[error:")) {
                    throw new RuntimeException(mcpReport);
                }
                
                Files.writeString(outDir.resolve("modelio_mcp_tma_report.txt"), mcpReport);
                debug("✅ TMA requirements created in Modelio. Report length: " + mcpReport.length());
                
            } catch (Exception e) {
                String errorMsg = "❌ MCP creation failed: " + e.getMessage();
                debug(errorMsg);
                Files.writeString(outDir.resolve("modelio_tma_error.txt"), errorMsg + "\n\nStack trace:\n" + getStackTrace(e));
                throw new RuntimeException("Failed to create TMA requirements in Modelio", e);
            } finally {
                metrics.recordStageTiming("create_requirements", createRequirementsStartTime);
            }
            
            debug("🎉 TMA pipeline completed successfully!");
            long finalizingStartTime = System.currentTimeMillis();
            progress.onStep(++step, totalSteps, "progress.tma.finalizing");
            metrics.recordStageTiming("finalizing", finalizingStartTime);
            System.out.println(metrics.buildTimingSummary());
            
        } catch (Exception e) {
            debug("❌ TMA pipeline failed: " + e.getMessage());
            Files.writeString(outDir.resolve("tma_pipeline_error.txt"), 
                    "TMA Pipeline Error: " + e.getMessage() + "\n\nStack trace:\n" + getStackTrace(e));
            throw e;
        }
    }
    
    /**
     * Utility method to get stack trace as string
     */
    private String getStackTrace(Exception e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        e.printStackTrace(pw);
        return sw.toString();
    }
}