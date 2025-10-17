package com.docaposte.modelioalchemist.langchain.impl;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonUtils {

    private static final Pattern JSON_PATTERN = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    public static String extractFirstJson(String text) {
        if (text == null) return null;
        Matcher m = JSON_PATTERN.matcher(text);
        if (m.find()) {
            return m.group();
        }
        return null;
    }
}
