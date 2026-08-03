package com.docaposte.modelioalchemist.langchain.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests du découpage PlantUML utilisé par la phase domaine en mode chunké.
 */
class PlantUmlParserTest {

    @Test
    void decoupageEnLotsDeTailleExacte() {
        List<List<String>> chunks = PlantUmlParser.splitIntoChunks(List.of("a", "b", "c", "d"), 2);

        assertEquals(2, chunks.size());
        assertEquals(List.of("a", "b"), chunks.get(0));
        assertEquals(List.of("c", "d"), chunks.get(1));
    }

    @Test
    void dernierLotIncomplet() {
        List<List<String>> chunks = PlantUmlParser.splitIntoChunks(List.of("a", "b", "c"), 2);

        assertEquals(2, chunks.size());
        assertEquals(List.of("c"), chunks.get(1));
    }

    @Test
    void listeVideDonneAucunLot() {
        assertTrue(PlantUmlParser.splitIntoChunks(List.of(), 5).isEmpty());
    }

    /**
     * Invariant dont dépend la vérification post-lot : {@code classNames} et {@code classBlocks}
     * sont alignés index par index, donc les découper avec la même taille de lot fait correspondre
     * le lot i de noms au lot i de blocs.
     */
    @Test
    void nomsEtBlocsDeClassesSontAlignesParIndex() {
        String plantUml = """
                @startuml
                package "Business" {
                  class Alpha {
                    +id: int
                  }
                  class Beta {
                    +nom: String
                  }
                }
                package "Technical" {
                  class Gamma {
                    +traiter()
                  }
                }
                Alpha "1" --> "0..*" Beta : "contient"
                @enduml
                """;

        PlantUmlParser.DomainPlantUmlParts parts = PlantUmlParser.extractDomainPlantUmlParts(plantUml);

        assertEquals(List.of("Alpha", "Beta", "Gamma"), parts.classNames);
        assertEquals(parts.classNames.size(), parts.classBlocks.size());
        for (int i = 0; i < parts.classNames.size(); i++) {
            assertTrue(parts.classBlocks.get(i).contains(parts.classNames.get(i)),
                    "le bloc " + i + " doit décrire la classe " + parts.classNames.get(i));
        }

        List<List<String>> nameChunks = PlantUmlParser.splitIntoChunks(parts.classNames, 2);
        List<List<String>> blockChunks = PlantUmlParser.splitIntoChunks(parts.classBlocks, 2);
        assertEquals(nameChunks.size(), blockChunks.size());
        assertEquals(List.of("Alpha", "Beta"), nameChunks.get(0));
    }

    @Test
    void classesDupliqueesSontDedupliquees() {
        String plantUml = """
                @startuml
                class Alpha {
                  +id: int
                }
                class Alpha {
                  +id: int
                }
                @enduml
                """;

        PlantUmlParser.DomainPlantUmlParts parts = PlantUmlParser.extractDomainPlantUmlParts(plantUml);

        assertEquals(List.of("Alpha"), parts.classNames);
        assertEquals(1, parts.classBlocks.size());
    }
}
