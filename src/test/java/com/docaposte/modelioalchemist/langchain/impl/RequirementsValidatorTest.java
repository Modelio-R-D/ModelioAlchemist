package com.docaposte.modelioalchemist.langchain.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de la validation d'exhaustivité des exigences (logique pure, sans MCP ni LLM).
 */
class RequirementsValidatorTest {

    private static final String CLASSIFIED_TWO = """
            {
              "fonctionnel": [ {"requirement_id": "EX-001"} ],
              "technique":   [ {"requirement_id": "EX-002"} ]
            }
            """;

    @Test
    void toutesLesExigencesClassifiees_estValide() {
        RequirementsValidator.ValidationResult result =
                RequirementsValidator.validateClassification("EX-001 et EX-002 sont exigées.", CLASSIFIED_TWO);

        assertEquals(2, result.extractedCount);
        assertEquals(2, result.classifiedCount);
        assertTrue(result.missingRequirements.isEmpty());
        assertTrue(result.extraRequirements.isEmpty());
        assertTrue(result.isValid);
    }

    @Test
    void exigenceNonClassifiee_estSignaleeManquante() {
        RequirementsValidator.ValidationResult result =
                RequirementsValidator.validateClassification("EX-001, EX-002 et EX-003.", CLASSIFIED_TWO);

        assertEquals(3, result.extractedCount);
        assertEquals(1, result.missingRequirements.size());
        assertTrue(result.missingRequirements.contains("EX-003"));
        assertFalse(result.isValid);
    }

    @Test
    void exigenceInventee_estSignaleeSupplementaire() {
        RequirementsValidator.ValidationResult result =
                RequirementsValidator.validateClassification("Seule EX-001 est exigée.", CLASSIFIED_TWO);

        assertEquals(1, result.extraRequirements.size());
        assertTrue(result.extraRequirements.contains("EX-002"));
    }

    /**
     * Régression : une extraction vide rendait {@code missingRequirements} vide par construction,
     * donc la validation réussissait alors qu'aucune comparaison n'avait réellement eu lieu.
     * C'est le cas observé en production (0 extraite, 8 classifiées, « ✅ SUCCÈS »).
     */
    @Test
    void extractionVideMaisClassificationNonVide_nEstPasValide() {
        RequirementsValidator.ValidationResult result =
                RequirementsValidator.validateClassification("Aucun identifiant ici.", CLASSIFIED_TWO);

        assertEquals(0, result.extractedCount);
        assertEquals(2, result.classifiedCount);
        assertFalse(result.isValid, "une comparaison impossible ne doit pas être rapportée comme un succès");
    }

    @Test
    void extractionVideEtClassificationVide_resteValide() {
        RequirementsValidator.ValidationResult result =
                RequirementsValidator.validateClassification("Aucun identifiant.", "{}");

        assertEquals(0, result.extractedCount);
        assertEquals(0, result.classifiedCount);
        assertTrue(result.isValid, "rien à comparer des deux côtés n'est pas une anomalie");
    }

    @Test
    void ancienFormatTexteBrut_estAussiReconnu() {
        String classifiedOldFormat = """
                { "fonctionnel": [ "L'exigence EX-001 doit être respectée." ] }
                """;
        RequirementsValidator.ValidationResult result =
                RequirementsValidator.validateClassification("EX-001", classifiedOldFormat);

        assertEquals(1, result.classifiedCount);
        assertTrue(result.isValid);
    }

    @Test
    void jsonInvalide_remonteUneErreur() {
        RequirementsValidator.ValidationResult result =
                RequirementsValidator.validateClassification("EX-001", "ceci n'est pas du JSON");

        assertFalse(result.isValid);
        assertTrue(result.errorMessage != null && !result.errorMessage.isBlank());
    }
}
