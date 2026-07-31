package com.docaposte.modelioalchemist.langchain.impl;

import java.util.List;

/**
 * Builds LLM prompts for each UML model creation phase.
 * All methods are stateless; they receive the data they need as parameters.
 */
class UmlPromptBuilder {

    /** Max requirement UUIDs injected into a single chunk prompt to avoid context bloat. */
    private static final int PHASE2_CHUNK_MAX_REQUIREMENT_UUIDS = 20;

    // ------------------------------------------------------------------ Phase 1: requirements

    /**
     * Builds a prompt to create requirements in Modelio from pre-parsed document requirements.
     * Requirements must always come from the input document analysis — never from the PlantUML,
     * which represents the solution, not the problem.
     */
    static String createRequirementsPrompt(List<Requirement> parsedRequirements) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("🇫🇷 Vous êtes un analyste d'exigences Modelio. Votre mission : créer TOUTES les exigences en français dans Modelio.\n\n");
        prompt.append("## MISSION PHASE 1 : CRÉATION DES EXIGENCES\n");
        prompt.append("🎯 Créer des exigences complètes en français issues de l'analyse du document source.\n");
        prompt.append("🚨 Utiliser les outils MCP pour créer CHAQUE exigence comme élément d'analyse.\n\n");

        if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
            int displayLimit = Math.min(parsedRequirements.size(), 15);
            prompt.append("## EXIGENCES À CRÉER\n");
            prompt.append("Les ").append(displayLimit).append(" exigences principales")
                    .append(parsedRequirements.size() > 15 ? " (sur " + parsedRequirements.size() + " totales)" : "")
                    .append(" extraites du document source :\n\n");
            for (int i = 0; i < displayLimit; i++) {
                Requirement req = parsedRequirements.get(i);
                prompt.append(String.format("**%s**: %s\n", req.id, req.description));
                prompt.append(String.format("  - Catégorie: %s | Priorité: %s\n\n", req.category, req.priority));
            }
            if (parsedRequirements.size() > displayLimit) {
                prompt.append("⚠️ ").append(parsedRequirements.size() - displayLimit)
                        .append(" exigences supplémentaires existent mais sont résumées pour limiter la charge MCP.\n\n");
            }
            prompt.append("🚨 OBLIGATOIRE : Créer CHACUNE de ces exigences EXACTEMENT avec les outils MCP.\n\n");
        } else {
            prompt.append("⚠️ Aucune exigence parsée disponible dans le contexte courant.\n");
            prompt.append("Créez uniquement les exigences qui ont été explicitement fournies ci-dessus.\n\n");
        }

        prompt.append("## INSTRUCTIONS D'EXÉCUTION\n");
        prompt.append("🚨 OBLIGATOIRE : Utiliser les outils MCP de création d'exigences pour CHAQUE exigence\n");
        if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
            prompt.append("- Créer les ").append(parsedRequirements.size()).append(" exigences listées EXACTEMENT comme spécifié\n");
            prompt.append("- Utiliser les ID, descriptions, catégories et priorités fournis\n");
        }
        prompt.append("- Créer les exigences comme éléments d'analyse dans Modelio\n");
        prompt.append("- Rapporter l'UUID de chaque exigence créée pour la traçabilité\n");
        prompt.append("- Organiser dans le package 'Exigences'\n\n");

        prompt.append("📊 **CRITIQUE : PRODUIRE UNE SORTIE STRUCTURÉE**\n");
        prompt.append("Après avoir créé toutes les exigences, générer ce format EXACT :\n\n```json\n");
        prompt.append("{\n  \"requirements_created\": [\n    {\n");
        prompt.append("      \"id\": \"EXG-001\",\n      \"uuid\": \"00000000-0000-0000-0000-000000000000\",\n");
        prompt.append("      \"description\": \"Le système doit...\",\n");
        prompt.append("      \"categorie\": \"Fonctionnelle\",\n      \"priorite\": \"Haute\"\n    }\n  ],\n");
        prompt.append("  \"total_requirements\": 12,\n  \"package_uuid\": \"00000000-0000-0000-0000-000000000000\"\n}\n```\n\n");

        prompt.append("## CRITÈRES DE RÉUSSITE\n");
        prompt.append("✅ Toutes les exigences couvertes par des éléments d'analyse appropriés\n");
        prompt.append("✅ UUIDs rapportés pour toutes les exigences créées\n");
        prompt.append("✅ Exigences correctement catégorisées et priorisées\n");
        prompt.append("✅ Toutes les descriptions en français\n\n");
        prompt.append("NE FOURNISSEZ PAS DE PROCÉDURE MANUELLE. COMMENCEZ MAINTENANT : créez les exigences avec les outils MCP et retournez uniquement le JSON demandé.");
        return prompt.toString();
    }

    // ------------------------------------------------------------------ Phase 2A: classes (full)

    static String createClassesPrompt(String analysisResults, String requirementsResult, List<Requirement> parsedRequirements, List<String> requirementUUIDs) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("🇫🇷 Vous êtes un modélisateur de domaine Modelio. Votre mission : créer TOUTES les classes et associations en français.\n\n");
        prompt.append("## MISSION PHASE 2 : CLASSES & ASSOCIATIONS\n");
        prompt.append("🎯 Créer un modèle de domaine complet en français à partir du PlantUML avec toutes les relations.\n");
        prompt.append("🚨 Utiliser les outils MCP pour créer classes, attributs et associations.\n\n");
        prompt.append("🚫 INTERDICTIONS ABSOLUES : ne pas appeler `analyst_queryItems` et ne jamais rechercher une exigence par nom, mot-clé ou texte libre.\n");
        prompt.append("🔗 Les liens «Satisfait» sont OPTIONNELS : utilisez uniquement les UUID d'exigence explicitement fournis ci-dessous, sinon sautez le lien sans erreur.\n\n");

        prompt.append("## TRAÇABILITÉ - EXIGENCES CRÉÉES\n");
        if (requirementsResult != null && !requirementsResult.trim().isEmpty()) {
            if (requirementsResult.length() > 1500) {
                prompt.append(requirementsResult, 0, 1500).append("\n... (contexte exigences tronqué)\n\n");
            } else {
                prompt.append(requirementsResult).append("\n\n");
            }
            prompt.append("🔗 MAINTENIR LA TRAÇABILITÉ : Lier les classes aux exigences pertinentes lors de leur création.\n\n");
        }

        if (requirementUUIDs != null && !requirementUUIDs.isEmpty()) {
            prompt.append("## UUIDS DES EXIGENCES DISPONIBLES POUR LIEN «SATISFAIT» (OPTIONNEL)\n");
            prompt.append("NE PAS chercher les exigences par mots-clés! Utiliser directement ces UUIDs si pertinent:\n\n");
            for (int i = 0; i < requirementUUIDs.size(); i++) {
                prompt.append(String.format("- UUID exigence #%d: %s\n", (i + 1), requirementUUIDs.get(i)));
            }
            prompt.append("\nSi un lien «Satisfait» est créé, utiliser analyst_createRelation avec relation_type=\"satisfy\",");
            prompt.append(" source_uuid=<UUID classe>, target_uuid=<l'un des UUIDs exigences ci-dessus>.\n");
            prompt.append("⚠️ CES LIENS SONT OPTIONNELS — ne jamais bloquer ni échouer si aucun UUID d'exigence n'est disponible.\n\n");
        }

        if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
            prompt.append("## CONTEXTE DES EXIGENCES POUR TRAÇABILITÉ\n");
            prompt.append("Considérer ces exigences lors de la création des classes :\n");
            for (int i = 0; i < Math.min(parsedRequirements.size(), 10); i++) {
                Requirement req = parsedRequirements.get(i);
                prompt.append(String.format("- %s: %s\n", req.id,
                        req.description.length() > 80 ? req.description.substring(0, 80) + "..." : req.description));
            }
            if (parsedRequirements.size() > 10) {
                prompt.append("... et ").append(parsedRequirements.size() - 10).append(" exigences supplémentaires\n");
            }
            prompt.append("\n");
        }

        prompt.append("## PlantUML à implémenter\n```plantuml\n");
        prompt.append(PlantUmlParser.preparePlantUmlForPrompt(analysisResults));
        prompt.append("\n```\n\n");

        prompt.append("## SÉQUENCE D'EXÉCUTION (OBLIGATOIRE)\n");
        prompt.append("1️⃣ **Créer les Packages** : Utiliser les outils MCP\n");
        prompt.append("   - Créer le package 'Modèle de Domaine' pour toutes les classes\n");
        prompt.append("   - Rapporter l'UUID du package\n\n");
        prompt.append("2️⃣ **Créer les Classes** : Utiliser les outils MCP UNE PAR UNE\n");
        prompt.append("   - Parser TOUTES les classes du PlantUML\n");
        prompt.append("   - Conserver les noms exacts du PlantUML\n");
        prompt.append("   - Créer dans le package 'Modèle de Domaine'\n");
        prompt.append("   - Rapporter l'UUID de chaque classe\n");
        prompt.append("   - OPTIONNEL : si un UUID d'exigence valide est disponible ci-dessus, créer un lien «Satisfait»\n");
        prompt.append("     via `analyst_createRelation` (relation_type=\"satisfy\", source_uuid=<UUID de la classe>, target_uuid=<UUID de l'exigence>,\n");
        prompt.append("     module_name=\"ModelerModule\").\n");
        prompt.append("     Ne jamais bloquer la création de classe à cause d'un lien «Satisfait» manquant.\n\n");
        prompt.append("3️⃣ **Ajouter les Attributs** : Utiliser les outils MCP POUR CHAQUE CLASSE\n");
        prompt.append("   - Ajouter TOUS les attributs du PlantUML\n");
        prompt.append("   - Utiliser les types : String, int, boolean, float (compatibles Modelio)\n");
        prompt.append("   - NE JAMAIS utiliser : Date, Integer, Boolean\n");
        prompt.append("   - Attendre la confirmation de création de classe avant d'ajouter les attributs\n\n");
        prompt.append("4️⃣ **Créer les Associations** : Utiliser les outils MCP POUR CHAQUE RELATION\n");
        prompt.append("   - Parser CHAQUE relation : -->, --|>, --o, --*, <|--\n");
        prompt.append("   - Créer les associations SEULEMENT après que toutes les classes existent\n");
        prompt.append("   - Définir les cardinalités appropriées (1, 0..1, 1..*, 0..*)\n");
        prompt.append("   - Nommer les relations de manière significative\n");
        prompt.append("   - 🚨 NE PAS IGNORER AUCUNE ASSOCIATION\n\n");

        prompt.append("📊 **CRITIQUE : PRODUIRE UNE SORTIE AS-BUILT**\n");
        prompt.append("Après création du modèle de domaine, générer :\n\n");
        prompt.append("### 1. DIAGRAMME PLANTUML AS-BUILT\n```plantuml\n@startuml MODELE_DOMAINE_AS_BUILT\n");
        prompt.append("' Générer le PlantUML EXACT de ce qui a été créé dans Modelio\n");
        prompt.append("class Utilisateur {\n  +nom: String\n  +email: String\n}\n");
        prompt.append("' Inclure TOUTES les classes, attributs et associations créés\n@enduml\n```\n\n");
        prompt.append("### 2. JSON DU MODÈLE DE DOMAINE STRUCTURÉ\n```json\n{\n  \"domain_model_created\": {\n");
        prompt.append("    \"package_uuid\": \"00000000-0000-0000-0000-000000000000\",\n");
        prompt.append("    \"classes\": [\n      {\n        \"nom\": \"Utilisateur\",\n");
        prompt.append("        \"uuid\": \"00000000-0000-0000-0000-000000000000\",\n");
        prompt.append("        \"attributs\": [{\"nom\": \"email\", \"type\": \"String\"}],\n");
        prompt.append("        \"exigences_liees\": [\"EXG-001\", \"EXG-003\"]\n      }\n    ],\n");
        prompt.append("    \"associations\": [\n      {\"de\": \"Utilisateur\", \"vers\": \"Commande\", \"type\": \"Association\", \"cardinalite\": \"1..*\"}\n    ]\n  }\n}\n```\n\n");

        prompt.append("## CRITICAL TYPE RULES\n");
        prompt.append("✅ ALLOWED: String, int, boolean, float\n");
        prompt.append("❌ FORBIDDEN: Date, Integer, Boolean, LocalDate\n");
        prompt.append("🔄 FALLBACK: Use String for complex types\n\n");
        prompt.append("## ASSOCIATION TYPES\n- --> = Association\n- --|> = Generalization\n- --o = Aggregation\n- --* = Composition\n\n");

        prompt.append("## RÈGLE DE TRAÇABILITÉ («Satisfait»)\n");
        prompt.append("🔗 Si, et seulement si, un UUID d'exigence valide est fourni dans ce prompt, vous pouvez créer un lien «Satisfait».\n");
        prompt.append("   - Appel MCP autorisé : `analyst_createRelation` avec relation_type=\"satisfy\", source_uuid=<UUID de l'élément>,\n");
        prompt.append("     target_uuid=<UUID de l'exigence>, module_name=\"ModelerModule\".\n");
        prompt.append("   - Sens de la relation : source = élément de modélisation, target = exigence.\n");
        prompt.append("   - Si aucun UUID valide n'est disponible, n'essayez PAS de retrouver l'exigence par nom et continuez sans ce lien.\n");
        prompt.append("   - L'absence de lien «Satisfait» ne doit jamais faire échouer la création des classes, attributs ou associations.\n\n");

        prompt.append("NE FOURNISSEZ PAS DE PROCÉDURE MANUELLE. START NOW: create packages, classes, attributes, then associations with MCP tools and return the as-built outputs only.");
        return prompt.toString();
    }

    // ------------------------------------------------------------------ Phase 2A: classes (chunk)

    static String createClassesChunkPrompt(
            String requirementsResult,
            List<Requirement> parsedRequirements,
            List<String> requirementUUIDs,
            List<String> classBlocks,
            int chunkIndex,
            int totalChunks) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("DEMANDE UTILISATEUR À EXÉCUTER MAINTENANT (PHASE 2A, lot ").append(chunkIndex).append("/").append(totalChunks).append(")\n");
        prompt.append("Objectif unique: créer dans Modelio les classes et attributs listés ci-dessous.\n");
        prompt.append("Interdictions absolues: ne pas exécuter `project_overview`, ne pas appeler `analyst_queryItems`, ne pas créer d'associations dans ce lot.\n");
        prompt.append("Workflow obligatoire pour chaque classe:\n");
        prompt.append("  1) Cherche la classe par nom avec `search_model` (type=class). Si elle existe, réutilise son UUID.\n");
        prompt.append("  2) Si elle n'existe pas, crée-la avec `uml_createElement`. Utilise uniquement l'UUID retourné.\n");
        prompt.append("  3) Pour chaque attribut/opération, vérifie d'abord avec `uml_getElementDetails` si le membre existe déjà. S'il existe, ne le recrée pas.\n");
        prompt.append("  4) Ajoute uniquement les attributs/opérations manquants avec `uml_addMember`.\n");
        prompt.append("IMPORTANT: les UUIDs utilisés pour `uml_addMember` doivent provenir UNIQUEMENT des réponses de `uml_createElement` ou `search_model` — jamais d'exigences.\n");
        prompt.append("Liens «Satisfait» (traçabilité): optionnels — à créer UNIQUEMENT si un UUID d'exigence valide est disponible dans le contexte ci-dessous. Ne jamais bloquer ni échouer si aucun UUID n'est disponible.\n");
        prompt.append("Si tu hésites, n'appelle pas `project_overview`: fais quand même les créations demandées.\n");
        prompt.append("Retour attendu: uniquement un compte-rendu as-built avec UUIDs réels.\n\n");

        if (requirementUUIDs != null && !requirementUUIDs.isEmpty()) {
            int uuidLimit = Math.min(requirementUUIDs.size(), PHASE2_CHUNK_MAX_REQUIREMENT_UUIDS);
            prompt.append("## UUIDS EXIGENCES (OPTIONNEL — pour liens «Satisfait» si pertinent)\n");
            for (int i = 0; i < uuidLimit; i++) {
                prompt.append("- ").append(requirementUUIDs.get(i)).append(System.lineSeparator());
            }
            if (requirementUUIDs.size() > uuidLimit) {
                prompt.append("... (").append(requirementUUIDs.size() - uuidLimit).append(" autres disponibles dans le projet)\n");
            }
            prompt.append("⚠️ Ces liens sont OPTIONNELS — ne jamais bloquer la création de classes à cause d'eux.\n\n");
        }

        prompt.append("## CLASSES DE CE LOT (PlantUML)\n```plantuml\n@startuml PROMPT_INPUT\n");
        for (String classBlock : classBlocks) {
            prompt.append(classBlock).append(System.lineSeparator());
        }
        prompt.append("@enduml\n```\n\nRetourner uniquement un compte-rendu as-built avec UUIDs réels des éléments créés.");
        return prompt.toString();
    }

    // ------------------------------------------------------------------ Phase 2B: associations (chunk)

    static String createAssociationsChunkPrompt(
            String requirementsResult,
            List<String> requirementUUIDs,
            List<String> classNames,
            List<String> relationLines,
            int chunkIndex,
            int totalChunks) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("DEMANDE UTILISATEUR À EXÉCUTER MAINTENANT (PHASE 2B, lot ").append(chunkIndex).append("/").append(totalChunks).append(")\n");
        prompt.append("Objectif unique: créer dans Modelio les associations listées ci-dessous.\n");
        prompt.append("Interdictions absolues: ne pas exécuter `project_overview`, ne pas appeler `analyst_queryItems`, ne pas recréer les classes sauf nécessité absolue.\n");
        prompt.append("Utiliser les classes déjà créées, puis créer les relations une par une.\n");
        prompt.append("Retour attendu: uniquement un compte-rendu as-built avec UUIDs réels.\n\n");

        if (requirementsResult != null && !requirementsResult.isBlank()) {
            prompt.append("## CONTEXTE EXIGENCES\n");
            prompt.append(requirementsResult.length() > 600
                    ? requirementsResult.substring(0, 600) + "\n... (tronqué)\n\n"
                    : requirementsResult + "\n\n");
        }
        if (requirementUUIDs != null && !requirementUUIDs.isEmpty()) {
            int uuidLimit = Math.min(requirementUUIDs.size(), PHASE2_CHUNK_MAX_REQUIREMENT_UUIDS);
            prompt.append("## UUIDS EXIGENCES DISPONIBLES POUR «SATISFAIT» SI REQUIS\n");
            for (int i = 0; i < uuidLimit; i++) {
                prompt.append("- ").append(requirementUUIDs.get(i)).append(System.lineSeparator());
            }
            if (requirementUUIDs.size() > uuidLimit) {
                prompt.append("... (").append(requirementUUIDs.size() - uuidLimit).append(" autres UUIDs disponibles dans le projet)\n");
            }
            prompt.append(System.lineSeparator());
        }
        if (classNames != null && !classNames.isEmpty()) {
            prompt.append("## CLASSES DÉJÀ CRÉÉES (RÉFÉRENCE NOMS)\n");
            for (String className : classNames) {
                prompt.append("- ").append(className).append(System.lineSeparator());
            }
            prompt.append(System.lineSeparator());
        }
        prompt.append("## RELATIONS DE CE LOT (PlantUML)\n```plantuml\n@startuml PROMPT_INPUT\n");
        for (String relationLine : relationLines) {
            prompt.append(relationLine).append(System.lineSeparator());
        }
        prompt.append("@enduml\n```\n\nRetourner uniquement un compte-rendu as-built avec UUIDs réels des relations créées.");
        return prompt.toString();
    }

    // ------------------------------------------------------------------ shared context appender

    static void appendRequirementsContext(
            StringBuilder prompt,
            String requirementsResult,
            List<Requirement> parsedRequirements,
            List<String> requirementUUIDs) {
        if (requirementsResult != null && !requirementsResult.isBlank()) {
            prompt.append("## CONTEXTE EXIGENCES CRÉÉES\n");
            prompt.append(requirementsResult.length() > 1200
                    ? requirementsResult.substring(0, 1200) + "\n... (tronqué)\n\n"
                    : requirementsResult + "\n\n");
        }
        if (requirementUUIDs != null && !requirementUUIDs.isEmpty()) {
            int uuidLimit = Math.min(requirementUUIDs.size(), PHASE2_CHUNK_MAX_REQUIREMENT_UUIDS);
            prompt.append("## UUIDS EXIGENCES À UTILISER POUR LES LIENS «SATISFAIT»\n");
            for (int i = 0; i < uuidLimit; i++) {
                prompt.append("- ").append(requirementUUIDs.get(i)).append(System.lineSeparator());
            }
            if (requirementUUIDs.size() > uuidLimit) {
                prompt.append("... (").append(requirementUUIDs.size() - uuidLimit).append(" autres UUIDs disponibles dans le projet)\n");
            }
            prompt.append(System.lineSeparator());
        }
        if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
            prompt.append("## APERÇU EXIGENCES\n");
            for (int i = 0; i < Math.min(parsedRequirements.size(), 8); i++) {
                Requirement req = parsedRequirements.get(i);
                prompt.append("- ").append(req.id).append(": ")
                        .append(req.description.length() > 90
                                ? req.description.substring(0, 90) + "..."
                                : req.description)
                        .append(System.lineSeparator());
            }
            prompt.append(System.lineSeparator());
        }
    }

    // ------------------------------------------------------------------ Phase 3: use cases

    static String createUseCasesPrompt(String analysisResults, String requirementsResult, String classesResult,
            List<Requirement> parsedRequirements, List<String> requirementUUIDs) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("🇫🇷 Vous êtes un analyste de cas d'usage Modelio. Votre mission : créer TOUS les cas d'usage et acteurs en français.\n\n");
        prompt.append("## MISSION PHASE 3 : CAS D'USAGE & ACTEURS\n");
        prompt.append("🎯 Créer un modèle de cas d'usage complet en français avec acteurs et scénarios.\n");
        prompt.append("🚨 Utiliser les outils MCP pour créer acteurs, cas d'usage et leurs associations.\n\n");
        prompt.append("🚫 INTERDICTIONS ABSOLUES : ne pas appeler `analyst_queryItems` et ne jamais rechercher une exigence par nom, catégorie ou mot-clé.\n");
        prompt.append("🔗 Les liens «Satisfait» sont OPTIONNELS : utilisez uniquement un UUID d'exigence fourni explicitement dans ce prompt; sinon sautez le lien sans erreur.\n\n");

        if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
            prompt.append("## CONTEXTE FONCTIONNEL - APERÇU DES EXIGENCES\n");
            prompt.append("Baser les cas d'usage sur ces exigences fonctionnelles :\n");
            for (int i = 0; i < Math.min(parsedRequirements.size(), 12); i++) {
                Requirement req = parsedRequirements.get(i);
                if (req.category.contains("Fonctionnel") || req.category.contains("Interface")
                        || req.category.contains("Intégration") || req.category.contains("Fonctionnelle")
                        || req.category.contains("UI") || req.category.contains("Integration")) {
                    prompt.append(String.format("- **%s** (%s): %s\n", req.id, req.category,
                            req.description.length() > 100 ? req.description.substring(0, 100) + "..." : req.description));
                }
            }
            prompt.append("\n🔗 LIEN : Créer des cas d'usage qui couvrent ces exigences fonctionnelles; les liens «Satisfait» restent optionnels.\n\n");
        }

        prompt.append("## CONTEXTE DES PHASES PRÉCÉDENTES\n### Exigences Créées (Phase 1) :\n");
        if (requirementsResult != null && requirementsResult.length() > 2000) {
            prompt.append(requirementsResult, 0, 2000).append("\n... (contexte exigences tronqué)\n\n");
        } else {
            prompt.append(requirementsResult).append("\n\n");
        }

        if (requirementUUIDs != null && !requirementUUIDs.isEmpty()) {
            prompt.append("## UUIDS DES EXIGENCES DISPONIBLES POUR LIEN «SATISFAIT» (OPTIONNEL)\n");
            prompt.append("NE PAS chercher les exigences par mots-clés! Utiliser directement ces UUIDs si pertinent:\n\n");
            for (int i = 0; i < requirementUUIDs.size(); i++) {
                prompt.append(String.format("- UUID exigence #%d: %s\n", (i + 1), requirementUUIDs.get(i)));
            }
            prompt.append("\nSi un lien «Satisfait» est créé, utiliser analyst_createRelation avec relation_type=\"satisfy\",");
            prompt.append(" source_uuid=<UUID cas d'usage>, target_uuid=<l'un des UUIDs exigences ci-dessus>.\n");
            prompt.append("⚠️ CES LIENS SONT OPTIONNELS — ne jamais bloquer ni échouer si aucun UUID d'exigence n'est disponible.\n\n");
        }

        prompt.append("🔍 **EXTRAIRE LE JSON DES EXIGENCES** : Rechercher la structure JSON dans les résultats ci-dessus\n\n");
        prompt.append("### Modèle de Domaine Créé (Phase 2) :\n");
        if (classesResult != null && classesResult.length() > 2000) {
            prompt.append(classesResult, 0, 2000).append("\n... (contexte classes tronqué)\n\n");
        } else {
            prompt.append(classesResult).append("\n\n");
        }
        prompt.append("🔍 **EXTRAIRE LE MODÈLE DE DOMAINE** : Rechercher le PlantUML MODELE_DOMAINE_AS_BUILT et JSON ci-dessus\n");
        prompt.append("🎯 **UTILISER LE MODÈLE AS-BUILT** : Baser les cas d'usage sur les classes réellement créées, pas le PlantUML original\n\n");

        prompt.append("## RÉFÉRENCE PLANTUML ORIGINAL\n```plantuml\n");
        prompt.append(PlantUmlParser.preparePlantUmlForPrompt(analysisResults));
        prompt.append("\n```\n\n");

        prompt.append("## SÉQUENCE D'EXÉCUTION (OBLIGATOIRE)\n");
        prompt.append("1️⃣ **Créer le Package Cas d'Usage** : Utiliser les outils MCP\n");
        prompt.append("   - Créer le package 'Cas d Usage'\n   - Rapporter l'UUID du package\n\n");
        prompt.append("2️⃣ **Créer les Acteurs** : Utiliser les outils MCP\n");
        prompt.append("   - Identifier tous les types d'utilisateurs à partir des exigences et du modèle de domaine\n");
        prompt.append("   - Créer les acteurs : Utilisateur, Administrateur, Système Externe, etc.\n");
        prompt.append("   - Placer dans le package 'Cas d Usage'\n   - Rapporter l'UUID de chaque acteur\n\n");
        prompt.append("3️⃣ **Créer les Cas d'Usage** : Utiliser les outils MCP\n");
        prompt.append("   - Extraire les fonctionnalités principales des exigences et classes\n");
        prompt.append("   - Créer les cas d'usage : 'Gérer les Utilisateurs', 'Traiter les Données', etc.\n");
        prompt.append("   - Lier aux exigences d'implémentation uniquement si un UUID d'exigence valide est déjà fourni dans ce prompt\n");
        prompt.append("   - Placer dans le package 'Cas d Usage'\n   - Rapporter l'UUID de chaque cas d'usage\n");
        prompt.append("   - OPTIONNEL : si un UUID d'exigence valide est disponible, matérialiser le lien «Satisfait» avec `analyst_createRelation`\n");
        prompt.append("     (relation_type=\"satisfy\", source_uuid=<UUID du cas d'usage>, target_uuid=<UUID de l'exigence>, module_name=\"ModelerModule\").\n");
        prompt.append("     Si aucun UUID valide n'est disponible, ne cherchez pas l'exigence par nom et continuez sans erreur.\n\n");
        prompt.append("4️⃣ **Créer les Associations Acteur-Cas d'Usage** : Utiliser les outils MCP\n");
        prompt.append("   - Connecter chaque acteur aux cas d'usage pertinents\n");
        prompt.append("   - Utiliser les types d'association appropriés\n");
        prompt.append("   - Ajouter les relations <<include>> et <<extend>> si nécessaire\n");
        prompt.append("   - Maintenir la traçabilité vers les exigences\n\n");

        prompt.append("📊 **CRITICAL: PRODUCE VALIDATION & OUTPUTS**\n");
        prompt.append("After creating use cases, generate:\n\n");
        prompt.append("### 1. REQUIREMENTS COVERAGE VALIDATION\n```\nREQUIREMENTS COVERAGE ANALYSIS:\n");
        prompt.append("- REQ-001: Covered by [Login, User Management] use cases\n");
        prompt.append("- REQ-002: Covered by [Data Processing] use case\n");
        prompt.append("- REQ-XXX: NOT COVERED - Missing use case needed\n\nCOVERAGE RATE: 85% (11/13 requirements covered)\n```\n\n");
        prompt.append("### 2. USE CASE DIAGRAM PLANTUML\n```plantuml\n@startuml USE_CASES_DIAGRAM\n");
        prompt.append("actor User\nactor Admin\nrectangle System {\n  usecase \"Login\" as UC1\n  usecase \"Manage Data\" as UC2\n}\n");
        prompt.append("User --> UC1\nAdmin --> UC2\n@enduml\n```\n\n");
        prompt.append("### 3. USE CASES SUMMARY JSON\n```json\n{\n  \"use_cases_created\": {\n");
        prompt.append("    \"package_uuid\": \"00000000-0000-0000-0000-000000000000\",\n");
        prompt.append("    \"actors\": [{\"name\": \"User\", \"uuid\": \"00000000-0000-0000-0000-000000000000\"}],\n");
        prompt.append("    \"use_cases\": [\n      {\n        \"name\": \"Login\",\n");
        prompt.append("        \"uuid\": \"00000000-0000-0000-0000-000000000000\",\n");
        prompt.append("        \"actors\": [\"User\"],\n        \"linked_requirements\": [\"REQ-001\"],\n");
        prompt.append("        \"domain_classes_used\": [\"User\", \"Authentication\"]\n      }\n    ],\n");
        prompt.append("    \"coverage_rate\": 0.85,\n    \"uncovered_requirements\": [\"REQ-007\"]\n  }\n}\n```\n\n");

        prompt.append("## ACTEURS TYPIQUES À CONSIDÉRER\n");
        prompt.append("- Utilisateurs principaux (qui vont utiliser le système)\n");
        prompt.append("- Administrateurs (qui gèrent le système)\n");
        prompt.append("- Systèmes externes (APIs, bases de données)\n");
        prompt.append("- Parties prenantes (managers, auditeurs)\n\n");
        prompt.append("## CAS D'USAGE TYPIQUES À CONSIDÉRER\n");
        prompt.append("- Gestion des utilisateurs (inscription, connexion, profil)\n");
        prompt.append("- Opérations sur les données (créer, lire, modifier, supprimer)\n");
        prompt.append("- Reporting et analytiques\n");
        prompt.append("- Administration système\n");
        prompt.append("- Intégration avec systèmes externes\n\n");

        prompt.append("## EXIGENCES DE TRAÇABILITÉ\n");
        prompt.append("🔗 Lier les cas d'usage aux exigences qui définissent leurs fonctionnalités\n");
        prompt.append("🔗 Référencer les classes du modèle de domaine manipulées par les cas d'usage\n");
        prompt.append("🔗 Assurer une couverture complète des exigences fonctionnelles\n");
        prompt.append("⚠️ RAPPEL : un lien de traçabilité vers une exigence ne peut être créé que si un UUID d'exigence valide est fourni dans ce prompt.\n");
        prompt.append("   Ne jamais appeler `analyst_queryItems` ni rechercher une exigence par nom; si l'UUID manque, conservez la traçabilité uniquement dans le compte-rendu/JSON et continuez.\n\n");

        prompt.append("NE FOURNISSEZ PAS DE PROCÉDURE MANUELLE. COMMENCEZ MAINTENANT : créez le package cas d'usage, les acteurs, les cas d'usage, puis les associations avec les outils MCP et retournez uniquement les résultats as-built.");
        return prompt.toString();
    }

    // ------------------------------------------------------------------ legacy (compatibility)

    static String createLegacyModelGenerationPrompt(String plantUMLContent, String requirementsDocuments, List<Requirement> parsedRequirements) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("🇫🇷 Vous êtes un assistant de modélisation Modelio. Créez un modèle UML complet en français à partir du PlantUML.\n\n");
        if (parsedRequirements != null && !parsedRequirements.isEmpty()) {
            prompt.append("## Exigences Parsées Disponibles\n");
            for (Requirement req : parsedRequirements) {
                prompt.append(String.format("- **%s**: %s (Catégorie: %s, Priorité: %s)\n",
                        req.id, req.description, req.category, req.priority));
            }
            prompt.append("\n🚨 Créer ces exigences dans Modelio en utilisant les outils MCP.\n\n");
        }
        prompt.append("## PlantUML à Traiter\n```plantuml\n");
        prompt.append(plantUMLContent);
        prompt.append("\n```\n\n");
        prompt.append("## SÉQUENCE D'EXÉCUTION\n");
        prompt.append("1. Créer les packages (Exigences, Cas d'Usage, Modèle de Domaine)\n");
        prompt.append("2. Créer les exigences en utilisant les outils MCP\n");
        prompt.append("3. Créer les classes avec leurs attributs\n");
        prompt.append("4. Créer les associations entre les classes\n");
        prompt.append("5. Créer les cas d'usage et les acteurs\n\n");
        prompt.append("## RÈGLES CRITIQUES\n");
        prompt.append("- Utiliser les outils MCP pour CHAQUE création d'élément\n");
        prompt.append("- Créer d'abord les classes, puis les attributs, puis les associations\n");
        prompt.append("- Utiliser les types : String, int, boolean, float (compatibles Modelio)\n");
        prompt.append("- NE JAMAIS ignorer les associations - parser TOUTES les relations du PlantUML\n");
        prompt.append("- TOUTES les descriptions et noms doivent être en français\n");
        prompt.append("- 🚨 OBLIGATOIRE : chaque élément (classe, acteur, cas d'usage) qui répond à une exigence DOIT être relié\n");
        prompt.append("  à celle-ci via l'outil MCP `analyst_createRelation` (relation_type=\"satisfy\", source_uuid=<UUID élément>,\n");
        prompt.append("  target_uuid=<UUID exigence>, module_name=\"ModelerModule\")\n\n");
        prompt.append("COMMENCEZ MAINTENANT : Créez le modèle complet en utilisant les outils MCP.");
        return prompt.toString();
    }
}
