package com.docaposte.modelioalchemist.langchain.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Orchestrates the pipeline: extract PDF text, run LLM prompts, classify, generate PlantUML and optionally
 * call MCP to create elements.
 */
public class PipelineRunner {

    private final LangchainService llm;
    private final ModelioMcpAgent mcp;
    private final ObjectMapper mapper = new ObjectMapper();
    private PipelineMetrics metrics;

    public PipelineRunner(LangchainService llm, ModelioMcpAgent mcp) {
        this.llm = llm;
        this.mcp = mcp;
    }

    public void run(String pdfPath) throws Exception {
        run(pdfPath, "output");
    }

    public void run(String pdfPath, String outputDirPath) throws Exception {
        run(pdfPath, outputDirPath, PipelineProgressListener.NONE);
    }

    /**
     * Enveloppe garantissant que les métriques sont écrites même quand le pipeline échoue :
     * c'est précisément lors d'un échec qu'elles sont les plus utiles au diagnostic.
     */
    public void run(String pdfPath, String outputDirPath, PipelineProgressListener progress) throws Exception {
        try {
            runPipeline(pdfPath, outputDirPath, progress);
        } finally {
            writeMetricsJson(outputDirPath, pdfPath);
        }
    }

    /**
     * Sérialise les métriques collectées. Ne propage jamais d'exception : l'écriture des métriques
     * ne doit pas masquer l'erreur d'origine du pipeline.
     */
    private void writeMetricsJson(String outputDirPath, String pdfPath) {
        if (metrics == null || outputDirPath == null) {
            return;
        }
        try {
            String metricsFileName = "pipeline_metrics_" + metricsSlugFor(pdfPath) + ".json";
            Path metricsDir = Path.of("metrics");
            Files.createDirectories(metricsDir);
            Path metricsPath = metricsDir.resolve(metricsFileName);
            Files.writeString(metricsPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(metrics.toJson()));
            System.out.println("📊 Pipeline metrics written to " + metricsPath);
        } catch (Exception e) {
            System.err.println("⚠️ Could not write pipeline metrics: " + e.getMessage());
        }
    }

    /**
     * Beaucoup de cahiers des charges partagent le même nom de fichier (ex. "CCTP.pdf") : on inclut
     * aussi le nom du dossier parent pour que le fichier de métriques reste identifiable sans l'ouvrir.
     */
    private static String metricsSlugFor(String pdfPath) {
        Path path = Path.of(pdfPath);
        String stem = path.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        Path parent = path.getParent();
        String parentName = parent != null ? parent.getFileName().toString() : null;
        String slug = (parentName != null && !parentName.isBlank()) ? parentName + "_" + stem : stem;
        return slug.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    /**
     * Chaque run repart d'un dossier de sortie vide : les fichiers de debug d'un run précédent
     * (traces MCP, prompts, rapports) ne doivent jamais se mélanger avec ceux du run courant.
     */
    private static void deleteDirectoryContents(Path dir) throws java.io.IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder())
                    .filter(p -> !p.equals(dir))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private void runPipeline(String pdfPath, String outputDirPath, PipelineProgressListener progress) throws Exception {
        if (progress == null) {
            progress = PipelineProgressListener.NONE;
        }
        
        // Initialize metrics
        metrics = new PipelineMetrics();
        long pipelineStartTime = System.currentTimeMillis();
        metrics.setPipelineStartTime(pipelineStartTime);
        
        System.out.println("🚀 [Pipeline] Starting pipeline for: " + pdfPath);
        System.out.println("🏗️ [Pipeline] Build timestamp: " + BuildInfo.describe());
        System.out.println("📁 [Pipeline] Output directory: " + outputDirPath);
        String sourceDocumentName = Path.of(pdfPath).getFileName().toString();
        metrics.setSourceDocument(sourceDocumentName);

        // Fixed number of high-level stages reported to the UI: extract, clean, filter, classify,
        // 2 validations, 5 domain analyses, PlantUML (generated directly from the domain reports,
        // skipping the intermediate prose "model description" relay), 2 MCP creations, finalize.
        final int totalSteps = 15;
        int step = 0;

        // 1) extract raw text
        progress.onStep(++step, totalSteps, "progress.pipeline.extractText");
        long extractStartTime = System.currentTimeMillis();
        String rawText = PdfExtractor.extractText(pdfPath);
        metrics.recordStageTiming("extraction", extractStartTime);
        Path outDir = Path.of(outputDirPath);
        deleteDirectoryContents(outDir);
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("extracted_text.txt"), rawText);
        System.out.println("✅ [Stage 1/15] PDF text extracted and saved.");

        // 2) extractor agent - prompt amélioré pour conserver toutes les exigences avec regroupement thématique
        String extractorPrompt = """
            Vous êtes un expert en extraction de documents d'appels d'offres et de cahiers des charges.
            Votre mission est d'extraire TOUTES les informations importantes du document en les regroupant par thèmes cohérents.
            
            STRUCTURE DE SORTIE ATTENDUE :
            
            === CONTEXTE ET OBJECTIFS ===
            - Objectif général du projet
            - Contexte métier et organisationnel
            - Enjeux et finalités
            
            === EXIGENCES FONCTIONNELLES MÉTIER ===
            - Fonctionnalités principales et secondaires
            - Processus métier à supporter
            - Règles de gestion
            - Interactions utilisateurs
            - Workflows et enchaînements
            
            === EXIGENCES TECHNIQUES ET ARCHITECTURE ===
            - Architecture système et composants
            - Technologies imposées ou recommandées
            - Interfaces et intégrations
            - Performances et volumétries
            - Contraintes d'infrastructure
            
            === EXIGENCES SÉCURITÉ ET RSSI ===
            - Authentification et autorisation
            - Chiffrement et protection des données
            - Audit et traçabilité
            - Conformité réglementaire
            - Gestion des risques
            
            === EXIGENCES QUALITÉ ET MÉTHODES ===
            - Tests et recettes
            - Documentation
            - Méthodes de développement
            - Assurance qualité
            - Livrables attendus
            
            === EXIGENCES RSE ET ÉCOCONCEPTION ===
            - Impact environnemental
            - Efficacité énergétique
            - Développement durable
            - Responsabilité sociale
            
            === CONTRAINTES PROJET ===
            - Délais et planning
            - Budget et coûts
            - Ressources humaines
            - Contraintes organisationnelles
            
            INSTRUCTIONS CRITIQUES :
            - Gardez TOUTE l'information sans résumer
            - Conservez les références exactes (ex: EX-001, article X.Y)
            - Maintenez le niveau de détail technique
            - Précisez le contexte pour chaque exigence
            - Identifiez les interdépendances entre exigences
            - Distinguez les exigences obligatoires des recommandations
            - TRAÇABILITÉ OBLIGATOIRE : le texte source contient des marqueurs "[PAGE n]" indiquant
              le numéro de page d'origine. Pour CHAQUE exigence ou information extraite, reportez
              entre parenthèses le numéro de page et, si présent, le titre de section/sous-section
              le plus proche (ex: "(page 14, section 3.2.1 - Authentification)"). Ne supprimez jamais
              ces indications de page/section, elles sont indispensables à la traçabilité.
            
            Texte à traiter (contient des marqueurs [PAGE n] à préserver dans vos annotations) :""";
        progress.onStep(++step, totalSteps, "progress.pipeline.extractorAgent");
        long extractorAgentStartTime = System.currentTimeMillis();
        String extracted = llm.runPrompt(extractorPrompt, rawText, StageModelConfig.STAGE_EXTRACT);
        metrics.recordStageTiming("extractor_agent", extractorAgentStartTime);
        Files.writeString(outDir.resolve("extracted_agent_text.txt"), extracted);
        System.out.println("✅ [Stage 2/15] Extractor agent output saved.");

        // 2.5) NOUVEAU : Requirements Filter Agent - Étape de pré-traitement intelligent
        String requirementsFilterPrompt = """
            Vous êtes un expert en analyse d'appels d'offres et spécialiste en ingénierie des exigences.
            Votre mission : identifier et contextualiser TOUTES les vraies exigences opérationnelles.
            
            APPROCHE INCLUSIVE : ACCEPTER LARGEMENT LES VRAIES EXIGENCES
            
            ✅ ACCEPTER PRIORITAIREMENT - Exigences explicites :
            - "Le système doit/devra/permet..." (capacités fonctionnelles)
            - "L'application/plateforme doit..." (contraintes techniques)
            - "Les données doivent/sont..." (règles de gestion)
            - "L'utilisateur doit/peut..." (fonctionnalités utilisateur)
            - "Il est obligatoire/requis/nécessaire..." (contraintes)
            
            ✅ ACCEPTER LARGEMENT - Contraintes et exigences diverses :
            - Contraintes de performance, délais, volumes
            - Standards, normes, certifications (RGPD, ISO, SecNumcloud)
            - Contraintes d'hébergement et d'infrastructure
            - Exigences de sécurité et authentification
            - Contraintes d'intégration et d'interopérabilité
            - Exigences de disponibilité et support
            - Règles métier et processus
            - Contraintes contractuelles et réglementaires
            
            ❌ REJETER UNIQUEMENT - Éléments vraiment non-opérationnels :
            - Titres de sections isolés ("1.2.3 Titre")
            - Textes de présentation pure ("Cette section décrit...")
            - Instructions de rédaction ("Le candidat doit répondre...")
            - Références bibliographiques seules
            - Numérotation et mise en forme pure
            
            ⚠️ TRAITER AVEC BIENVEILLANCE :
            - Transformer les descriptions en exigences quand c'est pertinent
            - Conserver les contraintes même si implicites
            - Garder les règles métier même générales
            - Préserver les critères de qualité et conformité
            - EN CAS DE DOUTE : CONSERVER plutôt que rejeter
            
            INSTRUCTIONS DE TRAITEMENT INCLUSIVES :
            1. PRINCIPE GÉNÉRAL : En cas de doute, conservez l'exigence
            2. Pour chaque élément d'information :
               - Si c'est une contrainte → CONSERVER
               - Si c'est une capacité demandée → CONSERVER  
               - Si c'est une règle ou obligation → CONSERVER
               - Reformulez clairement si nécessaire
               - Conservez TOUJOURS les références (EX-XXX)
              - Extrayez l'origine exacte dans le document (section, sous-section, page, citation verbatim)
            3. Objectif : taux de rétention 40-60% (pas 15%)
            4. Préférez l'inclusion à l'exclusion pour préserver l'information
            
            TRAÇABILITÉ OBLIGATOIRE (champ "source_location") :
            - Le texte à analyser contient des annotations de page/section héritées du document
              source (ex: "(page 14, section 3.2.1 - Authentification)" ou des marqueurs "[PAGE n]").
            - Pour CHAQUE exigence retenue, remplissez "source_location" avec le numéro de page et,
              si disponible, la section/sous-section exacte (ex: "Section 3.2.1 - Authentification, page 14").
            - Si seule la page est identifiable, indiquez au minimum "page 14".
            - N'inventez jamais un numéro de page : si aucune indication n'est présente dans le texte
              fourni, laissez le champ vide plutôt que d'inventer une valeur.
            - Remplissez aussi "source_quote" avec la citation verbatim la plus proche du texte source.
            
            FORMAT DE SORTIE - JSON structuré :
            {
              "filtered_requirements": [
                {
                  "id": "REQ-001",
                  "original_ref": "EX-015",
                  "description": "Le système doit permettre l'authentification des utilisateurs via SSO avec les comptes Active Directory de l'organisation",
                  "category": "Sécurité",
                  "priority": "Haute",
                  "context": "Gestion des accès utilisateurs",
                  "source_location": "Section 3.2.1 - Authentification, page 14",
                  "source_quote": "Le soumissionnaire devra proposer un mécanisme SSO interfacé avec l'Active Directory.",
                  "business_impact": "Critique pour la sécurité",
                  "dependencies": ["REQ-002"]
                }
              ],
              "contextual_insights": {
                "domain_specific_rules": [],
                "regulatory_constraints": [],
                "integration_requirements": []
              },
              "rejected_items": [],
              "statistics": {
                "total_items_analyzed": 0,
                "requirements_retained": 0,
                "items_rejected": 0,
                "requirements_by_category": {}
              }
            }
            
            Texte structuré à analyser :
            """;
        
        progress.onStep(++step, totalSteps, "progress.pipeline.filterRequirements");
        long filterStartTime = System.currentTimeMillis();
        String filteredRequirements = llm.runPrompt(requirementsFilterPrompt, extracted, StageModelConfig.STAGE_FILTER);
        
        // Extraire le JSON de la réponse
        String filteredJson = JsonUtils.extractFirstJson(filteredRequirements);
        if (filteredJson == null) {
            filteredJson = filteredRequirements;
        }

        // Filet de sécurité déterministe : le LLM ne renseigne pas toujours "source_location"
        // (ex: ne recopie pas correctement les marqueurs [PAGE n]). On complète ici en recherchant
        // directement la référence/citation de chaque exigence dans le texte brut du PDF (rawText),
        // qui contient les marqueurs [PAGE n] insérés par PdfExtractor. Ceci garantit une traçabilité
        // page-exacte même quand le LLM omet ou invente l'information.
        SourceLocationEnrichmentResult enrichResult = enrichMissingSourceLocations(filteredJson, rawText);
        filteredJson = enrichResult.enrichedJson;
        metrics.recordStageTiming("filtering", filterStartTime);
        
        Files.writeString(outDir.resolve("filtered_requirements.json"), filteredJson);
        System.out.println("✅ [Stage 3/15] Requirements filter output saved.");

        // Valider et rapporter les statistiques de filtrage
        int filterTotal = 0, filterRetained = 0, filterRejected = 0;
        try {
            JsonNode filterResults = mapper.readTree(filteredJson);
            if (filterResults.has("statistics")) {
                JsonNode stats = filterResults.get("statistics");
                filterTotal = stats.path("total_items_analyzed").asInt(0);
                filterRetained = stats.path("requirements_retained").asInt(0);
                filterRejected = stats.path("items_rejected").asInt(0);
            } else if (filterResults.has("filtered_requirements") && filterResults.get("filtered_requirements").isArray()) {
                // Le LLM a parfois omis le bloc "statistics" entièrement (observé sur des runs réels),
                // ce qui faisait disparaître silencieusement le bloc "filtering" du JSON de sortie.
                // On dérive un total de repli depuis le tableau réellement produit plutôt que de
                // laisser le bloc absent.
                System.err.println("⚠️ 'statistics' block missing from filter output; deriving from filtered_requirements array");
                filterRetained = filterResults.get("filtered_requirements").size();
                filterTotal = filterRetained;
            }

            // Le LLM rapporte parfois total_items_analyzed=0 alors que retained/rejected sont
            // cohérents entre eux : dériver le total plutôt que publier un taux de rétention absent.
            if (filterTotal <= 0 && (filterRetained + filterRejected) > 0) {
                System.err.println("⚠️ total_items_analyzed reported as " + filterTotal
                        + " but retained/rejected are non-zero; deriving total_items_analyzed from their sum");
                filterTotal = filterRetained + filterRejected;
            } else if (filterTotal > 0 && filterTotal != filterRetained + filterRejected) {
                // Le LLM rapporte parfois un total_items_analyzed incohérent avec ses propres
                // retained/rejected (ex. observé : total=50, retained=50, rejected=1 — 50+1≠50).
                // Les deux comptes détaillés (retained, rejected) sont plus fiables que le total
                // résumé, car ce sont eux qui alimentent directement le JSON produit ; on les traite
                // comme la source de vérité plutôt que de publier trois chiffres qui ne s'additionnent
                // pas entre eux.
                System.err.println("⚠️ total_items_analyzed=" + filterTotal + " is inconsistent with retained(" + filterRetained
                        + ") + rejected(" + filterRejected + ") = " + (filterRetained + filterRejected)
                        + "; overriding total_items_analyzed with the sum");
                filterTotal = filterRetained + filterRejected;
            }

            System.out.println("📊 Requirements filtering statistics:");
            System.out.println("   - Total items analyzed: " + filterTotal);
            System.out.println("   - True requirements retained: " + filterRetained);
            System.out.println("   - False positives rejected: " + filterRejected);
            System.out.println("   - Retention rate: " + (filterTotal > 0 ? (filterRetained * 100 / filterTotal) : 0) + "%");

            // Record filtering metrics
            metrics.setFilteringMetrics(filterTotal, filterRetained, filterRejected);
        } catch (Exception e) {
            System.err.println("⚠️ Could not parse filter statistics: " + e.getMessage());
        }
        
        // Record source location metrics
        metrics.setSourceLocationMetrics(enrichResult.fromLlm, enrichResult.fromFallback, enrichResult.stillMissing);

        // 3) classifier agent - utilise maintenant les exigences FILTRÉES
        String classifierPrompt = """
            Vous êtes un architecte système expert en classification d'exigences pour appels d'offres.
            Votre mission : organiser les exigences par domaines d'expertise tout en préservant les contextes métier.
            
            CATÉGORIES DE CLASSIFICATION :
            
            "technique" - Infrastructure et Architecture :
            - Architecture système, patterns, composants
            - Technologies imposées, frameworks, langages
            - Performances, volumétries, scalabilité
            - Intégrations, APIs, protocoles, formats
            - Infrastructure, déploiement, monitoring
            - Contraintes techniques spécifiques
            
            "rssi" - Sécurité et Conformité :
            - Authentification, autorisation, SSO
            - Chiffrement, protection des données
            - Audit, traçabilité, logs de sécurité
            - Conformité RGPD, normes sécuritaires
            - Gestion des risques, plan de continuité
            - Contrôles d'accès et habilitations
            
            "fonctionnel" - Métier et Processus :
            - Fonctionnalités principales et secondaires
            - Processus métier, workflows, validation
            - Règles de gestion spécifiques au domaine
            - Interface utilisateur, ergonomie
            - Gestion des données métier
            - Cas d'usage et scénarios opérationnels
            
            "rse" - Gouvernance et Responsabilité :
            - Transparence, traçabilité des décisions
            - Éthique, protection des utilisateurs
            - Accessibilité, inclusion numérique
            - Gouvernance des données
            - Impact social, responsabilité
            
            "ecoconception" - Durabilité et Efficacité :
            - Efficacité énergétique, optimisation
            - Empreinte carbone, impact environnemental
            - Réutilisabilité, modularité
            - Optimisation des ressources
            - Durée de vie, maintenabilité
            
            RÈGLES DE CLASSIFICATION :
            1. Analysez le contexte métier de chaque exigence
            2. Identifiez la catégorie PRINCIPALE (impact majeur)
            3. Si impact significatif sur plusieurs catégories → dupliquer avec adaptation contextuelle
            4. Conservez les références originales (REQ-XXX, EX-XXX)
            5. Enrichissez avec le contexte métier spécifique
            6. Regroupez les exigences connexes logiquement
            
            FORMAT DE SORTIE - JSON enrichi :
            {
              "technique": [
                {
                  "requirement_id": "REQ-001",
                  "original_ref": "EX-015",
                  "description": "texte complet avec contexte",
                  "technical_domain": "architecture|performance|integration|infrastructure",
                  "business_context": "contexte métier spécifique"
                }
              ],
              "rssi": [...],
              "fonctionnel": [...],
              "rse": [...],
              "ecoconception": [...],
              "cross_category_links": [
                {"source": "REQ-001", "target": "REQ-005", "relationship": "dependency"}
              ]
            }
            
            Exigences à classifier avec enrichissement contextuel :
            """;
        progress.onStep(++step, totalSteps, "progress.pipeline.classifyRequirements");
        long classifyStartTime = System.currentTimeMillis();
        String classifiedJson = classifyRequirementsChunked(classifierPrompt, filteredJson, outDir);
        metrics.recordStageTiming("classification", classifyStartTime);

        Files.writeString(outDir.resolve("classified.json"), classifiedJson);
        System.out.println("✅ [Stage 4/15] Classifier output saved.");

        // Validation de l'exhaustivité des exigences.
        // La référence est `filteredJson` : c'est l'étape de filtrage qui attribue les identifiants
        // EX-XXX. Le texte de l'agent d'extraction n'en contient aucun, la comparaison y était donc
        // vide des deux côtés et la validation réussissait sans rien vérifier.
        progress.onStep(++step, totalSteps, "progress.pipeline.validateCompleteness");
        long validateCompletenessStartTime = System.currentTimeMillis();
        RequirementsValidator.ValidationResult validation =
            RequirementsValidator.validateClassification(filteredJson, classifiedJson);
        metrics.recordStageTiming("validate_completeness", validateCompletenessStartTime);
        
        String validationReport = RequirementsValidator.generateValidationReport(validation);
        Files.writeString(outDir.resolve("requirements_validation.txt"), validationReport);
        System.out.println("🧪 [Stage 5/15] Requirements validation: " + (validation.isValid ? "✅ PASSED" : "❌ FAILED"));
        
        // Record classification validation metrics
        metrics.setClassificationValidationMetrics(
            validation.extractedCount, 
            validation.classifiedCount,
            validation.missingRequirements.size(),
            validation.extraRequirements.size(),
            validation.isValid
        );
        
        // NOUVEAU : Validation de la contextualisation enrichie
        progress.onStep(++step, totalSteps, "progress.pipeline.validateContext");
        long validateContextStartTime = System.currentTimeMillis();
        RequirementContextValidator.ContextValidationResult contextValidation = 
            RequirementContextValidator.validateContextualization(extracted, classifiedJson);
        metrics.recordStageTiming("validate_context", validateContextStartTime);
        
        String contextValidationReport = RequirementContextValidator.generateContextValidationReport(contextValidation);
        Files.writeString(outDir.resolve("context_validation.txt"), contextValidationReport);
        System.out.println("🧪 [Stage 6/15] Context validation: " + (contextValidation.isValid ? "✅ PASSED" : "⚠️ NEEDS ATTENTION"));
        
        // Record context validation metrics
        metrics.setContextValidationMetrics(
            contextValidation.totalRequirements,
            contextValidation.contextualizedRequirements,
            contextValidation.categoryDistribution,
            contextValidation.missingReferences.size()
        );
        
        if (!validation.isValid) {
            System.err.println("⚠️  WARNING: Some requirements may have been lost during classification!");
            System.err.println("Missing: " + validation.missingRequirements.size() + " requirements");
        }
        
        if (!contextValidation.isValid) {
            System.err.println("⚠️  WARNING: Some requirements lack proper contextualization!");
            System.err.println("Missing contexts: " + contextValidation.missingContexts.size());
            System.err.println("Contextualization rate: " + 
                (contextValidation.totalRequirements > 0 ? 
                    (contextValidation.contextualizedRequirements * 100 / contextValidation.totalRequirements) : 0) + "%");
        }

        // parse JSON
        JsonNode root = mapper.readTree(classifiedJson);

        // Stocker tous les rapports d'agents pour les inclure dans les requirements
        StringBuilder allAgentReports = new StringBuilder();
        String plantUMLContent = "";

        String[] domainKeys = {"technique", "rssi", "fonctionnel", "rse", "ecoconception"};

        // Construire le contexte de chaque domaine d'abord (opération locale, rapide) afin de
        // pouvoir lancer les appels LLM indépendants en parallèle juste après. Ces 5 analyses ne
        // dépendent pas les unes des autres : les exécuter séquentiellement ne faisait qu'ajouter
        // 4 allers-retours réseau inutiles au temps total du pipeline.
        Map<String, String> domainContexts = new LinkedHashMap<>();
        for (String key : domainKeys) {
            StringBuilder ctx = new StringBuilder();
            if (root.has(key) && root.get(key) != null) {
                JsonNode categoryNode = root.get(key);
                if (!categoryNode.isNull() && categoryNode.isArray()) {
                    // Nouveau format enrichi : extraire les descriptions des objets
                    for (JsonNode reqNode : categoryNode) {
                        if (reqNode.has("description")) {
                            String reqId = reqNode.has("requirement_id") ? reqNode.get("requirement_id").asText() : "REQ-UNK";
                            String originalRef = reqNode.has("original_ref") ? reqNode.get("original_ref").asText() : "";
                            String description = reqNode.get("description").asText();
                            String businessContext = reqNode.has("business_context") ? reqNode.get("business_context").asText() : "";
                            
                            // Reconstituer au format attendu par les prompts suivants
                            ctx.append(reqId);
                            if (!originalRef.isEmpty()) {
                                ctx.append(" (").append(originalRef).append(")");
                            }
                            ctx.append(": ").append(description);
                            if (!businessContext.isEmpty()) {
                                ctx.append(" [Contexte: ").append(businessContext).append("]");
                            }
                            ctx.append("\n");
                        }
                    }
                } else if (categoryNode.isArray()) {
                    // Format ancien (tableau de strings) : fallback
                    for (JsonNode item : categoryNode) {
                        ctx.append(item.asText()).append("\n");
                    }
                }
            }
            domainContexts.put(key, ctx.toString());
        }

        Map<String, String> domainReports = new LinkedHashMap<>();
        AtomicInteger stepCounter = new AtomicInteger(step);
        long domainAnalysisStartTime = System.currentTimeMillis();
        ExecutorService domainAnalysisExecutor = Executors.newFixedThreadPool(domainKeys.length);
        try {
            // First pass: identify which domains have content
            List<String> domainsWithContent = new ArrayList<>();
            for (String key : domainKeys) {
                String ctx = domainContexts.get(key);
                if (!ctx.trim().isEmpty()) {
                    domainsWithContent.add(key);
                }
            }

            // Report all domain analyses that will run in parallel
            if (!domainsWithContent.isEmpty()) {
                String[] parallelStageKeys = new String[domainsWithContent.size()];
                for (int i = 0; i < domainsWithContent.size(); i++) {
                    parallelStageKeys[i] = "progress.pipeline.analyze." + domainsWithContent.get(i);
                }
                int parallelStartStep = stepCounter.get() + 1;
                progress.onParallelSteps(parallelStartStep, totalSteps, parallelStageKeys);
                // Advance the counter by the number of parallel stages
                for (int i = 0; i < domainsWithContent.size(); i++) {
                    stepCounter.incrementAndGet();
                }
            }

            Map<String, Future<String>> futures = new LinkedHashMap<>();
            for (String key : domainKeys) {
                String ctx = domainContexts.get(key);
                if (ctx.trim().isEmpty()) {
                    System.out.println("⏭️ [Domain Analysis] Skipping " + key + " - no content available");
                    Files.writeString(outDir.resolve(key + "_report.txt"), "No content available for category: " + key);
                    continue;
                }

                // Prompt amélioré pour l'analyse par catégorie avec contextualisation métier
                String agentPrompt = """
                    Vous êtes un expert en %s pour projets d'appels d'offres publics et privés.
                    
                    MISSION : Analysez exhaustivement les exigences de votre domaine en préservant le contexte métier.
                    
                    STRUCTURE D'ANALYSE ATTENDUE :
                    
                    === INVENTAIRE CONTEXTUALISÉ ===
                    Pour chaque exigence :
                    - Référence et ID (REQ-XXX, EX-XXX)
                    - Contexte métier spécifique
                    - Impact sur l'architecture globale
                    - Niveau de criticité business
                    - Interdépendances identifiées
                    
                    === REGROUPEMENT THÉMATIQUE ===
                    Organisez les exigences par sous-domaines cohérents :
                    %s
                    
                    === ANALYSE D'IMPACT SYSTÈME ===
                    - Impact sur les autres domaines (transversalité)
                    - Contraintes techniques induites
                    - Risques de non-conformité
                    - Complexité de mise en œuvre
                    
                    === RECOMMANDATIONS OPÉRATIONNELLES ===
                    - Solutions techniques concrètes
                    - Bonnes pratiques du domaine
                    - Standards et normes applicables
                    - Méthodes de validation et test
                    
                    === POINTS DE VIGILANCE PROJET ===
                    - Risques d'oubli ou de perte d'exigences
                    - Éléments nécessitant éclaircissement
                    - Contraintes d'intégration avec l'existant
                    - Planning et ressources nécessaires
                    
                    CRITÈRES DE QUALITÉ :
                    - ❌ Aucune exigence ne doit être omise
                    - ✅ Préservez la traçabilité (références originales)
                    - ✅ Contextualisez chaque exigence dans son domaine métier
                    - ✅ Identifiez les liens avec les autres domaines
                    - ✅ Proposez des solutions concrètes et éprouvées
                    
                    Exigences à analyser pour le domaine %s :
                    """.formatted(
                        getDomainExpertise(key), 
                        getDomainSubCategories(key),
                        key.toUpperCase()
                    );

                Callable<String> task = () -> llm.runPrompt(agentPrompt, ctx, StageModelConfig.STAGE_DOMAIN);
                futures.put(key, domainAnalysisExecutor.submit(task));
            }

            for (Map.Entry<String, Future<String>> entry : futures.entrySet()) {
                String key = entry.getKey();
                String report = entry.getValue().get();
                Files.writeString(outDir.resolve(key + "_report.txt"), report);
                System.out.println("✅ [Domain Analysis] Saved report for " + key + " (" + domainContexts.get(key).split("\n").length + " requirements processed)");
                domainReports.put(key, report);
            }
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        } finally {
            domainAnalysisExecutor.shutdown();
        }
        metrics.recordStageTiming("domain_analysis", domainAnalysisStartTime);
        step = stepCounter.get();

        // Collecter tous les rapports pour les requirements, dans l'ordre stable des domaines.
        for (String key : domainKeys) {
            String report = domainReports.get(key);
            if (report == null) {
                continue;
            }
            allAgentReports.append("=== ").append(key.toUpperCase()).append(" ANALYSIS ===").append("\n");
            allAgentReports.append(report).append("\n\n");
        }
        
        // Générer le PlantUML directement à partir de TOUS les rapports d'agents.
        // NOTE : on ne passe plus par une étape intermédiaire de "description du modèle en prose".
        // Le PlantUML et la création des requirements Modelio sont indépendants, donc on les lance
        // en parallèle pour réduire la latence globale.
        if (allAgentReports.length() > 0) {
            System.out.println("🧩 [Stages 12-14/15] Generating PlantUML and Modelio requirements in parallel...");
            progress.onParallelSteps(step + 1, totalSteps,
                "progress.pipeline.plantuml",
                "progress.pipeline.createRequirements");

            ExecutorService modelCreationExecutor = Executors.newFixedThreadPool(2);
            try {
                // Préparer les documents d'analyse pour le parsing des requirements
                StringBuilder requirementsDocuments = new StringBuilder();
                requirementsDocuments.append("=== EXTRACTED REQUIREMENTS ===\n");
                requirementsDocuments.append(extracted).append("\n\n");
                requirementsDocuments.append(allAgentReports);

                System.out.println("📋 Requirements documents assembled from ALL agents:");
                System.out.println("   - Original extracted text");
                System.out.println("   - Technical analysis report");
                System.out.println("   - RSSI security report");
                System.out.println("   - Functional analysis report");
                System.out.println("   - RSE responsibility report");
                System.out.println("   - Ecoconception sustainability report");
                System.out.println("   Total content length: " + requirementsDocuments.length() + " characters");

                final String requirementsJsonForModelio = filteredJson;
                // Ces deux étapes tournent en parallèle : chacune se chronomètre dans son propre
                // thread, sinon toutes deux mesureraient la durée du bloc entier et seraient
                // identiques (et le total par étape dépasserait le total du pipeline).
                final java.util.concurrent.atomic.AtomicLong plantumlDurationMs = new java.util.concurrent.atomic.AtomicLong();
                final java.util.concurrent.atomic.AtomicLong createRequirementsDurationMs = new java.util.concurrent.atomic.AtomicLong();

                Future<String> plantUmlFuture = modelCreationExecutor.submit(() -> {
                    long stageStart = System.currentTimeMillis();
                    System.out.println("🧩 [Stage 12/15] Generating PlantUML directly from all agent reports...");
                    String pumlPrompt = """
                        Vous êtes un expert PlantUML spécialisé dans les architectures système complètes.
                        Générez PLUSIEURS diagrammes UML COMPLETS : classes, use cases et séquences.
                        
                        STRUCTURE OBLIGATOIRE - Générez EXACTEMENT ce format :
                        
                        1. DIAGRAMME DE CLASSES (obligatoire) :
                        @startuml Classes
                        !theme plain
                        
                        package "Business" {
                          class NomExact {
                            +attributSpecifiqueA: String
                            +attributSpecifiqueB: int
                            +methodeSpecifique()
                          }
                        }
                        
                        package "Technical" {
                          class NomServiceExact {
                            +operationSpecifiqueDuService()
                          }
                        }

                        package "Securite" {
                          class NomServiceSecuriteExact {
                            +operationSpecifiqueDeSecurite(): boolean
                          }
                        }
                        
                        ' RELATIONS OBLIGATOIRES avec cardinalités
                        Business.ClasseA "1" --> "0..*" Business.ClasseB : "gère"
                        Business.ClasseC *-- Business.ClasseD : "contient"
                        Technical.ServiceClass --> Business.ClasseA : "utilise"
                        @enduml
                        
                        2. DIAGRAMME DE USE CASES (obligatoire) :
                        @startuml UseCases
                        !theme plain
                        
                        actor "Utilisateur" as User
                        actor "Administrateur" as Admin
                        actor "Système Externe" as ExtSys
                        
                        rectangle "Système de Gestion" {
                          usecase "Gérer candidatures" as UC1
                          usecase "Suivre projets" as UC2
                          usecase "Générer rapports" as UC3
                          usecase "Administrer système" as UC4
                          usecase "Authentifier utilisateur" as UC5
                        }
                        
                        User --> UC1 : "soumet"
                        User --> UC2 : "consulte"
                        Admin --> UC3 : "génère"
                        Admin --> UC4 : "configure"
                        UC1 --> UC5 : "<<include>>"
                        UC2 --> UC5 : "<<include>>"
                        @enduml
                        
                        🚫 INTERDIT : les blocs ci-dessus (`NomExact`, `NomServiceExact`, `attributSpecifiqueA/B`,
                        `operationSpecifiqueDuService`, etc.) sont des exemples de SYNTAXE uniquement, pas des modèles à recopier.
                        Chaque classe DOIT avoir ses propres attributs et opérations, déduits de son rôle métier réel dans les
                        rapports fournis (ex. une classe "Dossier" a des attributs de dossier, une classe "Utilisateur" a des
                        attributs d'utilisateur — jamais le même jeu d'attributs ou d'opérations recopié tel quel sur plusieurs
                        classes). Si deux classes différentes se retrouvent avec des attributs ou opérations identiques, c'est un
                        signe d'erreur : reprenez les rapports pour identifier ce qui est propre à chacune.

                        RÈGLES STRICTES TYPES MODELIO :
                        - Utilisez UNIQUEMENT : String, int, boolean, float
                        - JAMAIS : Date, Integer, Boolean, LocalDate (incompatibles Modelio)
                        - Pour dates : utilisez String
                        - Pour nombres : utilisez int ou float
                        - Pour identifiants : utilisez int
                        
                        RÈGLES RELATIONS OBLIGATOIRES :
                        - Créez TOUJOURS des associations entre classes liées
                        - Utilisez les cardinalités (1, 0..1, 0..*, 1..*)
                        - Nommez les relations ("gère", "contient", "utilise")
                        - Modélisez l'héritage avec <|--
                        - Modélisez la composition avec *--
                        - Modélisez l'agrégation avec o--
                        
                        OBLIGATIONS USE CASES :
                        - Identifiez TOUS les acteurs du système
                        - Créez des use cases pour chaque fonctionnalité
                        - Utilisez <<include>> pour les dépendances
                        - Utilisez <<extend>> pour les cas optionnels
                        
                        IMPORTANT - LES RAPPORTS SUIVANTS SONT VOTRE SEULE SOURCE :
                        - Ils couvrent les domaines fonctionnel, technique, sécurité (RSSI), RSE et éco-conception
                        - Intégrez TOUTES les analyses (ne perdez aucune information, aucune classe/acteur/cas d'usage identifié)
                        - Conservez la traçabilité avec les références EX-XXX / REQ-XXX
                        - Organisez les classes en packages cohérents (métier, technique, sécurité, transverse)
                        - Soyez précis sur les noms (ils deviendront les noms des classes UML)
                        
                        Tous les rapports d'analyse à transformer directement en PlantUML :
                        """;
                    String puml = llm.runPrompt(pumlPrompt, allAgentReports.toString(), StageModelConfig.STAGE_PLANTUML);
                    Files.writeString(outDir.resolve("modele_donnees.puml"), puml);
                    System.out.println("✅ [Stage 12/15] PlantUML generated from all agent reports.");
                    plantumlDurationMs.set(System.currentTimeMillis() - stageStart);
                    return puml;
                });

                Future<String> requirementsFuture = modelCreationExecutor.submit(() -> {
                    long stageStart = System.currentTimeMillis();
                    System.out.println("🗺️ [Stage 13/15] Creating requirements in Modelio...");
                    String requirementsReport = mcp.createRequirementsInModelio(requirementsJsonForModelio, outDir.toString(), sourceDocumentName);
                    Files.writeString(outDir.resolve("modelio_mcp_requirements_report.txt"), requirementsReport);
                    if (requirementsReport == null || requirementsReport.trim().isEmpty() ||
                        requirementsReport.startsWith("❌") ||
                        requirementsReport.startsWith("MCP_EXECUTION_FAILED:") ||
                        requirementsReport.startsWith("[error:")) {
                        throw new IllegalStateException("Requirements creation did not execute successfully via MCP.\n" + requirementsReport);
                    }
                    System.out.println("✅ [Stage 13/15] Requirements created in Modelio.");
                    createRequirementsDurationMs.set(System.currentTimeMillis() - stageStart);
                    return requirementsReport;
                });

                String puml = null;
                String requirementsReport = null;
                Throwable plantUmlFailure = null;
                Throwable requirementsFailure = null;
                try {
                    puml = plantUmlFuture.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    plantUmlFailure = e;
                } catch (java.util.concurrent.ExecutionException e) {
                    plantUmlFailure = e.getCause() != null ? e.getCause() : e;
                }

                try {
                    requirementsReport = requirementsFuture.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    requirementsFailure = e;
                } catch (java.util.concurrent.ExecutionException e) {
                    requirementsFailure = e.getCause() != null ? e.getCause() : e;
                }

                metrics.recordStageDuration("plantuml", plantumlDurationMs.get());
                metrics.recordStageDuration("create_requirements", createRequirementsDurationMs.get());

                if (plantUmlFailure != null || requirementsFailure != null) {
                    Throwable failure = plantUmlFailure != null ? plantUmlFailure : requirementsFailure;
                    if (plantUmlFailure != null && requirementsFailure != null && plantUmlFailure != requirementsFailure) {
                        failure.addSuppressed(requirementsFailure);
                    }
                    if (failure instanceof Exception ex) {
                        throw ex;
                    }
                    throw new RuntimeException(failure);
                }

                plantUMLContent = puml;
                step += 2;

                System.out.println("🏠 Step 3: Creating UML class model in Modelio from PlantUML...");
                progress.onStep(++step, totalSteps, "progress.pipeline.createClassModel");
                long createClassModelStartTime = System.currentTimeMillis();
                String classModelReport = mcp.createUmlClassModel(plantUMLContent, requirementsReport, outDir.toString());
                metrics.recordStageTiming("create_class_model", createClassModelStartTime);
                Files.writeString(outDir.resolve("modelio_mcp_classmodel_report.txt"), classModelReport);
                if (classModelReport == null || classModelReport.trim().isEmpty() ||
                    classModelReport.startsWith("❌") ||
                    classModelReport.startsWith("MCP_EXECUTION_FAILED:") ||
                    classModelReport.startsWith("[error:")) {
                    throw new IllegalStateException("Class model creation did not execute successfully via MCP.\n" + classModelReport);
                }
                System.out.println("✅ [Stage 14/15] UML class model created in Modelio from PlantUML.");

                StringBuilder finalSummary = new StringBuilder();
                finalSummary.append("=== MODELIO MCP CREATION SUMMARY ===\n\n");
                finalSummary.append("1. MCP REQUIREMENTS CREATION:\n").append(requirementsReport).append("\n\n");
                finalSummary.append("2. MCP UML CLASS MODEL CREATION:\n").append(classModelReport).append("\n\n");
                finalSummary.append("=== END MCP SUMMARY ===\n");

                Files.writeString(outDir.resolve("modelio_mcp_creation_summary.txt"), finalSummary.toString());
                System.out.println("📋 Final MCP summary saved to modelio_mcp_creation_summary.txt");

                parseMcpMetrics(requirementsReport, classModelReport, outDir.toString());
            } catch (Exception e) {
                String errorMsg = "MCP failed: " + e.getMessage();
                System.err.println(errorMsg);
                Files.writeString(outDir.resolve("modelio_mcp_error.txt"), errorMsg);
                throw new IllegalStateException(errorMsg, e);
            } finally {
                modelCreationExecutor.shutdown();
            }
        } else {
            System.out.println("⏭️ [Stages 12-14/15] No agent reports available - skipping PlantUML/MCP generation.");
        }

        progress.onStep(++step, totalSteps, "progress.pipeline.finalizing");
        long finalizingStartTime = System.currentTimeMillis();
        System.out.println("🎉 [Stage 15/15] Pipeline finished successfully.");

        // La durée de finalisation doit être enregistrée avant la sérialisation (faite dans le
        // finally de run()), sans quoi elle n'apparaîtrait jamais dans le JSON.
        metrics.recordStageTiming("finalizing", finalizingStartTime);
        System.out.println(metrics.buildTimingSummary());
    }
    
    /**
     * Retourne l'expertise spécialisée selon le domaine pour contextualiser l'analyse
     */
    private String getDomainExpertise(String domain) {
        return switch (domain) {
            case "technique" -> "architecture système et infrastructure technique";
            case "rssi" -> "sécurité informatique et conformité réglementaire";
            case "fonctionnel" -> "analyse métier et processus opérationnels";
            case "rse" -> "responsabilité sociale et gouvernance";
            case "ecoconception" -> "développement durable et efficacité énergétique";
            default -> "analyse système généraliste";
        };
    }
    
    /**
     * Retourne les sous-catégories d'analyse par domaine
     */
    private String getDomainSubCategories(String domain) {
        return switch (domain) {
            case "technique" -> """
                - Architecture applicative et patterns
                - Infrastructure, déploiement, cloud
                - Performance, scalabilité, disponibilité
                - Intégrations, APIs, protocoles
                - Technologies, frameworks, composants
                """;
            case "rssi" -> """
                - Authentification, autorisation, SSO
                - Protection des données, chiffrement
                - Audit, traçabilité, logs sécurité
                - Conformité RGPD, normes sécuritaires
                - Gestion des risques, continuité
                """;
            case "fonctionnel" -> """
                - Processus métier, workflows
                - Fonctionnalités principales et secondaires
                - Règles de gestion, validation
                - Interface utilisateur, ergonomie
                - Gestion des données métier
                """;
            case "rse" -> """
                - Transparence, gouvernance
                - Éthique, protection utilisateurs
                - Accessibilité, inclusion numérique
                - Impact social, responsabilité
                - Conformité réglementaire sociale
                """;
            case "ecoconception" -> """
                - Efficacité énergétique, optimisation
                - Empreinte carbone, impact environnemental
                - Réutilisabilité, modularité, durabilité
                - Optimisation des ressources
                - Maintenabilité, évolutivité
                """;
            default -> "- Analyse générale par sous-domaines";
        };
    }

    /**
     * Filet de sécurité déterministe pour la traçabilité des exigences.
     *
     * Le LLM de filtrage est censé renseigner "source_location" (section + page) pour chaque
     * exigence, mais ce n'est pas garanti (résumé, oubli, hallucination). Cette méthode complète
     * les exigences dont "source_location" est absent ou vide en recherchant directement leur
     * "original_ref" (ex: "EX-015") ou, à défaut, un extrait de leur "source_quote"/"description"
     * dans le texte brut du PDF (rawText), qui contient les marqueurs "[PAGE n]" insérés par
     * {@link PdfExtractor}. Cela garantit une page d'origine exacte indépendamment du comportement
     * du LLM.
     * 
     * @return SourceLocationEnrichmentResult containing enriched JSON and metrics
     */
    private static SourceLocationEnrichmentResult enrichMissingSourceLocations(String filteredJson, String rawText) {
        if (filteredJson == null || filteredJson.isBlank() || rawText == null || rawText.isEmpty()) {
            return new SourceLocationEnrichmentResult(filteredJson, 0, 0, 0);
        }
        
        int fromLlm = 0;
        int fromFallback = 0;
        int stillMissing = 0;
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(filteredJson);
            if (!(root instanceof ObjectNode) || !root.has("filtered_requirements")) {
                return new SourceLocationEnrichmentResult(filteredJson, 0, 0, 0);
            }
            JsonNode reqsNode = root.get("filtered_requirements");
            if (!(reqsNode instanceof ArrayNode)) {
                return new SourceLocationEnrichmentResult(filteredJson, 0, 0, 0);
            }

            List<int[]> pageBoundaries = findPageBoundaries(rawText);

            for (JsonNode node : reqsNode) {
                if (!(node instanceof ObjectNode)) continue;
                ObjectNode reqNode = (ObjectNode) node;

                String existingLocation = reqNode.path("source_location").asText("").trim();
                if (!existingLocation.isEmpty()) {
                    fromLlm++; // LLM already filled it
                    continue;
                }

                String originalRef = reqNode.path("original_ref").asText("").trim();
                String sourceQuote = reqNode.path("source_quote").asText("").trim();
                String description = reqNode.path("description").asText("").trim();

                int matchIndex = -1;
                if (!originalRef.isEmpty()) {
                    matchIndex = indexOfFlexible(rawText, originalRef);
                }
                if (matchIndex < 0 && !sourceQuote.isEmpty()) {
                    matchIndex = indexOfFlexible(rawText, firstWords(sourceQuote, 8));
                }
                if (matchIndex < 0 && !description.isEmpty()) {
                    matchIndex = indexOfFlexible(rawText, firstWords(description, 8));
                }

                if (matchIndex >= 0) {
                    int page = pageForIndex(pageBoundaries, matchIndex);
                    if (page > 0) {
                        String section = findNearestSectionHeading(rawText, matchIndex);
                        String computedLocation = section != null
                                ? section + ", page " + page
                                : "page " + page;
                        reqNode.put("source_location", computedLocation);
                        fromFallback++;
                    } else {
                        stillMissing++;
                    }
                } else {
                    stillMissing++;
                }
            }

            String enrichedJson = mapper.writeValueAsString(root);
            return new SourceLocationEnrichmentResult(enrichedJson, fromLlm, fromFallback, stillMissing);
        } catch (Exception e) {
            System.err.println("⚠️ Could not enrich source_location fields: " + e.getMessage());
            return new SourceLocationEnrichmentResult(filteredJson, 0, 0, 0);
        }
    }

    /** Retourne une liste de [offsetDansLeTexte, numeroDePage] triée par offset croissant. */
    private static List<int[]> findPageBoundaries(String rawText) {
        List<int[]> boundaries = new ArrayList<>();
        Matcher m = Pattern.compile("\\[PAGE (\\d+)\\]").matcher(rawText);
        while (m.find()) {
            boundaries.add(new int[] { m.start(), Integer.parseInt(m.group(1)) });
        }
        return boundaries;
    }

    /** Trouve le numéro de page correspondant à un offset donné (dernière borne <= offset). */
    private static int pageForIndex(List<int[]> pageBoundaries, int index) {
        int page = -1;
        for (int[] boundary : pageBoundaries) {
            if (boundary[0] <= index) {
                page = boundary[1];
            } else {
                break;
            }
        }
        return page;
    }

    /**
     * Recherche "needle" dans "haystack" en tolérant des espaces/retours à la ligne multiples
     * (le texte extrait d'un PDF ne respecte pas toujours l'espacement d'origine).
     * Retourne l'offset du premier match, ou -1.
     */
    private static int indexOfFlexible(String haystack, String needle) {
        if (needle == null || needle.isBlank()) return -1;
        String normalizedNeedle = needle.trim();
        if (normalizedNeedle.length() < 4) return -1; // trop court pour être fiable
        try {
            StringBuilder regex = new StringBuilder();
            for (String token : normalizedNeedle.split("\\s+")) {
                if (regex.length() > 0) regex.append("\\s+");
                regex.append(Pattern.quote(token));
            }
            Matcher m = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE).matcher(haystack);
            return m.find() ? m.start() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Retourne les N premiers mots d'un texte. */
    private static String firstWords(String text, int wordCount) {
        String[] words = text.trim().split("\\s+");
        int limit = Math.min(wordCount, words.length);
        return String.join(" ", java.util.Arrays.copyOfRange(words, 0, limit));
    }

    /**
     * Cherche le titre de section/sous-section numéroté le plus proche avant "index"
     * (ex: "1.2.3 Authentification"), dans une fenêtre raisonnable pour rester pertinent.
     */
    private static String findNearestSectionHeading(String rawText, int index) {
        int windowStart = Math.max(0, index - 4000);
        String window = rawText.substring(windowStart, index);
        Matcher m = Pattern.compile("(?m)^\\s*(\\d+(?:\\.\\d+){0,3})\\s+([A-ZÀ-Ü][^\\n]{2,80})$").matcher(window);
        String lastMatch = null;
        while (m.find()) {
            lastMatch = "Section " + m.group(1) + " - " + m.group(2).trim();
        }
        return lastMatch;
    }
    
    /**
     * Parses MCP reports and trace files to extract creation and satisfy metrics
     */
    private void parseMcpMetrics(String requirementsReport, String classModelReport, String outputDir) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            
            // Parse requirements metrics
            int mcpAttempted = 0;
            int mcpCreatedSuccessfully = 0;
            int mcpFailed = 0;
            
            if (requirementsReport != null && !requirementsReport.isEmpty()) {
                try {
                    JsonNode reqReport = mapper.readTree(requirementsReport);
                    if (reqReport.has("total_requirements")) {
                        mcpAttempted = reqReport.get("total_requirements").asInt();
                        mcpCreatedSuccessfully = mcpAttempted;
                    }
                    if (reqReport.has("failed_requirements")) {
                        JsonNode failedNode = reqReport.get("failed_requirements");
                        if (failedNode.isArray()) {
                            mcpFailed = failedNode.size();
                            mcpCreatedSuccessfully = mcpAttempted - mcpFailed;
                        }
                    }
                } catch (Exception e) {
                    System.err.println("⚠️ Could not parse requirements MCP report: " + e.getMessage());
                }
            }
            
            metrics.setMcpRequirementsMetrics(mcpAttempted, mcpCreatedSuccessfully, mcpFailed);
            metrics.reconcileClassificationValidation(mcpCreatedSuccessfully);
            
            // Parse satisfy metrics from generated reports first, then trace files as fallback.
            // ("uml_elements_created" existait ici mais reposait sur countUmlElementsCreated, un
            // comptage heuristique par en-têtes de section français ("Éléments UML créés", "Cas
            // d'usage créés sous"...) qui datait d'un format de rapport antérieur à toute la
            // restructuration de cette session — ces en-têtes n'apparaissent plus dans les rapports
            // actuels, donc le champ retombait systématiquement à 0. Supprimé plutôt que réparé :
            // "class_model_elements" et "use_case_elements" couvrent déjà ce besoin avec des comptes
            // déterministes réels, pas une heuristique de texte.)
            Path outputPath = Path.of(outputDir);

            // Les liens «Satisfait» sont désormais créés de façon déterministe côté Java
            // (LangchainService.createDeterministicSatisfyLinks) et le compte réel est imprimé dans
            // le rapport sous la forme "SATISFY_LINKS_DETERMINISTIC: attempted=X confirmed=Y".
            // On privilégie cette valeur, garantie non fabriquée, et on ne retombe sur le parsing
            // heuristique des traces MCP que si ce marqueur est absent (ex. anciens rapports).
            int satisfyRelationsAttempted;
            int satisfyRelationsConfirmed;
            Matcher deterministicMatcher = Pattern.compile(
                    "SATISFY_LINKS_DETERMINISTIC:\\s*attempted=(\\d+)\\s*confirmed=(\\d+)"
                    + "(?:\\s*\\|\\s*use_cases_total=(\\d+)\\s*covered_by_llm=(\\d+)\\s*covered_by_fallback=(\\d+)\\s*uncovered=(\\d+))?")
                    .matcher(classModelReport == null ? "" : classModelReport);
            if (deterministicMatcher.find()) {
                satisfyRelationsAttempted = Integer.parseInt(deterministicMatcher.group(1));
                satisfyRelationsConfirmed = Integer.parseInt(deterministicMatcher.group(2));
                if (deterministicMatcher.group(3) != null) {
                    metrics.setMcpSatisfyLinksMetrics(satisfyRelationsAttempted, satisfyRelationsConfirmed,
                            Integer.parseInt(deterministicMatcher.group(3)), Integer.parseInt(deterministicMatcher.group(4)),
                            Integer.parseInt(deterministicMatcher.group(5)), Integer.parseInt(deterministicMatcher.group(6)));
                } else {
                    metrics.setMcpSatisfyLinksMetrics(satisfyRelationsAttempted, satisfyRelationsConfirmed);
                }
            } else {
                satisfyRelationsAttempted = countSatisfyRelations(classModelReport, outputPath, true);
                satisfyRelationsConfirmed = countSatisfyRelations(classModelReport, outputPath, false);
                metrics.setMcpSatisfyLinksMetrics(satisfyRelationsAttempted, satisfyRelationsConfirmed);
            }

            // "use_case_elements" : décompte déterministe imprimé par LangchainService sous la forme
            // "USE_CASE_ELEMENTS_DETERMINISTIC: actors=A usecases=U diagram=0|1".
            Matcher useCaseElementsMatcher = Pattern.compile(
                    "USE_CASE_ELEMENTS_DETERMINISTIC:\\s*actors=(\\d+)\\s*usecases=(\\d+)\\s*diagram=([01])")
                    .matcher(classModelReport == null ? "" : classModelReport);
            if (useCaseElementsMatcher.find()) {
                metrics.setUseCaseElementsMetrics(
                        Integer.parseInt(useCaseElementsMatcher.group(1)),
                        Integer.parseInt(useCaseElementsMatcher.group(2)),
                        "1".equals(useCaseElementsMatcher.group(3)));
            }

            int[] classModelElements = countClassModelElements(classModelReport);
            metrics.setClassModelElementsMetrics(
                    classModelElements[0], classModelElements[1], classModelElements[2], classModelElements[3]);

        } catch (Exception e) {
            System.err.println("⚠️ Error parsing MCP metrics: " + e.getMessage());
        }
    }

    /** Au-delà de ce nombre d'exigences, la classification est répartie en lots plutôt qu'envoyée en un seul appel. */
    private static final int CLASSIFY_CHUNK_THRESHOLD = 30;
    private static final int CLASSIFY_CHUNK_SIZE = 25;
    private static final List<String> CLASSIFY_CATEGORY_KEYS =
            List.of("technique", "rssi", "fonctionnel", "rse", "ecoconception");

    /**
     * Classifie les exigences filtrées, en répartissant l'appel en plusieurs lots au-delà de
     * {@link #CLASSIFY_CHUNK_THRESHOLD} exigences. Un seul appel LLM pour un grand nombre
     * d'exigences (observé : 92) risque de tronquer silencieusement sa sortie JSON avant la fin —
     * le pipeline continuait alors avec un {@code classified.json} incomplet (30 exigences
     * catégorisées sur 92) sans qu'aucune erreur ne remonte, seulement une métrique de validation
     * discordante découverte a posteriori.
     */
    private String classifyRequirementsChunked(String classifierPrompt, String filteredJson, Path outDir) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(filteredJson);
        JsonNode filteredReqs = root.path("filtered_requirements");
        if (!filteredReqs.isArray() || filteredReqs.size() <= CLASSIFY_CHUNK_THRESHOLD) {
            String classified = llm.runPrompt(classifierPrompt, filteredJson, StageModelConfig.STAGE_CLASSIFY);
            String classifiedJson = JsonUtils.extractFirstJson(classified);
            return classifiedJson != null ? classifiedJson : classified;
        }

        List<JsonNode> allReqs = new ArrayList<>();
        filteredReqs.forEach(allReqs::add);
        int totalChunks = (allReqs.size() + CLASSIFY_CHUNK_SIZE - 1) / CLASSIFY_CHUNK_SIZE;
        System.out.println("📦 Classification répartie en " + totalChunks + " lots (" + allReqs.size() + " exigences, seuil="
                + CLASSIFY_CHUNK_THRESHOLD + "), traités en parallèle");

        // Les lots sont classifiés indépendamment (chacun via son propre appel LLM, sans écriture
        // MCP partagée) : contrairement au chunking du modèle de domaine, rien ne les rend
        // dépendants les uns des autres, donc ils sont dispatchés en parallèle puis fusionnés dans
        // leur ordre d'origine pour rester déterministes.
        List<List<JsonNode>> chunks = new ArrayList<>();
        for (int i = 0; i < allReqs.size(); i += CLASSIFY_CHUNK_SIZE) {
            chunks.add(allReqs.subList(i, Math.min(i + CLASSIFY_CHUNK_SIZE, allReqs.size())));
        }

        ExecutorService classifyExecutor = Executors.newFixedThreadPool(chunks.size());
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (List<JsonNode> chunkReqs : chunks) {
                ObjectNode chunkInput = mapper.createObjectNode();
                ArrayNode chunkArray = chunkInput.putArray("filtered_requirements");
                chunkReqs.forEach(chunkArray::add);
                String chunkInputJson = mapper.writeValueAsString(chunkInput);
                Callable<String> task = () -> llm.runPrompt(classifierPrompt, chunkInputJson, StageModelConfig.STAGE_CLASSIFY);
                futures.add(classifyExecutor.submit(task));
            }

            Map<String, ArrayNode> mergedCategories = new LinkedHashMap<>();
            for (String key : CLASSIFY_CATEGORY_KEYS) {
                mergedCategories.put(key, mapper.createArrayNode());
            }
            ArrayNode mergedCrossLinks = mapper.createArrayNode();

            for (int c = 0; c < futures.size(); c++) {
                int chunkIndex = c + 1;
                String classified = futures.get(c).get();
                String chunkJsonText = JsonUtils.extractFirstJson(classified);
                if (chunkJsonText == null) {
                    chunkJsonText = classified;
                }
                if (outDir != null) {
                    Files.writeString(outDir.resolve("classified_chunk_" + chunkIndex + ".json"), chunkJsonText);
                }

                try {
                    JsonNode chunkRoot = mapper.readTree(chunkJsonText);
                    for (String key : CLASSIFY_CATEGORY_KEYS) {
                        JsonNode categoryArray = chunkRoot.path(key);
                        if (categoryArray.isArray()) {
                            categoryArray.forEach(mergedCategories.get(key)::add);
                        }
                    }
                    JsonNode crossLinks = chunkRoot.path("cross_category_links");
                    if (crossLinks.isArray()) {
                        crossLinks.forEach(mergedCrossLinks::add);
                    }
                } catch (Exception e) {
                    System.err.println("⚠️ Lot de classification " + chunkIndex + "/" + totalChunks + " illisible, ignoré : " + e.getMessage());
                }
            }

            ObjectNode merged = mapper.createObjectNode();
            for (String key : CLASSIFY_CATEGORY_KEYS) {
                merged.set(key, mergedCategories.get(key));
            }
            merged.set("cross_category_links", mergedCrossLinks);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(merged);
        } finally {
            classifyExecutor.shutdown();
        }
    }

    /**
     * Compte les éléments réellement créés lors de la PHASE 3 (classes/associations) en parsant la
     * structure JSON as-built {@code domain_model_created}/{@code modele_domaine_cree} produite par
     * le prompt (cf. UmlPromptBuilder.createClassesPrompt). Étape la plus coûteuse du pipeline
     * (46-58% du temps total) et jusqu'ici totalement dépourvue de métrique de résultat.
     *
     * @return {classes_created, attributes_created, associations_created, packages_created}
     */
    private int[] countClassModelElements(String classModelReport) {
        int classesCreated = 0, attributesCreated = 0, associationsCreated = 0;
        java.util.Set<String> packageUuids = new java.util.HashSet<>();
        try {
            String domainModelJson = AgentResultProcessor.extractJSONStructure(
                    classModelReport, "domain_model_created", "modele_domaine_cree");
            if (domainModelJson == null) {
                return new int[]{0, 0, 0, 0};
            }
            ObjectMapper localMapper = new ObjectMapper();
            JsonNode root = localMapper.readTree(domainModelJson);
            JsonNode domainModel = root.path("domain_model_created");
            if (domainModel.isMissingNode()) {
                domainModel = root.path("modele_domaine_cree");
            }

            String packageUuid = domainModel.path("package_uuid").asText(null);
            if (packageUuid != null && !packageUuid.isBlank()) {
                packageUuids.add(packageUuid);
            }

            JsonNode classes = domainModel.path("classes");
            if (classes.isArray()) {
                classesCreated = classes.size();
                for (JsonNode classNode : classes) {
                    JsonNode attributs = classNode.path("attributs");
                    if (!attributs.isArray()) {
                        attributs = classNode.path("attributes");
                    }
                    if (attributs.isArray()) {
                        attributesCreated += attributs.size();
                    }
                }
            }

            JsonNode associations = domainModel.path("associations");
            if (associations.isArray()) {
                associationsCreated = associations.size();
            }
        } catch (Exception e) {
            System.err.println("⚠️ Could not count class model elements: " + e.getMessage());
        }
        return new int[]{classesCreated, attributesCreated, associationsCreated, packageUuids.size()};
    }
    
    /**
     * Counts satisfy relations from the MCP report first, then trace files as fallback.
     * @param outputDir output directory
     * @param countAttempts if true, count all attempted calls; if false, count confirmed successful relations
     */
    private int countSatisfyRelations(String classModelReport, Path outputDir, boolean countAttempts) {
        try {
            int traceCount = 0;
            int reportConfirmed = countSatisfyRelationsFromReports(classModelReport, outputDir);
             
            // Check all MCP trace files for satisfy relations
            Pattern satisfyPattern = Pattern.compile(
                "analyst_createRelation.*?relation_type[\"']\\s*[:=]\\s*[\"']satisfy[\"']",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Pattern errorPattern = Pattern.compile(
                "(?i)(error|failed|exception|MCP_EXECUTION_FAILED)", Pattern.CASE_INSENSITIVE);
            
            // Check domain_model_phase_mcp_trace.txt
            Path domainTraceFile = outputDir.resolve("domain_model_phase_mcp_trace.txt");
            if (Files.exists(domainTraceFile)) {
                String content = Files.readString(domainTraceFile);
                if (countAttempts) {
                    Matcher matcher = satisfyPattern.matcher(content);
                    while (matcher.find()) {
                        traceCount++;
                    }
                } else {
                    // For confirmed, check if response doesn't contain errors
                    Matcher matcher = satisfyPattern.matcher(content);
                    while (matcher.find()) {
                        int matchStart = matcher.start();
                        int nextEnd = Math.min(matchStart + 2000, content.length());
                        String responseSection = content.substring(matchStart, nextEnd);
                        if (!errorPattern.matcher(responseSection).find()) {
                            traceCount++;
                        }
                    }
                }
            }
            
            // Check use_cases_phase_mcp_trace.txt
            Path useCasesTraceFile = outputDir.resolve("use_cases_phase_mcp_trace.txt");
            if (Files.exists(useCasesTraceFile)) {
                String content = Files.readString(useCasesTraceFile);
                if (countAttempts) {
                    Matcher matcher = satisfyPattern.matcher(content);
                    while (matcher.find()) {
                        traceCount++;
                    }
                } else {
                    Matcher matcher = satisfyPattern.matcher(content);
                    while (matcher.find()) {
                        int matchStart = matcher.start();
                        int nextEnd = Math.min(matchStart + 2000, content.length());
                        String responseSection = content.substring(matchStart, nextEnd);
                        if (!errorPattern.matcher(responseSection).find()) {
                            traceCount++;
                        }
                    }
                }
            }

            if (countAttempts) {
                return traceCount > 0 ? traceCount : reportConfirmed;
            }
            return reportConfirmed > 0 ? reportConfirmed : traceCount;
        } catch (Exception e) {
            System.err.println("⚠️ Could not count satisfy relations: " + e.getMessage());
        }
        return 0;
    }

    private int countSatisfyRelationsFromReport(String classModelReport) {
        if (classModelReport == null || classModelReport.isBlank()) {
            return 0;
        }
        return countPatternOccurrences(classModelReport,
                Pattern.compile("(?m)^\\s*-\\s+.*?\\bsatisfy\\b.*?(?:relation UUID|`[a-fA-F0-9-]{36}`)"));
    }

    private int countSatisfyRelationsFromReports(String classModelReport, Path outputDir) {
        int count = countSatisfyRelationsFromReport(classModelReport);
        for (String reportFileName : List.of(
                "modelio_mcp_classmodel_report.txt",
                "uml_model_3phase_report.txt",
                "modelio_mcp_creation_summary.txt")) {
            count = Math.max(count, countSatisfyRelationsFromReport(readReportIfExists(outputDir.resolve(reportFileName))));
        }
        return count;
    }

    private int countPatternOccurrences(String content, Pattern pattern) {
        Matcher matcher = pattern.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private String readReportIfExists(Path reportPath) {
        try {
            if (reportPath != null && Files.exists(reportPath)) {
                return Files.readString(reportPath);
            }
        } catch (Exception e) {
            System.err.println("⚠️ Could not read report file '" + reportPath + "': " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Helper class to return both enriched JSON and source location metrics
     */
    static class SourceLocationEnrichmentResult {
        String enrichedJson;
        int fromLlm;
        int fromFallback;
        int stillMissing;
        
        SourceLocationEnrichmentResult(String enrichedJson, int fromLlm, int fromFallback, int stillMissing) {
            this.enrichedJson = enrichedJson;
            this.fromLlm = fromLlm;
            this.fromFallback = fromFallback;
            this.stillMissing = stillMissing;
        }
    }
}
