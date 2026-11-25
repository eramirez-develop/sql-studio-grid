package com.quasera.sqlstudiogrid.sql;

import java.util.Locale;
import java.util.Set;

/** Palabras clave SQL comunes (ANSI, T-SQL, PL/SQL, MySQL) y tipos de datos. */
public final class SqlKeywords {

    public static final Set<String> KEYWORDS = Set.of(
            "ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "BEGIN", "BETWEEN", "BREAK", "BY", "CASE",
            "CAST", "CATCH", "CHECK", "CLOSE", "COLUMN", "COMMIT", "CONSTRAINT", "CONTINUE", "CONVERT",
            "CREATE", "CROSS", "CURSOR", "DATABASE", "DEALLOCATE", "DECLARE", "DEFAULT", "DELETE", "DESC",
            "DISTINCT", "DROP", "ELSE", "ELSIF", "END", "EXCEPT", "EXCEPTION", "EXEC", "EXECUTE", "EXISTS",
            "FETCH", "FIRST", "FOR", "FOREIGN", "FROM", "FULL", "FUNCTION", "GO", "GRANT", "GROUP",
            "HAVING", "IDENTITY", "IF", "IN", "INDEX", "INNER", "INSERT", "INTERSECT", "INTO", "IS",
            "JOIN", "KEY", "LEFT", "LIKE", "LIMIT", "LOOP", "MERGE", "NEXT", "NOCOUNT", "NOT", "NULL",
            "OF", "OFFSET", "ON", "ONLY", "OPEN", "OR", "ORDER", "OUTER", "OUTPUT", "OVER", "PACKAGE",
            "PARTITION", "PRIMARY", "PRINT", "PROC", "PROCEDURE", "RAISERROR", "REFERENCES", "REPLACE",
            "RETURN", "RETURNS", "REVOKE", "RIGHT", "ROLLBACK", "ROWNUM", "ROWS", "SELECT", "SET", "TABLE",
            "THEN", "THROW", "TOP", "TRAN", "TRANSACTION", "TRIGGER", "TRUNCATE", "TRY", "UNION",
            "UNIQUE", "UPDATE", "USE", "USING", "VALUES", "VIEW", "WHEN", "WHERE", "WHILE", "WITH",
            "COUNT", "SUM", "AVG", "MIN", "MAX", "COALESCE", "ISNULL", "NVL", "GETDATE", "SYSDATE", "NOW",
            "BIGINT", "BIT", "CHAR", "DATE", "DATETIME", "DECIMAL", "FLOAT", "INT", "INTEGER", "MONEY",
            "NCHAR", "NUMBER", "NUMERIC", "NVARCHAR", "SMALLINT", "TEXT", "TIMESTAMP", "TINYINT",
            "VARCHAR", "VARCHAR2", "CLOB", "BLOB");

    private SqlKeywords() {
    }

    public static boolean isKeyword(String word) {
        return word != null && KEYWORDS.contains(word.toUpperCase(Locale.ROOT));
    }
}
