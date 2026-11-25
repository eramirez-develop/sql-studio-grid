package com.quasera.sqlstudiogrid.sql;

import java.util.ArrayList;
import java.util.List;

/** Tokenizador SQL sencillo, compartido por el formateador. */
public final class SqlTokenizer {

    public enum Type { WHITESPACE, LINE_COMMENT, BLOCK_COMMENT, STRING, QUOTED_IDENTIFIER, WORD, NUMBER, SYMBOL }

    public record Token(Type type, String text) {
        public boolean isWord(String upper) {
            return type == Type.WORD && text.equalsIgnoreCase(upper);
        }

        public boolean isSymbol(String s) {
            return type == Type.SYMBOL && text.equals(s);
        }
    }

    private static final String[] MULTI_SYMBOLS = {"<=", ">=", "<>", "!=", "||", "::", ":="};

    private SqlTokenizer() {
    }

    public static List<Token> tokenize(String sql) {
        List<Token> out = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            int start = i;
            if (Character.isWhitespace(c)) {
                while (i < n && Character.isWhitespace(sql.charAt(i))) {
                    i++;
                }
                out.add(new Token(Type.WHITESPACE, sql.substring(start, i)));
            } else if (c == '-' && next == '-') {
                int eol = sql.indexOf('\n', i);
                i = eol < 0 ? n : eol;
                out.add(new Token(Type.LINE_COMMENT, sql.substring(start, i).stripTrailing()));
            } else if (c == '/' && next == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.add(new Token(Type.BLOCK_COMMENT, sql.substring(start, i)));
            } else if (c == '\'' || ((c == 'N' || c == 'n') && next == '\'')) {
                i = closeQuoted(sql, c == '\'' ? i : i + 1, '\'');
                out.add(new Token(Type.STRING, sql.substring(start, i)));
            } else if (c == '"' || c == '`' || c == '[') {
                i = closeQuoted(sql, i, c == '[' ? ']' : c);
                out.add(new Token(Type.QUOTED_IDENTIFIER, sql.substring(start, i)));
            } else if (Character.isDigit(c) || (c == '.' && Character.isDigit(next))) {
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    i++;
                }
                out.add(new Token(Type.NUMBER, sql.substring(start, i)));
            } else if (Character.isLetter(c) || c == '_' || c == '@' || c == '#' || c == '$') {
                while (i < n && isWordPart(sql.charAt(i))) {
                    i++;
                }
                out.add(new Token(Type.WORD, sql.substring(start, i)));
            } else {
                String symbol = String.valueOf(c);
                for (String m : MULTI_SYMBOLS) {
                    if (sql.startsWith(m, i)) {
                        symbol = m;
                        break;
                    }
                }
                i += symbol.length();
                out.add(new Token(Type.SYMBOL, symbol));
            }
        }
        return out;
    }

    /** @param open posición de la comilla de apertura; devuelve la posición tras la de cierre */
    private static int closeQuoted(String sql, int open, char close) {
        int n = sql.length();
        int j = open + 1;
        while (j < n) {
            if (sql.charAt(j) == close) {
                if (j + 1 < n && sql.charAt(j + 1) == close) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return n;
    }

    public static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '@' || c == '#' || c == '$';
    }
}
