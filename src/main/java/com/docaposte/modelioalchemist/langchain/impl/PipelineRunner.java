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

        // 2) extractor agent - prompt amélioré pour conserver toutes les exigences  
        String extractorPrompt = "Vous êtes un expert en extraction de documents techniques. Votre mission est d'extraire TOUTES les informations importantes du document, en particulier :\n" +
            "\n" +
            "1. TOUTES les exigences fonctionnelles (même celles qui semblent mineures)\n" +
            "2. TOUTES les contraintes techniques \n" +
            "3. TOUS les aspects de sécurité et RSSI\n" +
            "4. TOUTES les exigences RSE et écoconception\n" +
            "5. Tous les détails sur l'architecture, les composants, les interfaces\n" +
            "6. Toutes les règles métier et processus\n" +
            "\n" +
            "IMPORTANT: Ne résumez pas, ne filtrez pas, ne perdez aucune information. \n" +
            "Conservez les détails techniques, les références, les normes mentionnées.\n" +
            "Structurez le texte de manière claire mais gardez l'exhaustivité.\n" +
            "\n" +
            "Texte à traiter :";
        String extracted = llm.runPrompt(extractorPrompt, rawText);
        Files.writeString(outDir.resolve("extracted_agent_text.txt"), extracted);
        System.out.println("Extractor agent output saved.");

        // 2.5) NOUVEAU : Requirements Filter Agent - Étape de pré-traitement intelligent
        String requirementsFilterPrompt = """
            Vous êtes un expert en identification d'exigences système. Votre mission est de FILTRER le texte pour ne conserver QUE les vraies exigences opérationnelles.
            
            CRITÈRES STRICTS pour qu'un élément soit une VRAIE exigence :
            ✅ ACCEPTER : Exigences qui décrivent des capacités, contraintes, ou comportements spécifiques du système
            - "Le système doit permettre..."
            - "L'application doit supporter..."
            - "La performance doit être inférieure à..."
            - "Les données doivent être chiffrées..."
            - "L'utilisateur doit pouvoir..."
            
            ❌ REJETER : Éléments qui ne sont PAS des exigences opérationnelles
            - Titres de sections ("Objectif du document", "Fonctionnalités principales")
            - Descriptions générales ("Ce document décrit...")
            - Contexte ou introduction
            - Références bibliographiques
            - Artefacts de formatage (**Pour EX-XXX**, ***Note***, etc.)
            - Résumés ou conclusions
            
            INSTRUCTIONS :
            1. Parcourez TOUT le texte ligne par ligne
            2. Ne gardez QUE les phrases qui sont de vraies exigences
            3. Reformulez chaque exigence retenue de manière claire et actionnable
            4. Numérotez les vraies exigences (REQ-001, REQ-002, etc.)
            5. Ajoutez une catégorie estimée (Functional, Technical, Security, Performance)
            6. Ajoutez un niveau de priorité estimé (High, Medium, Low)
            
            FORMAT DE SORTIE - JSON uniquement :
            {
              "filtered_requirements": [
                {
                  "id": "REQ-001",
                  "description": "Le système doit permettre l'authentification des utilisateurs via SSO",
                  "category": "Security",
                  "priority": "High"
                },
                {
                  "id": "REQ-002", 
                  "description": "La base de données doit supporter au moins 1000 utilisateurs concurrents",
                  "category": "Performance",
                  "priority": "Medium"
                }
              ],
              "rejected_items": [
                "Objectif du document",
                "Fonctionnalités principales",
                "**Pour EX-011**:"
              ],
              "statistics": {
                "total_items_analyzed": 45,
                "requirements_retained": 23,
                "items_rejected": 22
              }
            }
            
            Texte à filtrer :
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
            Vous êtes un expert en classification d'exigences système. Vous recevez une liste de VRAIES exigences déjà filtrées.
            
            Classifiez CHAQUE exigence selon ces catégories EXACTES :
            - "technique" : Architecture système, technologies, performances, intégrations, API, protocoles, infrastructure
            - "rssi" : Sécurité, authentification, autorisation, chiffrement, audit, conformité sécuritaire, protection des données
            - "fonctionnel" : Fonctionnalités métier, cas d'usage, processus, règles de gestion, interfaces utilisateur
            - "rse" : Responsabilité sociale, éthique, impact social, gouvernance, transparence
            - "ecoconception" : Efficacité énergétique, empreinte carbone, développement durable, optimisation des ressources
            
            INSTRUCTIONS :
            1. Prenez les exigences filtrées du JSON fourni (champ "filtered_requirements")
            2. Classifiez chaque exigence selon sa catégorie naturelle
            3. Conservez l'ID et la description exacte de chaque exigence
            4. Si une exigence touche plusieurs catégories, la dupliquer dans chaque catégorie
            
            Retournez UNIQUEMENT un objet JSON valide avec cette structure :
            {
              "technique": ["REQ-001: texte complet exigence 1", "REQ-005: texte complet exigence 5"],
              "rssi": ["REQ-002: texte complet exigence 2"],
              "fonctionnel": ["REQ-003: texte complet exigence 3"],
              "rse": ["REQ-004: texte complet exigence 4"],
              "ecoconception": ["REQ-006: texte complet exigence 6"]
            }
            
            Exigences filtrées à classifier (JSON) :
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
        
        if (!validation.isValid) {
            System.err.println("⚠️  WARNING: Some requirements may have been lost during classification!");
            System.err.println("Missing: " + validation.missingRequirements.size() + " requirements");
        }

        // parse JSON
        JsonNode root = mapper.readTree(classifiedJson);

        // Stocker tous les rapports d'agents pour les inclure dans les requirements
        StringBuilder allAgentReports = new StringBuilder();
        String plantUMLContent = "";
        String modelDescription = "";

        for (String key : new String[]{"technique", "rssi", "fonctionnel", "rse", "ecoconception"}) {
            String ctx = "";
            if (root.has(key) && root.get(key) != null) {
                JsonNode node = root.get(key);
                if (!node.isNull()) {
                    ctx = node.toString();
                }
            }
            
            // Skip if no content available for this category
            if (ctx.isEmpty() || ctx.equals("\"\"") || ctx.equals("{}") || ctx.equals("[]")) {
                System.out.println("Skipping " + key + " - no content available");
                Files.writeString(outDir.resolve(key + "_report.txt"), "No content available for category: " + key);
                continue;
            }
            
            // Prompt amélioré pour l'analyse par catégorie
            String agentPrompt = """
                Vous êtes un expert en analyse d'exigences pour la catégorie "%s".
                
                Analysez en détail TOUTES les exigences de cette catégorie et produisez un rapport structuré comprenant :
                
                1. INVENTAIRE EXHAUSTIF : Listez et numérotez chaque exigence (gardez les références EX-XXX)
                2. ANALYSE DÉTAILLÉE : Pour chaque exigence, analysez :
                   - L'impact sur le système
                   - Les contraintes techniques
                   - Les dépendances avec d'autres exigences
                   - Les risques et points d'attention
                3. SYNTHÈSE ARCHITECTURALE : Impact global sur l'architecture système
                4. RECOMMANDATIONS : Actions concrètes pour satisfaire ces exigences
                5. POINTS DE VIGILANCE : Risques de non-conformité ou de perte d'exigences
                
                CRITÈRES DE QUALITÉ :
                - Aucune exigence ne doit être omise ou résumée
                - Conservez la traçabilité (références EX-XXX)
                - Identifiez les liens entre exigences
                - Proposez des solutions concrètes
                
                Exigences à analyser pour la catégorie %s :
                """.formatted(key.toUpperCase(), key);
            
            String report = llm.runPrompt(agentPrompt, ctx);
            Files.writeString(outDir.resolve(key + "_report.txt"), report);
            System.out.println("Saved report for " + key);

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
                // Prompt amélioré pour PlantUML unifié
                String pumlPrompt = """
                    Vous êtes un expert PlantUML spécialisé dans les architectures système complètes.
                    Générez un diagramme de classes UML COMPLET intégrant TOUS les aspects du système.
                    
                    RÈGLES STRICTES :
                    1. Commencez par @startuml et finissez par @enduml
                    2. Organisez en PACKAGES par domaine :
                       - package "Business" { } // Classes métier
                       - package "Technical" { } // Infrastructure
                       - package "Security" { } // Sécurité
                       - package "Monitoring" { } // Transverse
                    3. Utilisez les NOMS EXACTS des classes identifiées (pas de synonymes)
                    4. Incluez TOUS les attributs et méthodes mentionnés
                    5. Modélisez TOUTES les relations (métier, technique, sécurité)
                    6. Respectez la syntaxe PlantUML correcte
                    7. Ajoutez des commentaires pour la traçabilité (ex: ' EX-001: exigence fonctionnelle)
                    
                    SYNTAXE PlantUML attendue :
                    - Classes : class NomClasse { +attribut: type +methode() }
                    - Relations : ClasseA --> ClasseB : "relation"
                    - Cardinalités : ClasseA "1" --> "0..*" ClasseB
                    - Héritage : ClasseParent <|-- ClasseEnfant
                    - Composition : ClasseA *-- ClasseB
                    - Agrégation : ClasseA o-- ClasseB
                    
                    VÉRIFICATION : Le diagramme doit refléter :
                    - Toutes les classes métier (fonctionnel)
                    - Toutes les classes techniques (infrastructure)
                    - Toutes les classes sécuritaires (RSSI)
                    - Toutes les classes transverses (RSE, éco-conception)
                    - Toutes leurs relations et interactions
                    
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
                Files.writeString(outDir.resolve("modelio_requirements_report.txt"), requirementsReport);
                System.out.println("✅ Requirements created in Modelio");
                
                // 2) Créer le modèle de classes UML dans Modelio à partir du PlantUML généré
                System.out.println("🏠 Step 2: Creating UML class model in Modelio from PlantUML...");
                String classModelReport = mcp.createUmlClassModel(plantUMLContent, outDir.toString());
                Files.writeString(outDir.resolve("modelio_classmodel_report.txt"), classModelReport);
                System.out.println("✅ UML class model created in Modelio from PlantUML");
                
                // Résumé final
                StringBuilder finalSummary = new StringBuilder();
                finalSummary.append("=== MODELIO CREATION SUMMARY ===\n\n");
                finalSummary.append("1. REQUIREMENTS CREATION:\n").append(requirementsReport).append("\n\n");
                finalSummary.append("2. UML CLASS MODEL CREATION:\n").append(classModelReport).append("\n\n");
                finalSummary.append("=== END SUMMARY ===\n");
                
                Files.writeString(outDir.resolve("modelio_creation_summary.txt"), finalSummary.toString());
                System.out.println("📋 Final summary saved to modelio_creation_summary.txt");
                
            } catch (Exception e) {
                String errorMsg = "MCP failed: " + e.getMessage();
                System.err.println(errorMsg);
                Files.writeString(outDir.resolve("modelio_mcp_error.txt"), errorMsg);
            }
        } else {
            System.out.println("No PlantUML content available - skipping MCP generation");
        }

        System.out.println("Pipeline finished.");
    }
}
