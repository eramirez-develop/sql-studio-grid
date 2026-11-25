package com.quasera.sqlstudiogrid.connectivity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Divide un script SQL en sentencias o lotes, respetando cadenas, identificadores
 * entre comillas y comentarios.
 *
 * <ul>
 *   <li>SQL Server / Sybase: separa solo por líneas {@code GO} (igual que SSMS); cada lote
 *       se envía completo y el driver devuelve todos sus resultados.</li>
 *   <li>Oracle: separa por {@code ;} y por líneas {@code /}. Los bloques PL/SQL
 *       (CREATE PROCEDURE, BEGIN, DECLARE...) no se cortan en {@code ;}.</li>
 *   <li>MySQL: separa por {@code ;}.</li>
 * </ul>
 */
public final class SqlScriptSplitter {

    /**
     * @param semicolon          {@code ;} separa sentencias
     * @param goSeparator        una línea {@code GO} separa lotes
     * @param slashSeparator     una línea {@code /} separa sentencias (Oracle)
     * @param bracketIdentifiers {@code [identificador]} se trata como texto entre comillas
     */
    public record Options(boolean semicolon, boolean goSeparator, boolean slashSeparator,
                          boolean bracketIdentifiers) {
    }

    private static final Pattern GO_LINE = Pattern.compile("(?i)\\s*go(\\s+\\d+)?\\s*;?\\s*");
    private static final Pattern SLASH_LINE = Pattern.compile("\\s*/\\s*");
    private static final Pattern PLSQL_START = Pattern.compile(
            "(?i)(create\\s+(or\\s+replace\\s+)?((non)?editionable\\s+)?"
                    + "(procedure|function|package|trigger|type)\\b|declare\\b|begin\\b)");

    private SqlScriptSplitter() {
    }

    public static List<String> split(String script, Options options) {
        List<String> out = new ArrayList<>();
        if (script == null || script.isEmpty()) {
            return out;
        }
        StringBuilder current = new StringBuilder(); // texto tal cual se enviará
        StringBuilder code = new StringBuilder();    // mismo texto sin comentarios
        int n = script.length();
        int i = 0;
        boolean lineStart = true;

        while (i < n) {
            if (lineStart && (options.goSeparator() || options.slashSeparator())) {
                int eol = script.indexOf('\n', i);
                if (eol < 0) {
                    eol = n;
                }
                String line = stripCr(script.substring(i, eol));
                if ((options.goSeparator() && GO_LINE.matcher(line).matches())
                        || (options.slashSeparator() && SLASH_LINE.matcher(line).matches())) {
                    flush(out, current, code);
                    i = eol + 1;
                    continue;
                }
            }
            lineStart = false;

            char c = script.charAt(i);
            char next = i + 1 < n ? script.charAt(i + 1) : '\0';

            // Comentario de línea
            if (c == '-' && next == '-') {
                int eol = script.indexOf('\n', i);
                if (eol < 0) {
                    eol = n;
                }
                current.append(script, i, eol);
                code.append(' ');
                i = eol;
                continue;
            }
            // Comentario de bloque
            if (c == '/' && next == '*') {
                int end = script.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                current.append(script, i, end);
                code.append(' ');
                i = end;
                continue;
            }
            // Cadenas e identificadores entre comillas
            if (c == '\'' || c == '"' || c == '`' || (c == '[' && options.bracketIdentifiers())) {
                char close = c == '[' ? ']' : c;
                int j = i + 1;
                while (j < n) {
                    char d = script.charAt(j);
                    if (d == close) {
                        if (j + 1 < n && script.charAt(j + 1) == close) {
                            j += 2; // comilla escapada duplicándola
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                int end = Math.min(j + 1, n);
                current.append(script, i, end);
                code.append(script, i, end);
                i = end;
                continue;
            }
            // Separador ;
            if (c == ';' && options.semicolon() && !insidePlsql(code, options)) {
                flush(out, current, code);
                i++;
                continue;
            }

            current.append(c);
            code.append(c);
            if (c == '\n') {
                lineStart = true;
            }
            i++;
        }
        flush(out, current, code);
        return out;
    }

    private static boolean insidePlsql(StringBuilder code, Options options) {
        if (!options.slashSeparator()) {
            return false;
        }
        String trimmed = code.toString().stripLeading();
        return PLSQL_START.matcher(trimmed).lookingAt();
    }

    private static void flush(List<String> out, StringBuilder current, StringBuilder code) {
        if (!code.toString().isBlank()) {
            out.add(current.toString().strip());
        }
        current.setLength(0);
        code.setLength(0);
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
