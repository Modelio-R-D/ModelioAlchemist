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

    // ------------------------------------------------------------------ Phase 3A: classes (full)

    static String createClassesPrompt(String analysisResults, String requirementsResult, List<Requirement> parsedRequirements,
            String domainPackageUuid) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("🇫🇷 Vous êtes un modélisateur de domaine Modelio. Votre mission : créer TOUTES les classes et associations en français.\n\n");
        prompt.append("## MISSION PHASE 3 : CLASSES & ASSOCIATIONS\n");
        prompt.append("🎯 Créer un modèle de domaine complet en français à partir du PlantUML avec toutes les relations.\n");
        prompt.append("🚨 Utiliser les outils MCP pour créer classes, attributs et associations.\n\n");
        prompt.append("🚫 INTERDICTIONS ABSOLUES : ne pas appeler `analyst_queryItems`, ne jamais rechercher une exigence par nom, mot-clé ou texte libre,\n");
        prompt.append("   et ne jamais créer de lien «Satisfait» dans cette phase.\n\n");

        prompt.append("## TRAÇABILITÉ - EXIGENCES CRÉÉES\n");
        if (requirementsResult != null && !requirementsResult.trim().isEmpty()) {
            if (requirementsResult.length() > 1500) {
                prompt.append(requirementsResult, 0, 1500).append("\n... (contexte exigences tronqué)\n\n");
            } else {
                prompt.append(requirementsResult).append("\n\n");
            }
            prompt.append("🧭 Utiliser les exigences comme contexte métier pour dériver les classes, sans créer de lien de traçabilité dans cette phase.\n\n");
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
        prompt.append("1️⃣ **Package 'Modèle de Domaine' DÉJÀ CRÉÉ** — UUID EXACT À RÉUTILISER : ").append(domainPackageUuid).append("\n");
        prompt.append("   🚫 NE JAMAIS appeler `uml_createElement` pour créer ce package — il existe déjà avec cet UUID.\n");
        prompt.append("   Si un sous-package est nécessaire (ex. Business/Technical/Securite), utiliser IMPÉRATIVEMENT `uml_findOrCreateElement`\n");
        prompt.append("   (name=<nom>, parent_uuid=").append(domainPackageUuid).append(") — jamais `uml_createElement` pour un package.\n\n");
        prompt.append("2️⃣ **Créer les Classes** : UN SEUL appel `uml_createClassWithMembers` PAR CLASSE\n");
        prompt.append("   - Parser TOUTES les classes du PlantUML\n");
        prompt.append("   - Conserver les noms exacts du PlantUML\n");
        prompt.append("   - Chaque classe : `uml_createClassWithMembers` (name, parent_uuid=").append(domainPackageUuid).append(" ou un sous-package obtenu via `uml_findOrCreateElement`,\n");
        prompt.append("     attributes=[{name, type, visibility:\"public\"}, ...], operations=[{name, visibility:\"public\"}, ...]) — TOUS les attributs et opérations de cette classe DANS LE MÊME appel,\n");
        prompt.append("     PAS de `uml_createElement` + `uml_addMember` séparés. Types autorisés : String, int, boolean, float. INTERDITS : Date, Integer, Boolean.\n");
        prompt.append("   - Si `duplicate_class_name` est renvoyé, réutiliser l'`existing_uuid` fourni au lieu de recréer la classe\n");
        prompt.append("   - Rapporter l'UUID de chaque classe\n");
        prompt.append("   - Ne créer AUCUN lien «Satisfait» : la traçabilité vers les exigences est réservée aux cas d'usage.\n\n");
        prompt.append("3️⃣ **Créer les Associations** : UN SEUL appel `uml_createAssociationsBulk` pour TOUTES les relations de ce lot\n");
        prompt.append("   - Parser CHAQUE relation du PlantUML : -->, --|>, --o, --*, <|--\n");
        prompt.append("   - Construire le tableau `relations` complet (relation_type, source_uuid, target_uuid, name, source_role, target_role, source_multiplicity, target_multiplicity)\n");
        prompt.append("     et l'envoyer en UN SEUL appel — PAS un `uml_createStaticRelation` par relation.\n");
        prompt.append("   - Créer les associations SEULEMENT après que toutes les classes existent\n");
        prompt.append("   - Un item du batch qui échoue (UUID introuvable) n'annule pas les autres — vérifier `failed[]` dans la réponse et le signaler sans bloquer.\n");
        prompt.append("   - 🚨 NE PAS IGNORER AUCUNE ASSOCIATION\n\n");
        prompt.append("4️⃣ **Créer le Diagramme de Classes** : OBLIGATOIRE, en dernier\n");
        prompt.append("   - `uml_createDiagram` : diagramme de classes nommé 'Modèle de Domaine' dans le package 'Modèle de Domaine'\n");
        prompt.append("   - Puis `uml_unmaskInDiagram` pour CHAQUE classe créée (UUID du diagramme + UUID de la classe)\n");
        prompt.append("   - Les associations entre classes affichées apparaissent automatiquement\n");
        prompt.append("   - Si un appel diagramme échoue, continuer sans bloquer et le signaler\n\n");

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
        prompt.append("        \"attributs\": [{\"nom\": \"email\", \"type\": \"String\"}]\n      }\n    ],\n");
        prompt.append("    \"associations\": [\n      {\"de\": \"Utilisateur\", \"vers\": \"Commande\", \"type\": \"Association\", \"cardinalite\": \"1..*\"}\n    ]\n  }\n}\n```\n\n");

        prompt.append("## CRITICAL TYPE RULES\n");
        prompt.append("✅ ALLOWED: String, int, boolean, float\n");
        prompt.append("❌ FORBIDDEN: Date, Integer, Boolean, LocalDate\n");
        prompt.append("🔄 FALLBACK: Use String for complex types\n\n");
        prompt.append("## ASSOCIATION TYPES\n- --> = Association\n- --|> = Generalization\n- --o = Aggregation\n- --* = Composition\n\n");

        prompt.append("## RÈGLE DE TRAÇABILITÉ\n");
        prompt.append("🚫 Aucun lien «Satisfait» ne doit être créé dans le modèle de domaine.\n");
        prompt.append("   Les relations de traçabilité exigence ↔ cas d'usage seront créées dans la phase cas d'usage uniquement.\n\n");

        prompt.append("NE FOURNISSEZ PAS DE PROCÉDURE MANUELLE. START NOW: create packages, classes, attributes, then associations with MCP tools and return the as-built outputs only.");
        return prompt.toString();
    }

    // ------------------------------------------------------------------ Phase 3A: classes (chunk)

    static String createClassesChunkPrompt(
            String requirementsResult,
            List<Requirement> parsedRequirements,
            List<String> requirementUUIDs,
            List<String> classBlocks,
            int chunkIndex,
            int totalChunks,
            String domainPackageUuid) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("DEMANDE UTILISATEUR À EXÉCUTER MAINTENANT (PHASE 3A, lot ").append(chunkIndex).append("/").append(totalChunks).append(")\n");
        prompt.append("Objectif unique: créer dans Modelio les classes listées ci-dessous AVEC TOUS LEURS ATTRIBUTS ET OPÉRATIONS.\n");
        prompt.append("📦 Package racine du modèle de domaine DÉJÀ CRÉÉ, UUID EXACT À RÉUTILISER : ").append(domainPackageUuid).append("\n");
        prompt.append("   🚫 NE JAMAIS appeler `uml_createElement` pour créer un package nommé 'Modèle de Domaine' — il existe déjà avec cet UUID.\n");
        prompt.append("   Placer directement les classes de ce lot sous cet UUID (parentUuid=").append(domainPackageUuid)
                .append(") SAUF si elles appartiennent logiquement à un sous-package (ex. Business/Technical/Securite).\n");
        prompt.append("   Si un sous-package est nécessaire, l'outil `uml_findOrCreateElement` (name=<nom>, parent_uuid=")
                .append(domainPackageUuid).append(") DOIT être utilisé — jamais `uml_createElement` pour un package : \n");
        prompt.append("   il est idempotent (renvoie l'UUID existant si le sous-package a déjà été créé par un autre lot) et évite les packages dupliqués.\n");
        prompt.append("🚨 RÈGLE N°1 — Chaque classe DOIT être créée en UN SEUL appel `uml_createClassWithMembers`, avec TOUS ses attributs ET TOUTES ses opérations\n");
        prompt.append("   RÉELLEMENT listés dans le bloc PlantUML de cette classe passés dans les tableaux `attributes`/`operations` de cet appel — jamais `uml_createElement` + `uml_addMember` un par un.\n");
        prompt.append("   Si le bloc PlantUML d'une classe ne liste AUCUN attribut (classe de service avec seulement des opérations, ex. AuthService), c'est normal : passer `attributes: []` et remplir seulement `operations`, ne bloque JAMAIS pour cette raison.\n");
        prompt.append("   L'échec à éviter est d'omettre un attribut ou une opération QUI EST LISTÉ dans le PlantUML, pas l'absence d'attributs quand le PlantUML n'en liste aucun.\n");
        prompt.append("Interdictions absolues: ne pas exécuter `project_overview`, ne pas appeler `analyst_queryItems`, ne créer AUCUNE association/dépendance/généralisation dans ce lot\n");
        prompt.append("   (`uml_createStaticRelation`, `uml_createDependency`, `uml_createBehavioralRelation` sont INTERDITS ici — ils seront faits dans la phase 3B).\n");
        prompt.append("⛔ NE PAS appeler `uml_getElementDetails` sur une classe que tu viens de créer: elle est vide par définition. Chaque appel inutile te fait perdre le budget nécessaire.\n");
        prompt.append("Workflow obligatoire, classe par classe (terminer complètement une classe avant de passer à la suivante):\n");
        prompt.append("  1) `search_model` (type=class) sur le nom exact. Si trouvée, réutilise son UUID directement — ne rappelle pas `uml_createClassWithMembers` pour cette classe.\n");
        prompt.append("  2) Sinon, UN SEUL appel `uml_createClassWithMembers` (name=<nom exact>, parent_uuid=<UUID du package>,\n");
        prompt.append("     attributes=[{name, type, visibility:\"public\"}, ...], operations=[{name, visibility:\"public\"}, ...]) avec TOUS les membres du bloc PlantUML de cette classe.\n");
        prompt.append("     Si l'appel échoue avec `duplicate_class_name`, réutilise l'`existing_uuid` retourné — ne recrée pas la classe.\n");
        prompt.append("Types autorisés pour les attributs: String, int, boolean, float. INTERDITS: Date, Integer, Boolean, LocalDate (remplacer par String).\n");
        prompt.append("Budget: un seul appel par classe libère largement le budget nécessaire pour couvrir tout le lot sans jamais s'arrêter à mi-chemin. Compte-rendu final: nombre d'attributs/opérations réellement ajoutés par classe.\n");
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

    // ------------------------------------------------------------------ Phase 3B: associations (chunk)

    static String createAssociationsChunkPrompt(
            String requirementsResult,
            List<String> requirementUUIDs,
            List<String> classNames,
            List<String> relationLines,
            int chunkIndex,
            int totalChunks) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("DEMANDE UTILISATEUR À EXÉCUTER MAINTENANT (PHASE 3B, lot ").append(chunkIndex).append("/").append(totalChunks).append(")\n");
        prompt.append("Objectif unique: créer dans Modelio les associations listées ci-dessous.\n");
        prompt.append("Interdictions absolues: ne pas exécuter `project_overview`, ne pas appeler `analyst_queryItems`, ne pas recréer les classes sauf nécessité absolue.\n");
        prompt.append("🚨 UN SEUL appel `uml_createAssociationsBulk` pour TOUT ce lot : résoudre d'abord l'UUID de chaque classe référencée\n");
        prompt.append("   (via `search_model` type=class si nécessaire), puis construire le tableau `relations` complet\n");
        prompt.append("   (relation_type, source_uuid, target_uuid, name, source_role, target_role, source_multiplicity, target_multiplicity) et l'envoyer en un seul appel.\n");
        prompt.append("   PAS de `uml_createStaticRelation` par relation, et JAMAIS `uml_updateAssociationEnd` : multiplicités et rôles se passent directement dans le batch.\n");
        prompt.append("   Un item du batch qui échoue (UUID introuvable) n'annule pas les autres — vérifier `failed[]` dans la réponse et le signaler sans bloquer.\n");
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

    // ------------------------------------------------------------------ Phase 3C: class diagram

    static String createClassDiagramPrompt(List<String> classNames) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("DEMANDE UTILISATEUR À EXÉCUTER MAINTENANT (PHASE 3C : DIAGRAMME DE CLASSES)\n");
        prompt.append("Objectif unique: créer UN diagramme de classes et y afficher les classes du modèle de domaine.\n");
        prompt.append("Interdictions absolues: ne pas exécuter `project_overview`, ne pas appeler `analyst_queryItems`,\n");
        prompt.append("   ne créer AUCUNE classe, AUCUN attribut et AUCUNE association — tout existe déjà.\n\n");
        prompt.append("Séquence obligatoire:\n");
        prompt.append("  1) `uml_findPackage` (ou `search_model` type=package) pour retrouver l'UUID du package 'Modèle de Domaine'.\n");
        prompt.append("  2) `list_diagrams` (type_filter=ClassDiagram) : si un diagramme nommé 'Modèle de Domaine' existe déjà, réutiliser son UUID\n");
        prompt.append("     et passer directement à l'étape 3 — NE PAS en créer un second. Sinon, `uml_createDiagram` pour le créer\n");
        prompt.append("     (diagramme de classes nommé 'Modèle de Domaine', avec ce package comme propriétaire) et conserver l'UUID retourné.\n");
        prompt.append("  3) Pour CHAQUE classe listée ci-dessous : `search_model` (type=class) pour obtenir son UUID,\n");
        prompt.append("     puis `uml_unmaskInDiagram` avec l'UUID du diagramme et l'UUID de la classe.\n");
        prompt.append("  4) Les associations entre classes affichées apparaissent automatiquement : ne pas les recréer.\n");
        prompt.append("Si un `uml_unmaskInDiagram` échoue, continuer avec les classes suivantes sans bloquer.\n");
        prompt.append("Retour attendu: UUID du diagramme et liste des classes effectivement affichées.\n\n");

        prompt.append("## CLASSES À AFFICHER\n");
        if (classNames != null) {
            for (String className : classNames) {
                prompt.append("- ").append(className).append(System.lineSeparator());
            }
        }
        return prompt.toString();
    }

    // ------------------------------------------------------------------ Phase 2C: use case diagram (deterministic recovery)

    /**
     * Recovery prompt used when the main use-cases agent didn't create its diagram (a single agent
     * handling actors + use cases + associations + diagram in one pass can run out of budget before
     * the last step). Looks up actors/use cases itself via search_model — deliberately doesn't rely
     * on names parsed from the main agent's report, which may be incomplete or malformed.
     */
    static String createUseCaseDiagramPrompt(String useCasesPackageUuid) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("DEMANDE UTILISATEUR À EXÉCUTER MAINTENANT (PHASE 2C : DIAGRAMME DE CAS D'USAGE)\n");
        prompt.append("Objectif unique: créer UN diagramme de cas d'usage et y afficher les acteurs et cas d'usage du package ci-dessous.\n");
        prompt.append("Interdictions absolues: ne pas exécuter `project_overview`, ne pas appeler `analyst_queryItems`,\n");
        prompt.append("   ne créer AUCUN acteur, AUCUN cas d'usage et AUCUNE association — tout existe déjà.\n\n");
        prompt.append("Package Cas d'Usage, UUID EXACT : ").append(useCasesPackageUuid).append("\n\n");
        prompt.append("Séquence obligatoire:\n");
        prompt.append("  1) `list_diagrams` (type_filter=UseCaseDiagram, SANS name_filter) : parcourir TOUS les résultats.\n");
        prompt.append("     Si l'un d'eux a pour propriétaire (owner) le package UUID ci-dessus, réutiliser SON UUID — QUEL QUE SOIT SON NOM —\n");
        prompt.append("     et passer directement à l'étape 2 — NE PAS en créer un second. Sinon SEULEMENT, `uml_createDiagram` pour le créer\n");
        prompt.append("     (diagramme de cas d'usage nommé 'Cas d'Usage', avec le package ci-dessus comme propriétaire) et conserver l'UUID retourné.\n");
        prompt.append("  2) `search_model` (type=actor, owner_uuid=").append(useCasesPackageUuid).append(") ET `search_model` (type=usecase, owner_uuid=")
                .append(useCasesPackageUuid).append(") pour lister TOUS les acteurs et cas d'usage déjà créés sous ce package.\n");
        prompt.append("  3) Pour CHAQUE acteur et CHAQUE cas d'usage trouvé : `uml_unmaskInDiagram` avec l'UUID du diagramme et son UUID.\n");
        prompt.append("  4) Les associations acteur-cas d'usage affichées apparaissent automatiquement : ne pas les recréer.\n");
        prompt.append("Si un `uml_unmaskInDiagram` échoue, continuer avec les éléments suivants sans bloquer.\n");
        prompt.append("Retour attendu: UUID du diagramme et liste des acteurs/cas d'usage effectivement affichés.\n");
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

    // ------------------------------------------------------------------ Phase 2: use cases

    static String createUseCasesPrompt(String analysisResults, String requirementsResult, String classesResult,
            List<Requirement> parsedRequirements, List<String> requirementUUIDs, String useCasesPackageUuid) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("🇫🇷 Vous êtes un analyste de cas d'usage Modelio. Votre mission : créer TOUS les cas d'usage et acteurs en français.\n\n");
        prompt.append("## MISSION PHASE 2 : CAS D'USAGE & ACTEURS\n");
        prompt.append("🎯 Créer un modèle de cas d'usage complet en français avec acteurs et scénarios.\n");
        prompt.append("🚨 Utiliser les outils MCP pour créer acteurs, cas d'usage et leurs associations.\n\n");
        prompt.append("🚫 INTERDICTIONS ABSOLUES : ne pas appeler `analyst_queryItems` et ne jamais rechercher une exigence par nom, catégorie ou mot-clé.\n");
        prompt.append("🔗 TRAÇABILITÉ OBLIGATOIRE : chaque cas d'usage DOIT être rattaché à au moins une exigence (voir `linked_requirements` plus bas) —\n");
        prompt.append("   choisir uniquement parmi les identifiants d'exigence fournis explicitement dans ce prompt, jamais par recherche.\n\n");

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
            prompt.append("\n🔗 LIEN OBLIGATOIRE : créer des cas d'usage qui couvrent ces exigences fonctionnelles ET déclarer ce lien dans `linked_requirements`.\n\n");
        }

        prompt.append("## CONTEXTE DES PHASES PRÉCÉDENTES\n### Exigences Créées (Phase 1) :\n");
        if (requirementsResult != null && requirementsResult.length() > 2000) {
            prompt.append(requirementsResult, 0, 2000).append("\n... (contexte exigences tronqué)\n\n");
        } else {
            prompt.append(requirementsResult).append("\n\n");
        }

        if (requirementUUIDs != null && !requirementUUIDs.isEmpty()) {
            prompt.append("## UUIDS EXIGENCES (référence — pour information uniquement)\n");
            prompt.append("NE PAS chercher les exigences par mots-clés! Ces UUIDs confirment que les exigences ci-dessus existent bien dans Modelio:\n\n");
            for (int i = 0; i < requirementUUIDs.size(); i++) {
                prompt.append(String.format("- UUID exigence #%d: %s\n", (i + 1), requirementUUIDs.get(i)));
            }
            prompt.append("\n🚨 RAPPEL : dans le JSON as-built, `linked_requirements` de CHAQUE cas d'usage doit contenir au moins un identifiant\n");
            prompt.append("   (EXG-XXX) parmi les exigences listées ci-dessus — jamais vide. NE PAS appeler `analyst_createRelation` vous-même ;\n");
            prompt.append("   la création du lien «Satisfait» à partir de ce champ est faite automatiquement après coup.\n\n");
        }

        prompt.append("🔍 **EXTRAIRE LE JSON DES EXIGENCES** : Rechercher la structure JSON dans les résultats ci-dessus\n\n");
        if (classesResult != null && !classesResult.isBlank()) {
            prompt.append("### Modèle de Domaine Déjà Créé (contexte optionnel) :\n");
            if (classesResult.length() > 2000) {
                prompt.append(classesResult, 0, 2000).append("\n... (contexte classes tronqué)\n\n");
            } else {
                prompt.append(classesResult).append("\n\n");
            }
            prompt.append("🔍 **EXTRAIRE LE MODÈLE DE DOMAINE** : Rechercher le PlantUML MODELE_DOMAINE_AS_BUILT et JSON ci-dessus\n");
            prompt.append("🎯 **UTILISER LE MODÈLE AS-BUILT SI DISPONIBLE** : Sinon, baser les cas d'usage sur les exigences et le PlantUML original\n\n");
        }

        prompt.append("## RÉFÉRENCE PLANTUML ORIGINAL\n```plantuml\n");
        prompt.append(PlantUmlParser.preparePlantUmlForPrompt(analysisResults));
        prompt.append("\n```\n\n");

        prompt.append("## SÉQUENCE D'EXÉCUTION (OBLIGATOIRE)\n");
        prompt.append("1️⃣ **Package Cas d'Usage DÉJÀ CRÉÉ** — UUID EXACT À RÉUTILISER : ").append(useCasesPackageUuid).append("\n");
        prompt.append("   🚫 NE JAMAIS appeler `uml_createElement` pour créer un package 'Cas d'Usage' (ou toute variante orthographique) — il existe déjà avec cet UUID.\n");
        prompt.append("   Si un sous-package est vraiment nécessaire, utiliser IMPÉRATIVEMENT `uml_findOrCreateElement` (name=<nom>, parent_uuid=")
                .append(useCasesPackageUuid).append(") — jamais `uml_createElement` pour un package.\n\n");
        prompt.append("2️⃣ **Créer les Acteurs** : Utiliser les outils MCP\n");
        prompt.append("   - Identifier tous les types d'utilisateurs à partir des exigences et, si disponible, du modèle de domaine\n");
        prompt.append("   - Créer les acteurs : Utilisateur, Administrateur, Système Externe, etc.\n");
        prompt.append("   - Placer sous parentUuid=").append(useCasesPackageUuid).append("\n   - Rapporter l'UUID de chaque acteur\n\n");
        prompt.append("3️⃣ **Créer les Cas d'Usage** : Utiliser les outils MCP\n");
        prompt.append("   - Extraire les fonctionnalités principales des exigences et du PlantUML de référence\n");
        prompt.append("   - Créer les cas d'usage : 'Gérer les Utilisateurs', 'Traiter les Données', etc.\n");
        prompt.append("   - Placer sous parentUuid=").append(useCasesPackageUuid).append("\n   - Rapporter l'UUID de chaque cas d'usage\n");
        prompt.append("   🚨 OBLIGATOIRE — TRAÇABILITÉ : dans le JSON as-built (section suivante), le champ `linked_requirements` de\n");
        prompt.append("      CHAQUE cas d'usage DOIT contenir AU MOINS UN identifiant d'exigence (ex. \"EXG-003\") parmi ceux fournis dans ce prompt.\n");
        prompt.append("      Choisir l'exigence la plus pertinente même si le lien n'est pas parfait — ne JAMAIS laisser `linked_requirements` vide.\n");
        prompt.append("      NE PAS appeler `analyst_createRelation` vous-même : la création réelle du lien «Satisfait» à partir de ce champ\n");
        prompt.append("      est faite automatiquement après coup, de façon déterministe — un appel manuel créerait un doublon.\n\n");
        prompt.append("4️⃣ **Créer les Associations Acteur-Cas d'Usage** : Utiliser les outils MCP\n");
        prompt.append("   - Connecter chaque acteur aux cas d'usage pertinents\n");
        prompt.append("   - Utiliser les types d'association appropriés\n");
        prompt.append("   - Ajouter les relations <<include>> et <<extend>> si nécessaire\n");
        prompt.append("   - Maintenir la traçabilité vers les exigences\n\n");
        prompt.append("5️⃣ **Créer le Diagramme de Cas d'Usage** : OBLIGATOIRE, en dernier\n");
        prompt.append("   - `list_diagrams` (type_filter=UseCaseDiagram, SANS name_filter) : parcourir TOUS les résultats retournés.\n");
        prompt.append("     Si l'un d'eux a pour propriétaire (owner) le package UUID ").append(useCasesPackageUuid).append(", réutiliser SON UUID — QUEL QUE SOIT SON NOM — et NE PAS en créer un second.\n");
        prompt.append("   - Sinon SEULEMENT, `uml_createDiagram` : diagramme de cas d'usage nommé EXACTEMENT 'Cas d'Usage' (jamais un nom basé sur le contenu),\n");
        prompt.append("     avec le package 'Cas d'Usage' (UUID ci-dessus) comme propriétaire.\n");
        prompt.append("   - Puis `uml_unmaskInDiagram` pour CHAQUE acteur ET CHAQUE cas d'usage créés (UUID du diagramme + UUID de l'élément).\n");
        prompt.append("   - Les associations acteur-cas d'usage affichées apparaissent automatiquement.\n");
        prompt.append("   - Si un appel diagramme échoue, continuer sans bloquer et le signaler.\n\n");

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
        prompt.append("🔗 OBLIGATOIRE : lier CHAQUE cas d'usage à au moins une exigence qui définit sa fonctionnalité (champ `linked_requirements`)\n");
        prompt.append("🔗 Référencer les classes du modèle de domaine manipulées par les cas d'usage\n");
        prompt.append("🔗 Assurer une couverture complète des exigences fonctionnelles\n");
        prompt.append("⚠️ RAPPEL : choisir uniquement parmi les identifiants d'exigence fournis dans ce prompt.\n");
        prompt.append("   Ne jamais appeler `analyst_queryItems` ni rechercher une exigence par nom — si un cas d'usage ne correspond à aucune exigence évidente,\n");
        prompt.append("   choisir la plus proche plutôt que de laisser `linked_requirements` vide.\n\n");

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
