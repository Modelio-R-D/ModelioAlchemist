package com.docaposte.modelioalchemist.langchain.impl;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;

public class PdfExtractor {

    /**
     * Extrait le texte du PDF en insérant des marqueurs de page (ex: "[PAGE 3]") avant le
     * contenu de chaque page. Ces marqueurs permettent aux étapes ultérieures du pipeline
     * (agents LLM) de tracer précisément la page d'origine de chaque exigence extraite,
     * ce qui alimente le champ "origin" des exigences créées dans Modelio.
     */
    public static String extractText(String pdfPath) throws IOException {
        try (PDDocument document = PDDocument.load(new File(pdfPath))) {
            int pageCount = document.getNumberOfPages();
            StringBuilder result = new StringBuilder();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                result.append("\n[PAGE ").append(page).append("]\n").append(pageText);
            }
            return result.toString();
        }
    }
}
