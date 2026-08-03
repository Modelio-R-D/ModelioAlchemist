package com.docaposte.modelioalchemist.langchain.impl;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Expose l'horodatage du build effectivement chargé, afin de vérifier lors d'un run
 * que les dernières modifications ont bien été packagées et déployées.
 *
 * <p>La valeur provient de l'entrée {@code Build-Timestamp} du MANIFEST (renseignée par
 * maven-jar-plugin). En développement (classes non packagées) ou si le manifeste est absent,
 * on retombe sur la date de dernière modification du jar ou du fichier .class.
 */
public final class BuildInfo {

    private static final String UNKNOWN = "unknown";
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private static final String BUILD_TIMESTAMP = resolveBuildTimestamp();
    private static final String SOURCE = resolveSourceLocation();

    private BuildInfo() {
    }

    /** Horodatage du build, ou "unknown" s'il n'a pas pu être déterminé. */
    public static String buildTimestamp() {
        return BUILD_TIMESTAMP;
    }

    /** Emplacement (jar ou répertoire de classes) d'où le code est chargé. */
    public static String sourceLocation() {
        return SOURCE;
    }

    /** Ligne prête à afficher, du type "2026-08-03 14:12:07 CEST (from .../modelioalchemist-0.1.0.jar)". */
    public static String describe() {
        return BUILD_TIMESTAMP + " (from " + SOURCE + ")";
    }

    private static String resolveBuildTimestamp() {
        String fromManifest = readManifestTimestamp();
        if (fromManifest != null) {
            return fromManifest;
        }
        String fromFile = readArtifactLastModified();
        return fromFile != null ? fromFile : UNKNOWN;
    }

    private static String readManifestTimestamp() {
        try {
            URL classUrl = BuildInfo.class.getResource("BuildInfo.class");
            if (classUrl == null) {
                return null;
            }
            String classPath = classUrl.toString();
            int separator = classPath.indexOf("!/");
            if (separator < 0) {
                // Classes non packagées : pas de manifeste à lire.
                return null;
            }
            URL manifestUrl = new URL(classPath.substring(0, separator + 2) + "META-INF/MANIFEST.MF");
            try (InputStream in = manifestUrl.openStream()) {
                Attributes attributes = new Manifest(in).getMainAttributes();
                String value = attributes.getValue("Build-Timestamp");
                return (value == null || value.isBlank() || value.startsWith("${")) ? null : value;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String readArtifactLastModified() {
        try {
            CodeSource codeSource = BuildInfo.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return null;
            }
            Path path = Paths.get(codeSource.getLocation().toURI());
            if (Files.isDirectory(path)) {
                // Répertoire de classes : la classe elle-même est le repère le plus fiable.
                path = path.resolve(BuildInfo.class.getName().replace('.', '/') + ".class");
            }
            if (!Files.exists(path)) {
                return null;
            }
            return DISPLAY_FORMAT.format(Files.getLastModifiedTime(path).toInstant());
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolveSourceLocation() {
        try {
            CodeSource codeSource = BuildInfo.class.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return UNKNOWN;
            }
            return Paths.get(codeSource.getLocation().toURI()).toString();
        } catch (Exception e) {
            return UNKNOWN;
        }
    }
}
