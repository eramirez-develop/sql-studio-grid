package com.quasera.sqlstudiogrid.sql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Análisis del texto alrededor del cursor para el autocompletado:
 * palabra que se está escribiendo, calificador antes del punto y alias de tablas.
 */
public final class SqlCompletion {

    private static final String IDENT = "(?:[\\w$#@]+|\\[[^\\]]+\\]|\"[^\"]+\"|`[^`]+`)";
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN|UPDATE|INTO)\\s+(" + IDENT + "(?:\\s*\\.\\s*" + IDENT + "){0,2})"
                    + "(?:\\s+(?:AS\\s+)?([\\w$#@]+))?");
    private static final Pattern FROM_LIST_ITEM = Pattern.compile(
            "(?i)," + "\\s*(" + IDENT + "(?:\\s*\\.\\s*" + IDENT + "){0,2})(?:\\s+(?:AS\\s+)?([\\w$#@]+))?");
    private static final Pattern FROM_SEGMENT = Pattern.compile(
            "(?is)\\bFROM\\b(.*?)(?=\\b(?:WHERE|GROUP|ORDER|HAVING|UNION|JOIN|INNER|LEFT|RIGHT|FULL|CROSS|ON)\\b|[;)]|$)");
    private static final Set<String> NOT_ALIAS = Set.of(
            "WHERE", "ON", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "OUTER", "GROUP", "ORDER",
            "HAVING", "UNION", "SET", "VALUES", "WITH", "LIMIT", "USING", "SELECT", "AND", "OR", "INTO");

    /** Tipo de sugerencia. */
    public enum Kind { KEYWORD, TABLE, VIEW, COLUMN, SCHEMA }

    public record Candidate(String text, Kind kind, String detail) {
    }

    private SqlCompletion() {
    }

    /** Parte de identificador escrita justo antes del cursor. */
    public static String prefixAt(String text, int caret) {
        int start = caret;
        while (start > 0 && SqlTokenizer.isWordPart(text.charAt(start - 1))) {
            start--;
        }
        return text.substring(start, caret);
    }

    /** Identificador antes del punto ("c" en "c.nom|"), o null si no hay punto. */
    public static String qualifierAt(String text, int caret) {
        int start = caret - prefixAt(text, caret).length();
        if (start <= 0 || text.charAt(start - 1) != '.') {
            return null;
        }
        int end = start - 1;
        int i = end;
        if (i > 0 && (text.charAt(i - 1) == ']' || text.charAt(i - 1) == '"' || text.charAt(i - 1) == '`')) {
            char close = text.charAt(i - 1);
            char open = close == ']' ? '[' : close;
            int openPos = text.lastIndexOf(open, i - 2);
            return openPos < 0 ? null : text.substring(openPos + 1, i - 1);
        }
        while (i > 0 && SqlTokenizer.isWordPart(text.charAt(i - 1))) {
            i--;
        }
        return i == end ? null : text.substring(i, end);
    }

    /**
     * Alias y nombres de tablas usados en el texto (en minúsculas) → referencia completa de la tabla.
     * Ej.: "FROM dbo.clientes c" da {c → dbo.clientes, clientes → dbo.clientes}.
     */
    public static Map<String, String> aliases(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        collect(TABLE_REF.matcher(text), result);
        // tablas separadas por coma: solo dentro de cada cláusula FROM
        Matcher from = FROM_SEGMENT.matcher(text);
        while (from.find()) {
            collect(FROM_LIST_ITEM.matcher(from.group(1)), result);
        }
        return result;
    }

    private static void collect(Matcher m, Map<String, String> result) {
        while (m.find()) {
            String table = m.group(1).replaceAll("\\s+", "");
            String alias = m.group(2);
            List<String> parts = splitQualified(table);
            if (parts.isEmpty() || SqlKeywords.isKeyword(parts.getLast()) && parts.size() == 1) {
                continue;
            }
            String clean = String.join(".", parts);
            result.putIfAbsent(parts.getLast().toLowerCase(Locale.ROOT), clean);
            if (alias != null && !NOT_ALIAS.contains(alias.toUpperCase(Locale.ROOT))) {
                result.put(alias.toLowerCase(Locale.ROOT), clean);
            }
        }
    }

    /** Divide "db.[esquema].tabla" en partes sin comillas. */
    public static List<String> splitQualified(String name) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '[') {
                quote = ']';
            } else if (c == '"' || c == '`') {
                quote = c;
            } else if (c == '.') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else if (!Character.isWhitespace(c)) {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        parts.removeIf(String::isEmpty);
        return parts;
    }

    /** Filtra por prefijo (sin distinguir mayúsculas) y ordena: primero coincidencias exactas de inicio. */
    public static List<Candidate> filter(List<Candidate> all, String prefix, int limit) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : all) {
            if (c.text().toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(c);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }
}
