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

        // 3) classifier agent - prompt amélioré avec validation et exemples
        String classifierPrompt = """
            Vous êtes un expert en classification d'exigences système. Analysez le texte et classifiez CHAQUE exigence selon ces catégories EXACTES :
            
            - "technique" : Architecture système, technologies, performances, intégrations, API, protocoles, infrastructure
            - "rssi" : Sécurité, authentification, autorisation, chiffrement, audit, conformité sécuritaire, protection des données
            - "fonctionnel" : Fonctionnalités métier, cas d'usage, processus, règles de gestion, interfaces utilisateur
            - "rse" : Responsabilité sociale, éthique, impact social, gouvernance, transparence
            - "ecoconception" : Efficacité énergétique, empreinte carbone, développement durable, optimisation des ressources
            
            INSTRUCTIONS CRITIQUES:
            1. Chaque exigence doit être classée (aucune perte autorisée)
            2. Si une exigence touche plusieurs catégories, la dupliquer dans chaque catégorie pertinente
            3. Conservez le texte COMPLET de chaque exigence (pas de résumé)
            4. Numérotez chaque exigence pour traçabilité (EX-001, EX-002...)
            
            Retournez UNIQUEMENT un objet JSON valide avec cette structure exacte :
            {
              "technique": ["EX-001: texte complet exigence 1", "EX-005: texte complet exigence 5"],
              "rssi": ["EX-002: texte complet exigence 2"],
              "fonctionnel": ["EX-003: texte complet exigence 3", "EX-001: texte complet exigence 1"],
              "rse": ["EX-004: texte complet exigence 4"],
              "ecoconception": ["EX-006: texte complet exigence 6"]
            }
            
            Texte à classifier :
            """;
        String classified = llm.runPrompt(classifierPrompt, extracted);

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

            if ("fonctionnel".equals(key)) {
                // Prompt amélioré pour la description du modèle
                String modelPrompt = """
                    Vous êtes un architecte logiciel expert en modélisation UML. 
                    
                    À partir des exigences fonctionnelles analysées, identifiez TOUS les éléments du modèle de données :
                    
                    1. ENTITÉS/CLASSES : Listez chaque objet métier mentionné
                       - Nom exact de la classe
                       - Responsabilités et rôle
                       - Attributs identifiés (avec types si possible)
                       - Méthodes/opérations principales
                    
                    2. RELATIONS : Analysez TOUTES les interactions
                       - Associations (qui est lié à quoi)
                       - Compositions/Agrégations 
                       - Héritages
                       - Cardinalités (1..1, 1..*, etc.)
                    
                    3. RÈGLES MÉTIER : Contraintes et validations à modéliser
                    
                    4. INTERFACES : Services et API à exposer
                    
                    IMPORTANT : 
                    - Basez-vous UNIQUEMENT sur les exigences analysées
                    - Ne perdez aucune information des exigences fonctionnelles
                    - Conservez la traçabilité avec les références EX-XXX
                    - Soyez précis sur les noms (ils deviendront les noms des classes)
                    
                    Rapport d'exigences fonctionnelles à modéliser :
                    """;
                String modelDesc = llm.runPrompt(modelPrompt, report);
                Files.writeString(outDir.resolve("model_description.txt"), modelDesc);

                String puml = ""; // Déclarer puml en dehors du bloc if
                if (modelDesc != null && !modelDesc.trim().isEmpty()) {
                    // Prompt amélioré pour PlantUML
                    String pumlPrompt = """
                        Vous êtes un expert PlantUML. Générez un diagramme de classes UML complet et précis.
                        
                        RÈGLES STRICTES :
                        1. Commencez par @startuml et finissez par @enduml
                        2. Utilisez les NOMS EXACTS des classes identifiées (pas de synonymes)
                        3. Incluez TOUS les attributs mentionnés
                        4. Modélisez TOUTES les relations identifiées
                        5. Respectez la syntaxe PlantUML correcte
                        6. Ajoutez des commentaires pour la traçabilité (ex: ' EX-001: exigence X)
                        
                        Syntaxe PlantUML attendue :
                        - Classes : class NomClasse { +attribut: type +methode() }
                        - Relations : ClasseA --> ClasseB : "relation"
                        - Cardinalités : ClasseA "1" --> "0..*" ClasseB
                        
                        VÉRIFICATION : Le diagramme doit couvrir TOUTES les classes et relations de la description.
                        
                        Description du modèle à transformer en PlantUML :
                        """;
                    puml = llm.runPrompt(pumlPrompt, modelDesc);
                    Files.writeString(outDir.resolve("modele_donnees.puml"), puml);
                    System.out.println("PlantUML generated.");
                } else {
                    System.out.println("No model description available - skipping PlantUML generation");
                    puml = "@startuml\nnote \"No model description available\" as N1\n@enduml";
                    Files.writeString(outDir.resolve("modele_donnees.puml"), puml);
                }

                // Génération du modèle UML dans Modelio via MCP
                System.out.println("Generating UML model in Modelio via MCP...");
                try {
                    // Utiliser le client MCP directement
                    String mcpReport = mcp.generateModelFromPlantUML(puml);
                    Files.writeString(outDir.resolve("modelio_mcp_report.txt"), mcpReport);
                    System.out.println("Modelio MCP report saved.");
                    
                } catch (Exception e) {
                    String errorMsg = "MCP failed: " + e.getMessage();
                    System.err.println(errorMsg);
                    Files.writeString(outDir.resolve("modelio_mcp_error.txt"), errorMsg);
                }
            }
        }

        System.out.println("Pipeline finished.");
    }
}
