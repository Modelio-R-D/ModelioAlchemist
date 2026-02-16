package com.docaposte.modelioalchemist.tma;

import com.docaposte.modelioalchemist.langchain.impl.LangchainService;

/**
 * Extracteur spécialisé pour les exigences de Tierce Maintenance Applicative (TMA).
 * 
 * Utilise des prompts experts pour extraire et structurer les exigences spécifiques au TMA :
 * - Exigences de niveau de service (SLA)
 * - Exigences de compétences
 * - Exigences organisationnelles
 * - Exigences techniques et d'outillage
 * - Exigences de performance
 * - Exigences de pilotage et reporting
 */
public class TmaRequirementsExtractor {
    
    private static final boolean DEBUG = true;
    
    private static void debug(String message) {
        if (DEBUG) {
            System.out.println("[TmaRequirementsExtractor] " + message);
        }
    }
    
    /**
     * Analyse un cahier des charges TMA et en extrait toutes les exigences selon la méthodologie TMA
     */
    public String analyzeTmaRequirements(String cahierDesCharges, LangchainService llm) throws Exception {
        debug("🔍 Analyzing TMA requirements from document");
        
        // Validation des entrées
        if (cahierDesCharges == null || cahierDesCharges.trim().isEmpty()) {
            throw new IllegalArgumentException("Cahier des charges cannot be null or empty");
        }
        if (llm == null) {
            throw new IllegalArgumentException("LangchainService instance cannot be null");
        }
        
        debug("📄 Document length: " + cahierDesCharges.length() + " characters");
        
        String systemPrompt = createTmaExpertSystemPrompt();
        
        try {
            String analysis = llm.runPrompt(systemPrompt, cahierDesCharges);
            
            // Validation du résultat
            if (analysis == null || analysis.trim().isEmpty()) {
                throw new RuntimeException("LLM returned empty analysis result");
            }
            
            debug("✅ TMA analysis completed: " + analysis.length() + " characters");
            return analysis;
        } catch (Exception e) {
            debug("❌ Error during TMA analysis: " + e.getMessage());
            throw new Exception("Failed to analyze TMA requirements: " + e.getMessage(), e);
        }
    }
    
    /**
     * Structure les exigences TMA analysées pour la création dans Modelio
     */
    public String structureRequirements(String tmaAnalysis, LangchainService llm) throws Exception {
        debug("📋 Structuring TMA requirements for Modelio");
        
        // Validation des entrées
        if (tmaAnalysis == null || tmaAnalysis.trim().isEmpty()) {
            throw new IllegalArgumentException("TMA analysis cannot be null or empty");
        }
        if (llm == null) {
            throw new IllegalArgumentException("LangchainService instance cannot be null");
        }
        
        debug("📊 Analysis length: " + tmaAnalysis.length() + " characters");
        
        String structuringPrompt = createRequirementsStructuringPrompt();
        
        try {
            String structured = llm.runPrompt(structuringPrompt, tmaAnalysis);
            
            // Validation du résultat
            if (structured == null || structured.trim().isEmpty()) {
                throw new RuntimeException("LLM returned empty structuring result");
            }
            
            // Validation basique du format attendu
            if (!structured.contains("=== TMA REQUIREMENT ===")) {
                debug("⚠️ Warning: Structured output doesn't contain expected TMA REQUIREMENT format");
            }
            
            debug("✅ Requirements structured: " + structured.length() + " characters");
            return structured;
        } catch (Exception e) {
            debug("❌ Error during requirements structuring: " + e.getMessage());
            throw new Exception("Failed to structure TMA requirements: " + e.getMessage(), e);
        }
    }
    
    /**
     * Crée le prompt système expert pour l'analyse TMA
     */
    private String createTmaExpertSystemPrompt() {
        return """
            # Rôle et Contexte
            Tu es un expert en analyse de cahiers des charges pour les marchés de Tierce Maintenance Applicative (TMA). 
            Ta mission est d'extraire de manière exhaustive et structurée toutes les exigences d'un cahier des charges d'appel d'offres TMA.
            
            ## Spécificités des exigences TMA
            Les exigences TMA diffèrent des projets de développement classiques :
            • Pas d'exigences fonctionnelles de construction : l'application existe déjà
            • Focus sur la continuité de service : maintien des performances actuelles
            • Exigences organisationnelles et humaines : compétences, disponibilité des équipes
            • Engagements de niveau de service : SLA, délais, indicateurs
            
            ## Types d'exigences à identifier
            
            **A. Exigences de niveau de service (SLA)**
            • Temps de réponse aux incidents (délais, heures ouvrées/non-ouvrées)
            • Temps de résolution par criticité
            • Disponibilité de la plateforme (pourcentages)
            • Temps de traitement des demandes d'évolution
            • Délais de livraison des spécifications
            • Extraire systématiquement les valeurs chiffrées et unités
            
            **B. Exigences de compétences**
            • Technologies à maîtriser (langages, frameworks, outils)
            • Niveau d'expertise requis (junior, senior, expert)
            • Maintien des compétences pendant la durée du marché
            • Maîtrise linguistique (ex: français obligatoire)
            • Formations et certifications requises
            
            **C. Exigences organisationnelles**
            • Composition de l'équipe (nombre, profils, répartition)
            • Disponibilité des ressources (horaires, astreintes)
            • Continuité de service (pas d'interruption, remplacement)
            • Processus de gestion des incidents/demandes
            • Localisation géographique des équipes
            
            **D. Exigences techniques et d'outillage**
            • Outils de ticketing imposés (JIRA, ServiceNow, etc.)
            • Outils d'intégration continue et déploiement
            • Infrastructure technique à utiliser
            • Contraintes d'environnement et de sécurité
            • Formats de livrables techniques
            
            **E. Exigences de performance**
            • Temps de réponse applicatifs à maintenir
            • Métriques de performance actuelles à préserver
            • Seuils d'alerte et d'escalade
            
            **F. Exigences de pilotage et reporting**
            • Indicateurs de suivi (KPI) obligatoires
            • Fréquence de reporting (mensuel, trimestriel)
            • Tableaux de bord à fournir
            • Comités et gouvernance à respecter
            
            ## Format de sortie
            Pour chaque exigence extraite, fournir :
            **ID:** [numéro séquentiel]
            **TYPE:** [SLA / Compétences / Organisationnel / Technique / Performance / Pilotage]
            **SECTION SOURCE:** [référence section du cahier des charges]
            **EXIGENCE:** [texte de l'exigence - copie textuelle ou reformulation claire]
            **VALEURS CHIFFRÉES:** [délais, pourcentages, nombres - si applicable]
            **CRITICITÉ:** [Obligatoire / Importante / Souhaitable]
            **VÉRIFIABLE:** [Comment cette exigence peut-elle être mesurée/vérifiée ?]
            
            ## Règles d'extraction critiques
            
            **OBLIGATOIRE :**
            • Extraire TOUTES les valeurs chiffrées (délais, %, nombres)
            • Identifier les contraintes éliminatoires
            • Noter les engagements sur toute la durée du marché
            • Capturer les références aux documents annexes
            
            **ATTENTION :**
            • Distinguer descriptions du fonctionnement actuel vs nouvelles exigences
            • Ne pas extraire les exemples non engageants
            • Regrouper les exigences similaires sans perdre de détails
            
            ## Mission
            Analyse le cahier des charges fourni et extrais toutes les exigences selon cette méthodologie.
            Structure ta réponse avec un tableau récapitulatif puis le détail de chaque exigence.
            """;
    }
    
    /**
     * Crée le prompt pour structurer les exigences pour Modelio
     */
    private String createRequirementsStructuringPrompt() {
        return """
            # Mission : Structuration des exigences TMA pour Modelio
            
            Tu dois transformer l'analyse TMA précédente en un format structuré pour créer des requirements dans Modelio.
            
            ## Format de sortie requis
            
            Pour chaque exigence identifiée, génère :
            
            ```
            === TMA REQUIREMENT ===
            ID: TMA-001
            TITLE: [Titre court de l'exigence]
            DESCRIPTION: [Description détaillée]
            CATEGORY: [SLA / Compétences / Organisationnel / Technique / Performance / Pilotage]
            PRIORITY: [Haute / Moyenne / Basse]
            SOURCE: [Section du cahier des charges]
            MEASURABLE: [Critères de vérification]
            VALUES: [Valeurs chiffrées si applicable]
            ```
            
            ## Instructions
            
            1. **Transformer chaque exigence** de l'analyse en format structuré
            2. **Garder la traçabilité** avec les sections sources
            3. **Numéroter séquentiellement** les exigences TMA (TMA-001, TMA-002, etc.)
            4. **Categoriser précisément** selon les 6 types TMA
            5. **Conserver les valeurs chiffrées** dans le champ VALUES
            
            ## Exemple
            ```
            === TMA REQUIREMENT ===
            ID: TMA-001
            TITLE: Maintien des compétences sur 4 ans
            DESCRIPTION: Le titulaire doit maintenir pendant toute la durée du marché, sans interruption, un niveau constant de compétences des intervenants
            CATEGORY: Compétences
            PRIORITY: Haute
            SOURCE: 3.2 Ressources humaines
            MEASURABLE: Audit des CV, certifications, plan de formation
            VALUES: Durée = 4 ans
            ```
            
            Transforme maintenant l'analyse TMA fournie selon ce format.
            """;
    }
}