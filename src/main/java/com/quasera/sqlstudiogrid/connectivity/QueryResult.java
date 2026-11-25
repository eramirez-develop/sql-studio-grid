package com.quasera.sqlstudiogrid.connectivity;

import java.util.List;

/** Resultado de una sentencia: filas, filas afectadas, mensaje o error. */
public record QueryResult(
        Kind kind,
        String sql,
        List<String> columns,
        List<String> columnTypes,
        List<Object[]> rows,
        int updateCount,
        long elapsedMs,
        boolean truncated,
        String message) {

    public enum Kind { RESULT_SET, UPDATE, MESSAGE, ERROR }

    public static QueryResult resultSet(String sql, List<String> columns, List<String> types,
                                        List<Object[]> rows, long elapsedMs, boolean truncated) {
        return new QueryResult(Kind.RESULT_SET, sql, columns, types, rows, -1, elapsedMs, truncated, "");
    }

    public static QueryResult update(String sql, int count, long elapsedMs) {
        return new QueryResult(Kind.UPDATE, sql, List.of(), List.of(), List.of(), count, elapsedMs, false, "");
    }

    public static QueryResult message(String sql, String message) {
        return new QueryResult(Kind.MESSAGE, sql, List.of(), List.of(), List.of(), -1, 0, false, message);
    }

    public static QueryResult error(String sql, String message, long elapsedMs) {
        return new QueryResult(Kind.ERROR, sql, List.of(), List.of(), List.of(), -1, elapsedMs, false, message);
    }

    /** Texto corto para la pestaña de mensajes. */
    public String summary() {
        return switch (kind) {
            case RESULT_SET -> rows.size() + " fila(s)" + (truncated ? " (limitado)" : "") + " en " + elapsedMs + " ms";
            case UPDATE -> updateCount + " fila(s) afectada(s) en " + elapsedMs + " ms";
            case MESSAGE -> message;
            case ERROR -> "ERROR: " + message;
        };
    }
}
