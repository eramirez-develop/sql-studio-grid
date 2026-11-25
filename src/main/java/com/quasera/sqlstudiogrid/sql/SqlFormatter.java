package com.quasera.sqlstudiogrid.sql;

import com.quasera.sqlstudiogrid.sql.SqlTokenizer.Token;
import com.quasera.sqlstudiogrid.sql.SqlTokenizer.Type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Formateador SQL sencillo: palabras clave en mayúsculas, una cláusula por línea,
 * columnas del SELECT una por línea, AND/OR indentados y subconsultas sangradas.
 * No cambia cadenas, identificadores entre comillas ni comentarios.
 */
public final class SqlFormatter {

    private static final int INDENT = 4;

    private static final Set<String> CLAUSES = Set.of(
            "SELECT", "FROM", "WHERE", "GROUP BY", "ORDER BY", "HAVING", "UNION", "UNION ALL",
            "EXCEPT", "INTERSECT", "VALUES", "SET", "LIMIT", "OFFSET", "INSERT INTO", "UPDATE", "DELETE FROM");
    private static final Set<String> JOIN_WORDS = Set.of("JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS");
    private static final Set<String> JOIN_MODIFIERS = Set.of("INNER", "LEFT", "RIGHT", "FULL", "CROSS", "OUTER");
    private static final Set<String> FUNCTION_LIKE = Set.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "ISNULL", "NVL", "CAST", "CONVERT", "GETDATE",
            "SYSDATE", "NOW", "REPLACE", "CHAR", "NCHAR", "VARCHAR", "NVARCHAR", "VARCHAR2", "DECIMAL",
            "NUMERIC", "NUMBER", "FLOAT", "LEFT", "RIGHT");

    private SqlFormatter() {
    }

    public static String format(String sql) {
        List<Token> tokens = new ArrayList<>();
        for (Token t : SqlTokenizer.tokenize(sql)) {
            if (t.type() != Type.WHITESPACE) {
                tokens.add(t);
            }
        }

        Out out = new Out();
        Deque<Integer> subqueries = new ArrayDeque<>();   // profundidad de paréntesis de cada subconsulta
        Deque<String> savedClauses = new ArrayDeque<>();
        int depth = 0;
        String clause = "";
        boolean between = false;
        boolean selectListPending = false;

        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            Token prev = i > 0 ? tokens.get(i - 1) : null;
            Token next = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
            int queryDepth = subqueries.isEmpty() ? 0 : subqueries.peek();
            int base = INDENT * subqueries.size();

            switch (t.type()) {
                case LINE_COMMENT -> {
                    out.space();
                    out.write(t.text());
                    out.newline(base);
                }
                case BLOCK_COMMENT -> {
                    out.space();
                    out.write(t.text());
                }
                case WORD -> {
                    String up = t.text().toUpperCase(Locale.ROOT);
                    String word = SqlKeywords.isKeyword(t.text()) ? up : t.text();
                    if (next != null && ((up.equals("GROUP") || up.equals("ORDER")) && next.isWord("BY")
                            || up.equals("UNION") && next.isWord("ALL")
                            || up.equals("INSERT") && next.isWord("INTO")
                            || up.equals("DELETE") && next.isWord("FROM"))) {
                        word = up + " " + next.text().toUpperCase(Locale.ROOT);
                        up = word;
                        i++;
                    }
                    if (up.equals("GO")) {
                        out.newline(0);
                        out.write("GO");
                        out.blankLine();
                        clause = "";
                        continue;
                    }
                    if (CLAUSES.contains(up) && depth == queryDepth) {
                        out.newline(base);
                        out.write(word);
                        clause = up;
                        between = false;
                        selectListPending = up.equals("SELECT");
                        continue;
                    }
                    if (selectListPending) {
                        boolean topCount = prev != null && prev.isWord("TOP");
                        if (up.equals("DISTINCT") || up.equals("TOP") || topCount) {
                            out.space();
                            out.write(word);
                            continue;
                        }
                        out.newline(base + INDENT);
                        selectListPending = false;
                    }
                    if (JOIN_WORDS.contains(up) && depth == queryDepth && clause.length() > 0
                            && !(prev != null && prev.type() == Type.WORD
                            && JOIN_MODIFIERS.contains(prev.text().toUpperCase(Locale.ROOT)))
                            && !(next != null && next.isSymbol("("))) {
                        out.newline(base);
                        out.write(word);
                        clause = "JOIN";
                        continue;
                    }
                    if (up.equals("ON") && clause.equals("JOIN")) {
                        clause = "ON";
                    }
                    if ((up.equals("AND") || up.equals("OR")) && depth == queryDepth
                            && (clause.equals("WHERE") || clause.equals("HAVING") || clause.equals("ON"))) {
                        if (up.equals("AND") && between) {
                            between = false;
                        } else {
                            out.newline(base + INDENT);
                            out.write(word);
                            continue;
                        }
                    }
                    if (up.equals("BETWEEN")) {
                        between = true;
                    }
                    out.space();
                    out.write(word);
                }
                case SYMBOL -> {
                    String s = t.text();
                    if (selectListPending && !s.equals("(") && !s.equals(")")) {
                        out.newline(base + INDENT);
                        selectListPending = false;
                    }
                    switch (s) {
                        case "(" -> {
                            boolean glued = prev != null && (prev.type() == Type.WORD
                                    && (!SqlKeywords.isKeyword(prev.text())
                                    || FUNCTION_LIKE.contains(prev.text().toUpperCase(Locale.ROOT)))
                                    || prev.type() == Type.QUOTED_IDENTIFIER);
                            if (!glued) {
                                out.space();
                            }
                            out.write("(");
                            out.suppressSpace();
                            depth++;
                            if (next != null && next.isWord("SELECT")) {
                                subqueries.push(depth);
                                savedClauses.push(clause);
                            }
                        }
                        case ")" -> {
                            if (!subqueries.isEmpty() && subqueries.peek() == depth) {
                                subqueries.pop();
                                clause = savedClauses.pop();
                                selectListPending = false;
                                out.newline(INDENT * subqueries.size());
                            }
                            out.write(")");
                            depth = Math.max(0, depth - 1);
                        }
                        case "," -> {
                            out.write(",");
                            if (depth == queryDepth && (clause.equals("SELECT") || clause.equals("SET"))) {
                                out.newline(base + INDENT);
                            }
                        }
                        case ";" -> {
                            out.write(";");
                            out.blankLine();
                            clause = "";
                            depth = 0;
                            subqueries.clear();
                            savedClauses.clear();
                            selectListPending = false;
                        }
                        case "." -> {
                            out.write(".");
                            out.suppressSpace();
                        }
                        case "-", "+" -> {
                            boolean unary = prev == null || (prev.type() == Type.SYMBOL && !prev.isSymbol(")"))
                                    || (prev.type() == Type.WORD && SqlKeywords.isKeyword(prev.text()));
                            out.space();
                            out.write(s);
                            if (unary) {
                                out.suppressSpace();
                            }
                        }
                        default -> {
                            out.space();
                            out.write(s);
                        }
                    }
                }
                default -> {
                    // STRING, NUMBER, QUOTED_IDENTIFIER
                    if (selectListPending) {
                        if (t.type() == Type.NUMBER && prev != null && prev.isWord("TOP")) {
                            out.space();
                            out.write(t.text());
                            continue;
                        }
                        out.newline(base + INDENT);
                        selectListPending = false;
                    }
                    out.space();
                    out.write(t.text());
                }
            }
        }
        return out.result();
    }

    /** Acumulador de salida que maneja espacios y saltos de línea. */
    private static final class Out {
        private final StringBuilder sb = new StringBuilder();
        private boolean lineStart = true;
        private boolean noSpace = true;

        void newline(int indent) {
            trimTrailingSpaces();
            if (!lineStart && !sb.isEmpty()) {
                sb.append('\n');
            } else {
                // ya estamos al inicio de una línea: se reemplaza la sangría
                int lastNl = sb.lastIndexOf("\n");
                sb.setLength(lastNl + 1);
            }
            sb.append(" ".repeat(indent));
            lineStart = true;
            noSpace = true;
        }

        void blankLine() {
            trimTrailingSpaces();
            if (!sb.isEmpty()) {
                while (!sb.toString().endsWith("\n\n")) {
                    sb.append('\n');
                }
            }
            lineStart = true;
            noSpace = true;
        }

        void space() {
            if (!lineStart && !noSpace) {
                sb.append(' ');
            }
        }

        void suppressSpace() {
            noSpace = true;
        }

        void write(String text) {
            sb.append(text);
            lineStart = false;
            noSpace = false;
        }

        private void trimTrailingSpaces() {
            int len = sb.length();
            while (len > 0 && sb.charAt(len - 1) == ' ') {
                len--;
            }
            sb.setLength(len);
        }

        String result() {
            return sb.toString().strip() + "\n";
        }
    }
}
