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

    public void run(String pdfPath, String outputDirPath, PipelineProgressListener progress) throws Exception {
        if (progress == null) {
            progress = PipelineProgressListener.NONE;
        }
        
        // Initialize metrics
        metrics = new PipelineMetrics();
        long pipelineStartTime = System.currentTimeMillis();
        metrics.setPipelineStartTime(pipelineStartTime);
        
        System.out.println("🚀 [Pipeline] Starting pipeline for: " + pdfPath);
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
        String extracted = llm.runPrompt(extractorPrompt, rawText, StageModelConfig.STAGE_EXTRACT);
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
                filterTotal = stats.get("total_items_analyzed").asInt();
                filterRetained = stats.get("requirements_retained").asInt();
                filterRejected = stats.get("items_rejected").asInt();
                
                System.out.println("📊 Requirements filtering statistics:");
                System.out.println("   - Total items analyzed: " + filterTotal);
                System.out.println("   - True requirements retained: " + filterRetained);
                System.out.println("   - False positives rejected: " + filterRejected);
                System.out.println("   - Retention rate: " + (filterTotal > 0 ? (filterRetained * 100 / filterTotal) : 0) + "%");
                
                // Record filtering metrics
                metrics.setFilteringMetrics(filterTotal, filterRetained, filterRejected);
            }
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
        String classified = llm.runPrompt(classifierPrompt, filteredJson, StageModelConfig.STAGE_CLASSIFY);
        metrics.recordStageTiming("classification", classifyStartTime);

        // attempt to find JSON in the response
        String classifiedJson = JsonUtils.extractFirstJson(classified);
        if (classifiedJson == null) {
            // fallback: assume entire response is JSON
            classifiedJson = classified;
        }
        Files.writeString(outDir.resolve("classified.json"), classifiedJson);
        System.out.println("✅ [Stage 4/15] Classifier output saved.");

        // Validation de l'exhaustivité des exigences
        progress.onStep(++step, totalSteps, "progress.pipeline.validateCompleteness");
        RequirementsValidator.ValidationResult validation = 
            RequirementsValidator.validateClassification(extracted, classifiedJson);
        
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
        RequirementContextValidator.ContextValidationResult contextValidation = 
            RequirementContextValidator.validateContextualization(extracted, classifiedJson);
        
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
        // NOTE : on ne passe plus par une étape intermédiaire de "description du modèle en prose"
        // (l'ancienne PHASE modelDescription). Cette description n'était qu'un relais de paraphrase
        // entre les rapports structurés et le PlantUML : elle coûtait un aller-retour LLM complet sans
        // ajouter de décision indépendante, et chaque paraphrase est une occasion de perdre ou déformer
        // de l'information. Générer le PlantUML directement depuis les rapports réduit à la fois la
        // latence et le risque de dérive/perte d'information.
        if (allAgentReports.length() > 0) {
            System.out.println("🧩 [Stage 12/15] Generating PlantUML directly from all agent reports...");
            progress.onStep(++step, totalSteps, "progress.pipeline.plantuml");
            long umlSynthesisStartTime = System.currentTimeMillis();

            // Prompt amélioré pour PlantUML unifié avec use cases et types corrects
            String pumlPrompt = """
                Vous êtes un expert PlantUML spécialisé dans les architectures système complètes.
                Générez PLUSIEURS diagrammes UML COMPLETS : classes, use cases et séquences.
                
                STRUCTURE OBLIGATOIRE - Générez EXACTEMENT ce format :
                
                1. DIAGRAMME DE CLASSES (obligatoire) :
                @startuml Classes
                !theme plain
                
                package "Business" {
                  class NomExact {
                    +id: int
                    +nom: String  
                    +date: String
                    +status: String
                    +methode()
                  }
                }
                
                package "Technical" {
                  class ServiceClass {
                    +processData()
                    +validateInput()
                  }
                }
                
                package "Securite" {
                  class AuthService {
                    +authenticate(): boolean
                    +authorize(): boolean
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
            metrics.recordStageTiming("uml_synthesis", umlSynthesisStartTime);
            Files.writeString(outDir.resolve("modele_donnees.puml"), puml);
            System.out.println("✅ [Stage 12/15] PlantUML generated from all agent reports.");

            plantUMLContent = puml;
        } else {
            System.out.println("⏭️ [Stage 12/15] No agent reports available - skipping PlantUML generation.");
        }

        // Génération du modèle UML dans Modelio via MCP avec TOUS les rapports d'agents
        if (!plantUMLContent.isEmpty()) {
            System.out.println("🏗️ [Stages 13-14/15] Generating UML model in Modelio via MCP with all agent reports...");
            long mcpCreationStartTime = System.currentTimeMillis();
            try {
                // Préparer les documents d'analyse pour le parsing des requirements
                StringBuilder requirementsDocuments = new StringBuilder();
                
                // Inclure le texte extrait original qui contient toutes les exigences
                requirementsDocuments.append("=== EXTRACTED REQUIREMENTS ===\n");
                requirementsDocuments.append(extracted).append("\n\n");
                
                // Inclure TOUS les rapports d'agents (technique, rssi, fonctionnel, rse, ecoconception)
                requirementsDocuments.append(allAgentReports.toString());
                
                System.out.println("📋 Requirements documents assembled from ALL agents:");
                System.out.println("   - Original extracted text");
                System.out.println("   - Technical analysis report");
                System.out.println("   - RSSI security report");
                System.out.println("   - Functional analysis report");
                System.out.println("   - RSE responsibility report");
                System.out.println("   - Ecoconception sustainability report");
                System.out.println("   Total content length: " + requirementsDocuments.length() + " characters");
                
                // 🎆 NOUVELLE ARCHITECTURE : Création séparée des exigences et des classes UML
                
                // 1) Créer les exigences dans Modelio à partir des exigences filtrées
                System.out.println("🗺️ Step 1: Creating requirements in Modelio...");
                progress.onStep(++step, totalSteps, "progress.pipeline.createRequirements");
                String requirementsReport = mcp.createRequirementsInModelio(filteredJson, outDir.toString(), sourceDocumentName);
                Files.writeString(outDir.resolve("modelio_mcp_requirements_report.txt"), requirementsReport);
                if (requirementsReport == null || requirementsReport.trim().isEmpty() ||
                    requirementsReport.startsWith("❌") ||
                    requirementsReport.startsWith("MCP_EXECUTION_FAILED:") ||
                    requirementsReport.startsWith("[error:")) {
                    throw new IllegalStateException("Requirements creation did not execute successfully via MCP.\n" + requirementsReport);
                }
                System.out.println("✅ [Stage 13/15] Requirements created in Modelio.");
                
                // 2) Créer le modèle de classes UML dans Modelio à partir du PlantUML généré
                System.out.println("🏠 Step 2: Creating UML class model in Modelio from PlantUML...");
                progress.onStep(++step, totalSteps, "progress.pipeline.createClassModel");
                String classModelReport = mcp.createUmlClassModel(plantUMLContent, requirementsReport, outDir.toString());
                Files.writeString(outDir.resolve("modelio_mcp_classmodel_report.txt"), classModelReport);
                if (classModelReport == null || classModelReport.trim().isEmpty() ||
                    classModelReport.startsWith("❌") ||
                    classModelReport.startsWith("MCP_EXECUTION_FAILED:") ||
                    classModelReport.startsWith("[error:")) {
                    throw new IllegalStateException("Class model creation did not execute successfully via MCP.\n" + classModelReport);
                }
                System.out.println("✅ [Stage 14/15] UML class model created in Modelio from PlantUML.");
                
                // Résumé final
                StringBuilder finalSummary = new StringBuilder();
                finalSummary.append("=== MODELIO MCP CREATION SUMMARY ===\n\n");
                finalSummary.append("1. MCP REQUIREMENTS CREATION:\n").append(requirementsReport).append("\n\n");
                finalSummary.append("2. MCP UML CLASS MODEL CREATION:\n").append(classModelReport).append("\n\n");
                finalSummary.append("=== END MCP SUMMARY ===\n");
                
                Files.writeString(outDir.resolve("modelio_mcp_creation_summary.txt"), finalSummary.toString());
                System.out.println("📋 Final MCP summary saved to modelio_mcp_creation_summary.txt");
                
                // Parse MCP reports to extract metrics
                parseMcpMetrics(requirementsReport, classModelReport, outDir.toString());
                
            } catch (Exception e) {
                String errorMsg = "MCP failed: " + e.getMessage();
                System.err.println(errorMsg);
                Files.writeString(outDir.resolve("modelio_mcp_error.txt"), errorMsg);
                throw new IllegalStateException(errorMsg, e);
            }
            metrics.recordStageTiming("mcp_creation", mcpCreationStartTime);
        } else {
            System.out.println("⏭️ [Stages 13-14/15] No PlantUML content available - skipping MCP generation.");
        }

        progress.onStep(++step, totalSteps, "progress.pipeline.finalizing");
        System.out.println("🎉 [Stage 15/15] Pipeline finished successfully.");
        
        // Write pipeline metrics to JSON
        try {
            Path metricsPath = outDir.resolve("pipeline_metrics.json");
            String metricsJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(metrics.toJson());
            Files.writeString(metricsPath, metricsJson);
            System.out.println("📊 Pipeline metrics written to pipeline_metrics.json");
        } catch (Exception e) {
            System.err.println("⚠️ Could not write pipeline metrics: " + e.getMessage());
        }
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
            
            // Parse UML and satisfy metrics from trace files
            Path outputPath = Path.of(outputDir);
            int umlElementsCreated = countUmlElementsCreated(outputPath);
            int satisfyRelationsAttempted = countSatisfyRelations(outputPath, true);
            int satisfyRelationsConfirmed = countSatisfyRelations(outputPath, false);
            
            metrics.setMcpSatisfyLinksMetrics(umlElementsCreated, satisfyRelationsAttempted, satisfyRelationsConfirmed);
            
        } catch (Exception e) {
            System.err.println("⚠️ Error parsing MCP metrics: " + e.getMessage());
        }
    }
    
    /**
     * Counts UML elements created by parsing trace files
     */
    private int countUmlElementsCreated(Path outputDir) {
        try {
            // Check for class model report which contains UML creation info
            Path classModelReport = outputDir.resolve("modelio_mcp_classmodel_report.txt");
            if (Files.exists(classModelReport)) {
                String content = Files.readString(classModelReport);
                // Count analyst_createElement calls for UML elements (class, usecase, actor, etc.)
                int count = 0;
                Pattern pattern = Pattern.compile("analyst_createElement.*?type[\"']\\s*[:=]\\s*[\"']([^\"']+)", 
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                Matcher matcher = pattern.matcher(content);
                while (matcher.find()) {
                    count++;
                }
                if (count > 0) return count;
            }
            
            // Parse from use_cases_phase_mcp_trace.txt if available
            Path traceFile = outputDir.resolve("use_cases_phase_mcp_trace.txt");
            if (Files.exists(traceFile)) {
                String content = Files.readString(traceFile);
                Pattern pattern = Pattern.compile("type[\"']\\s*[:=]\\s*[\"']([^\"']+)[\"']");
                Matcher matcher = pattern.matcher(content);
                int count = 0;
                while (matcher.find()) {
                    String type = matcher.group(1).toLowerCase();
                    if (type.contains("class") || type.contains("usecase") || type.contains("actor") ||
                        type.contains("component") || type.contains("package")) {
                        count++;
                    }
                }
                if (count > 0) return count;
            }
            
            // Parse from domain_model_phase_mcp_trace.txt if available
            Path domainTraceFile = outputDir.resolve("domain_model_phase_mcp_trace.txt");
            if (Files.exists(domainTraceFile)) {
                String content = Files.readString(domainTraceFile);
                Pattern pattern = Pattern.compile("type[\"']\\s*[:=]\\s*[\"']([^\"']+)[\"']");
                Matcher matcher = pattern.matcher(content);
                int count = 0;
                while (matcher.find()) {
                    String type = matcher.group(1).toLowerCase();
                    if (type.contains("class") || type.contains("usecase") || type.contains("actor") ||
                        type.contains("component") || type.contains("package")) {
                        count++;
                    }
                }
                return count;
            }
        } catch (Exception e) {
            System.err.println("⚠️ Could not count UML elements: " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * Counts satisfy relations from trace files
     * @param outputDir output directory
     * @param countAttempts if true, count all attempted calls; if false, count confirmed successful relations
     */
    private int countSatisfyRelations(Path outputDir, boolean countAttempts) {
        try {
            int count = 0;
            
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
                        count++;
                    }
                } else {
                    // For confirmed, check if response doesn't contain errors
                    Matcher matcher = satisfyPattern.matcher(content);
                    while (matcher.find()) {
                        int matchStart = matcher.start();
                        int nextEnd = Math.min(matchStart + 2000, content.length());
                        String responseSection = content.substring(matchStart, nextEnd);
                        if (!errorPattern.matcher(responseSection).find()) {
                            count++;
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
                        count++;
                    }
                } else {
                    Matcher matcher = satisfyPattern.matcher(content);
                    while (matcher.find()) {
                        int matchStart = matcher.start();
                        int nextEnd = Math.min(matchStart + 2000, content.length());
                        String responseSection = content.substring(matchStart, nextEnd);
                        if (!errorPattern.matcher(responseSection).find()) {
                            count++;
                        }
                    }
                }
            }
            
            return count;
        } catch (Exception e) {
            System.err.println("⚠️ Could not count satisfy relations: " + e.getMessage());
        }
        return 0;
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
