package com.docaposte.modelioalchemist.langchain.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;


/**
 * Orchestrates the pipeline: extract PDF text, run LLM prompts, classify, generate PlantUML and optionally
 * call MCP to create elements.
 */
public class PipelineRunner {

    private final LangchainService llm;
    private final ModelioMcpAgent mcp;
    private final ObjectMapper mapper = new ObjectMapper();

    public PipelineRunner(LangchainService llm, ModelioMcpAgent mcp) {
        this.llm = llm;
        this.mcp = mcp;
    }

    public void run(String pdfPath) throws Exception {
        run(pdfPath, "output");
    }

    public void run(String pdfPath, String outputDirPath) throws Exception {
        System.out.println("Starting pipeline for: " + pdfPath);
        System.out.println("Output directory: " + outputDirPath);

        // 1) extract raw text
        String rawText = PdfExtractor.extractText(pdfPath);
        Path outDir = Path.of(outputDirPath);
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("extracted_text.txt"), rawText);
        System.out.println("Extracted text saved.");

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
            
            Texte à traiter :""";
        String extracted = llm.runPrompt(extractorPrompt, rawText);
        Files.writeString(outDir.resolve("extracted_agent_text.txt"), extracted);
        System.out.println("Extractor agent output saved.");

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
            3. Objectif : taux de rétention 40-60% (pas 15%)
            4. Préférez l'inclusion à l'exclusion pour préserver l'information
            
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
        
        String filteredRequirements = llm.runPrompt(requirementsFilterPrompt, extracted);
        
        // Extraire le JSON de la réponse
        String filteredJson = JsonUtils.extractFirstJson(filteredRequirements);
        if (filteredJson == null) {
            filteredJson = filteredRequirements;
        }
        Files.writeString(outDir.resolve("filtered_requirements.json"), filteredJson);
        System.out.println("Requirements filter output saved.");

        // Valider et rapporter les statistiques de filtrage
        try {
            JsonNode filterResults = mapper.readTree(filteredJson);
            if (filterResults.has("statistics")) {
                JsonNode stats = filterResults.get("statistics");
                int total = stats.get("total_items_analyzed").asInt();
                int retained = stats.get("requirements_retained").asInt();
                int rejected = stats.get("items_rejected").asInt();
                
                System.out.println("📊 Requirements filtering statistics:");
                System.out.println("   - Total items analyzed: " + total);
                System.out.println("   - True requirements retained: " + retained);
                System.out.println("   - False positives rejected: " + rejected);
                System.out.println("   - Retention rate: " + (total > 0 ? (retained * 100 / total) : 0) + "%");
            }
        } catch (Exception e) {
            System.err.println("⚠️ Could not parse filter statistics: " + e.getMessage());
        }

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
        String classified = llm.runPrompt(classifierPrompt, filteredJson);

        // attempt to find JSON in the response
        String classifiedJson = JsonUtils.extractFirstJson(classified);
        if (classifiedJson == null) {
            // fallback: assume entire response is JSON
            classifiedJson = classified;
        }
        Files.writeString(outDir.resolve("classified.json"), classifiedJson);
        System.out.println("Classifier output saved.");

        // Validation de l'exhaustivité des exigences
        RequirementsValidator.ValidationResult validation = 
            RequirementsValidator.validateClassification(extracted, classifiedJson);
        
        String validationReport = RequirementsValidator.generateValidationReport(validation);
        Files.writeString(outDir.resolve("requirements_validation.txt"), validationReport);
        System.out.println("Requirements validation: " + (validation.isValid ? "✅ PASSED" : "❌ FAILED"));
        
        // NOUVEAU : Validation de la contextualisation enrichie
        RequirementContextValidator.ContextValidationResult contextValidation = 
            RequirementContextValidator.validateContextualization(extracted, classifiedJson);
        
        String contextValidationReport = RequirementContextValidator.generateContextValidationReport(contextValidation);
        Files.writeString(outDir.resolve("context_validation.txt"), contextValidationReport);
        System.out.println("Context validation: " + (contextValidation.isValid ? "✅ PASSED" : "⚠️ NEEDS ATTENTION"));
        
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
        String modelDescription = "";

        for (String key : new String[]{"technique", "rssi", "fonctionnel", "rse", "ecoconception"}) {
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
            
            // Skip if no content available for this category
            if (ctx.length() == 0 || ctx.toString().trim().isEmpty()) {
                System.out.println("Skipping " + key + " - no content available");
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
            
            String report = llm.runPrompt(agentPrompt, ctx.toString());
            Files.writeString(outDir.resolve(key + "_report.txt"), report);
            System.out.println("Saved report for " + key + " (" + ctx.toString().split("\n").length + " requirements processed)");

            // Collecter tous les rapports pour les requirements
            allAgentReports.append("=== ").append(key.toUpperCase()).append(" ANALYSIS ===").append("\n");
            allAgentReports.append(report).append("\n\n");
        }
        
        // Après avoir collecté TOUS les rapports, générer la description du modèle unifiée
        if (allAgentReports.length() > 0) {
            System.out.println("Generating unified model description from ALL agent reports...");
            
            // Prompt amélioré pour la description du modèle unifié
            String modelPrompt = """
                Vous êtes un architecte système expert en modélisation UML complète. 
                
                À partir de TOUS les rapports d'analyse (fonctionnel, technique, sécurité, RSE, éco-conception), 
                identifiez TOUS les éléments du modèle système complet :
                
                1. CLASSES MÉTIER (fonctionnel) :
                   - Entités du domaine métier
                   - Objets de gestion et de traitement
                   - Services métier principaux
                
                2. CLASSES TECHNIQUES (infrastructure) :
                   - Couches de persistance et accès aux données
                   - Services d'intégration et APIs
                   - Composants d'architecture technique
                
                3. CLASSES SÉCURITÉ (RSSI) :
                   - Gestion de l'authentification et autorisation
                   - Chiffrement et protection des données
                   - Audit et traçabilité sécuritaire
                
                4. CLASSES TRANSVERSES (RSE, éco-conception) :
                   - Monitoring et métriques de performance
                   - Optimisation des ressources
                   - Gestion des logs et audit environnemental
                
                5. RELATIONS COMPLÈTES :
                   - Associations entre couches métier et technique
                   - Dépendances sécuritaires
                   - Interfaces entre tous les composants
                   - Cardinalités précises (1..1, 1..*, etc.)
                
                6. ARCHITECTURE ORGANISÉE :
                   - Packages par domaine (métier, technique, sécurité)
                   - Séparation claire des responsabilités
                   - Interfaces bien définies
                
                OBJECTIF : Créer un modèle système COMPLET qui reflète TOUS les aspects identifiés.
                
                IMPORTANT : 
                - Intégrez TOUTES les analyses (ne perdez aucune information)
                - Conservez la traçabilité avec les références EX-XXX
                - Organisez en couches cohérentes (métier, technique, sécurité, transverse)
                - Soyez précis sur les noms (ils deviendront les noms des classes UML)
                
                Tous les rapports d'analyse à modéliser :
                """;
            String modelDesc = llm.runPrompt(modelPrompt, allAgentReports.toString());
            Files.writeString(outDir.resolve("model_description.txt"), modelDesc);
            
            // Stocker pour utilisation ultérieure
            modelDescription = modelDesc;
            
            System.out.println("Unified model description generated from all agent reports.");

            
            // Générer le PlantUML à partir de la description unifiée
            String puml = ""; // Déclarer puml en dehors du bloc if
            if (modelDesc != null && !modelDesc.trim().isEmpty()) {
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
                    
                    Description complète du système à transformer en PlantUML :
                    """;
                puml = llm.runPrompt(pumlPrompt, modelDesc);
                Files.writeString(outDir.resolve("modele_donnees.puml"), puml);
                System.out.println("Complete PlantUML generated from unified model description.");
                
                // Stocker pour utilisation ultérieure
                plantUMLContent = puml;
            } else {
                System.out.println("No unified model description available - skipping PlantUML generation");
                puml = "@startuml\nnote \"No unified model description available\" as N1\n@enduml";
                Files.writeString(outDir.resolve("modele_donnees.puml"), puml);
                
                // Stocker pour utilisation ultérieure
                plantUMLContent = puml;
            }
        } else {
            System.out.println("No agent reports available - skipping model generation");
        }

        // Génération du modèle UML dans Modelio via MCP avec TOUS les rapports d'agents
        if (!plantUMLContent.isEmpty()) {
            System.out.println("Generating UML model in Modelio via MCP with ALL agent reports...");
            try {
                // Préparer les documents d'analyse pour le parsing des requirements
                StringBuilder requirementsDocuments = new StringBuilder();
                
                // Inclure le texte extrait original qui contient toutes les exigences
                requirementsDocuments.append("=== EXTRACTED REQUIREMENTS ===\n");
                requirementsDocuments.append(extracted).append("\n\n");
                
                // Inclure TOUS les rapports d'agents (technique, rssi, fonctionnel, rse, ecoconception)
                requirementsDocuments.append(allAgentReports.toString());
                
                // Inclure la description du modèle pour le contexte
                if (!modelDescription.isEmpty()) {
                    requirementsDocuments.append("=== MODEL DESCRIPTION ===\n");
                    requirementsDocuments.append(modelDescription).append("\n\n");
                }
                
                System.out.println("📋 Requirements documents assembled from ALL agents:");
                System.out.println("   - Original extracted text");
                System.out.println("   - Technical analysis report");
                System.out.println("   - RSSI security report");
                System.out.println("   - Functional analysis report");
                System.out.println("   - RSE responsibility report");
                System.out.println("   - Ecoconception sustainability report");
                System.out.println("   - Model description");
                System.out.println("   Total content length: " + requirementsDocuments.length() + " characters");
                
                // 🎆 NOUVELLE ARCHITECTURE : Création séparée des exigences et des classes UML
                
                // 1) Créer les exigences dans Modelio à partir des exigences filtrées
                System.out.println("🗺️ Step 1: Creating requirements in Modelio...");
                String requirementsReport = mcp.createRequirementsInModelio(filteredJson, outDir.toString());
                Files.writeString(outDir.resolve("modelio_mcp_requirements_report.txt"), requirementsReport);
                if (requirementsReport == null || requirementsReport.trim().isEmpty() ||
                    requirementsReport.startsWith("❌") ||
                    requirementsReport.startsWith("MCP_EXECUTION_FAILED:") ||
                    requirementsReport.startsWith("[error:")) {
                    throw new IllegalStateException("Requirements creation did not execute successfully via MCP.\n" + requirementsReport);
                }
                System.out.println("✅ Requirements created in Modelio");
                
                // 2) Créer le modèle de classes UML dans Modelio à partir du PlantUML généré
                System.out.println("🏠 Step 2: Creating UML class model in Modelio from PlantUML...");
                String classModelReport = mcp.createUmlClassModel(plantUMLContent, outDir.toString());
                Files.writeString(outDir.resolve("modelio_mcp_classmodel_report.txt"), classModelReport);
                if (classModelReport == null || classModelReport.trim().isEmpty() ||
                    classModelReport.startsWith("❌") ||
                    classModelReport.startsWith("MCP_EXECUTION_FAILED:") ||
                    classModelReport.startsWith("[error:")) {
                    throw new IllegalStateException("Class model creation did not execute successfully via MCP.\n" + classModelReport);
                }
                System.out.println("✅ UML class model created in Modelio from PlantUML");
                
                // Résumé final
                StringBuilder finalSummary = new StringBuilder();
                finalSummary.append("=== MODELIO MCP CREATION SUMMARY ===\n\n");
                finalSummary.append("1. MCP REQUIREMENTS CREATION:\n").append(requirementsReport).append("\n\n");
                finalSummary.append("2. MCP UML CLASS MODEL CREATION:\n").append(classModelReport).append("\n\n");
                finalSummary.append("=== END MCP SUMMARY ===\n");
                
                Files.writeString(outDir.resolve("modelio_mcp_creation_summary.txt"), finalSummary.toString());
                System.out.println("📋 Final MCP summary saved to modelio_mcp_creation_summary.txt");
                
            } catch (Exception e) {
                String errorMsg = "MCP failed: " + e.getMessage();
                System.err.println(errorMsg);
                Files.writeString(outDir.resolve("modelio_mcp_error.txt"), errorMsg);
                throw new IllegalStateException(errorMsg, e);
            }
        } else {
            System.out.println("No PlantUML content available - skipping MCP generation");
        }

        System.out.println("Pipeline finished successfully.");
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
}
