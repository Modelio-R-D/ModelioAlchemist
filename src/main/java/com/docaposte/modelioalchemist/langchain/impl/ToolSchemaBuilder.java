package com.docaposte.modelioalchemist.langchain.impl;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/** Builds minimal JSON schema strings for Azure function tool definitions. */
class ToolSchemaBuilder {

    static String build(ToolSpecification spec) {
        try {
            if (spec == null || spec.parameters() == null) return null;
            JsonSchemaElement root = spec.parameters();
            if (!(root instanceof JsonObjectSchema)) return null;
            String schema = buildObject((JsonObjectSchema) root);
            return schema;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String buildObject(JsonObjectSchema obj) {
        StringBuilder sb = new StringBuilder();
        sb.append('{').append("\"type\":\"object\"");
        if (obj.description()!=null && !obj.description().isEmpty()) sb.append(",\"description\":").append(esc(obj.description()));
        sb.append(",\"properties\":{");
        boolean first=true;
        if (obj.properties()!=null && !obj.properties().isEmpty()) {
            for (java.util.Map.Entry<String, JsonSchemaElement> e : obj.properties().entrySet()) {
                if (!first) sb.append(','); first=false;
                sb.append(esc(e.getKey())).append(':').append(buildAny(e.getValue()));
            }
        }
        sb.append('}');
        if (obj.required()!=null && !obj.required().isEmpty()) {
            sb.append(",\"required\":[");
            for (int i=0;i<obj.required().size();i++) { if (i>0) sb.append(','); sb.append(esc(obj.required().get(i))); }
            sb.append(']');
        }
        sb.append(",\"additionalProperties\":").append(obj.additionalProperties()==null?"false":String.valueOf(obj.additionalProperties().booleanValue()));
        sb.append('}');
        return sb.toString();
    }

    private static String buildAny(JsonSchemaElement el) {
        if (el == null) return "{}";
        if (el instanceof JsonObjectSchema) return buildObject((JsonObjectSchema) el);
        String type = rtType(el); String desc = rtDesc(el);
        StringBuilder sb = new StringBuilder("{");
        if (type != null) sb.append("\"type\":\"").append(type).append('\"');
        if (desc != null && !desc.isEmpty()) { if (type != null) sb.append(','); sb.append("\"description\":").append(esc(desc)); }
        if ("array".equals(type)) {
            JsonSchemaElement items = rtItems(el);
            if (items != null) sb.append(",\"items\":").append(buildAny(items));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String rtType(JsonSchemaElement el) {
        try { Object v = el.getClass().getMethod("type").invoke(el); if (v instanceof String) return (String) v; } catch (Throwable ignore) {}
        String cn = el.getClass().getSimpleName().toLowerCase();
        if (cn.contains("string")) return "string";
        if (cn.contains("int") || cn.contains("long") || cn.contains("double") || cn.contains("float") || cn.contains("number")) return "number";
        if (cn.contains("bool")) return "boolean";
        if (cn.contains("array")) return "array";
        return null;
    }
    private static String rtDesc(JsonSchemaElement el) { try { Object v = el.getClass().getMethod("description").invoke(el); if (v instanceof String) return (String) v; } catch (Throwable ignore) {} return null; }
    private static JsonSchemaElement rtItems(JsonSchemaElement el) { try { Object v = el.getClass().getMethod("items").invoke(el); if (v instanceof JsonSchemaElement) return (JsonSchemaElement) v; } catch (Throwable ignore) {} return null; }

    private static String esc(String s) { if (s==null) return "null"; StringBuilder sb=new StringBuilder("\""); for(int i=0;i<s.length();i++){ char c=s.charAt(i); switch(c){ case '"': sb.append("\\\""); break; case '\\': sb.append("\\\\"); break; case '\n': sb.append("\\n"); break; case '\r': sb.append("\\r"); break; case '\t': sb.append("\\t"); break; default: if(c<32) sb.append(String.format("\\u%04x",(int)c)); else sb.append(c);} } sb.append('"'); return sb.toString(); }
}