package com.quasera.sqlstudiogrid.connectivity;

import com.quasera.sqlstudiogrid.core.ConnectionProfile;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/**
 * Ejecuta scripts sobre una conexión propia (una sesión por consola, como DataGrip).
 * Los métodos que hablan con la base deben llamarse fuera del hilo de la interfaz.
 */
public final class QueryExecutor implements AutoCloseable {

    private static final int MAX_CLOB_CHARS = 10_000;
    private static final int MAX_BINARY_BYTES = 64;
    private static final int MAX_RESULTS_PER_STATEMENT = 1_000;

    private final ConnectionProfile profile;
    private Connection connection;
    private boolean autoCommit = true;
    private volatile Statement current;
    private volatile boolean cancelled;

    public QueryExecutor(ConnectionProfile profile) {
        this.profile = profile;
    }

    public ConnectionProfile profile() {
        return profile;
    }

    public synchronized Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = ConnectionFactory.open(profile);
            connection.setAutoCommit(autoCommit);
        }
        return connection;
    }

    public synchronized boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Ejecuta el script y entrega cada resultado al consumidor a medida que llega.
     * Se detiene en el primer error.
     */
    public void execute(String script, int maxRows, Consumer<QueryResult> sink) throws SQLException {
        cancelled = false;
        Connection c = connection();
        List<String> statements = SqlScriptSplitter.split(script, profile.type().scriptOptions());
        for (String sql : statements) {
            if (cancelled) {
                sink.accept(QueryResult.message(sql, "Ejecución cancelada por el usuario."));
                return;
            }
            if (!runOne(c, sql, maxRows, sink)) {
                return;
            }
        }
    }

    private boolean runOne(Connection c, String sql, int maxRows, Consumer<QueryResult> sink) {
        long start = System.nanoTime();
        try (Statement st = c.createStatement()) {
            current = st;
            if (maxRows > 0) {
                st.setMaxRows(maxRows + 1); // una fila extra para saber si se truncó
            }
            st.setFetchSize(200);
            boolean isResultSet = st.execute(sql);
            for (int guard = 0; guard < MAX_RESULTS_PER_STATEMENT; guard++) {
                if (isResultSet) {
                    try (ResultSet rs = st.getResultSet()) {
                        sink.accept(read(sql, rs, maxRows, start));
                    }
                } else {
                    int count = st.getUpdateCount();
                    if (count == -1) {
                        break;
                    }
                    sink.accept(QueryResult.update(sql, count, elapsed(start)));
                }
                isResultSet = st.getMoreResults();
            }
            String warnings = warnings(st.getWarnings());
            if (!warnings.isEmpty()) {
                sink.accept(QueryResult.message(sql, warnings));
            }
            return true;
        } catch (SQLException e) {
            sink.accept(QueryResult.error(sql, describe(e), elapsed(start)));
            return false;
        } finally {
            current = null;
        }
    }

    private static QueryResult read(String sql, ResultSet rs, int maxRows, long start) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int columnCount = md.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        List<String> types = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String label = md.getColumnLabel(i);
            columns.add(label == null || label.isBlank() ? "(sin nombre " + i + ")" : label);
            types.add(md.getColumnTypeName(i));
        }
        List<Object[]> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (maxRows > 0 && rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            Object[] row = new Object[columnCount];
            for (int i = 1; i <= columnCount; i++) {
                row[i - 1] = readValue(rs, i);
            }
            rows.add(row);
        }
        return QueryResult.resultSet(sql, columns, types, rows, elapsed(start), truncated);
    }

    private static Object readValue(ResultSet rs, int index) {
        try {
            Object value = rs.getObject(index);
            if (value instanceof Clob clob) {
                long length = clob.length();
                return clob.getSubString(1, (int) Math.min(length, MAX_CLOB_CHARS));
            }
            if (value instanceof Blob blob) {
                long length = blob.length();
                return hex(blob.getBytes(1, (int) Math.min(length, MAX_BINARY_BYTES)), length);
            }
            if (value instanceof byte[] bytes) {
                return hex(bytes, bytes.length);
            }
            if (value instanceof SQLXML xml) {
                return xml.getString();
            }
            return value;
        } catch (SQLException e) {
            try {
                return rs.getString(index);
            } catch (SQLException ignored) {
                return "<error: " + e.getMessage() + ">";
            }
        }
    }

    private static String hex(byte[] bytes, long totalLength) {
        int shown = Math.min(bytes.length, MAX_BINARY_BYTES);
        byte[] part = shown == bytes.length ? bytes : java.util.Arrays.copyOf(bytes, shown);
        String text = "0x" + HexFormat.of().withUpperCase().formatHex(part);
        return totalLength > shown ? text + "... (" + totalLength + " bytes)" : text;
    }

    /** Cancela la sentencia en curso (si el driver lo soporta). */
    public void cancel() {
        cancelled = true;
        Statement st = current;
        if (st != null) {
            try {
                st.cancel();
            } catch (SQLException ignored) {
                // algunos drivers no soportan cancelación
            }
        }
    }

    public synchronized void setAutoCommit(boolean value) throws SQLException {
        this.autoCommit = value;
        if (isConnected()) {
            connection.setAutoCommit(value);
        }
    }

    public synchronized void commit() throws SQLException {
        if (isConnected() && !connection.getAutoCommit()) {
            connection.commit();
        }
    }

    public synchronized void rollback() throws SQLException {
        if (isConnected() && !connection.getAutoCommit()) {
            connection.rollback();
        }
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // se está cerrando de todas formas
            }
            connection = null;
        }
    }

    public static String describe(SQLException e) {
        StringBuilder sb = new StringBuilder();
        SQLException ex = e;
        while (ex != null) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(ex.getMessage());
            if (ex.getSQLState() != null || ex.getErrorCode() != 0) {
                sb.append(" [SQLState=").append(ex.getSQLState())
                        .append(", código=").append(ex.getErrorCode()).append(']');
            }
            ex = ex.getNextException();
        }
        return sb.toString();
    }

    private static String warnings(SQLWarning warning) {
        StringBuilder sb = new StringBuilder();
        while (warning != null) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(warning.getMessage());
            warning = warning.getNextWarning();
        }
        return sb.toString();
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
