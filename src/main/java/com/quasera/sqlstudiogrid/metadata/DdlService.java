package com.quasera.sqlstudiogrid.metadata;

import com.quasera.sqlstudiogrid.core.DbType;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Obtiene el DDL (CREATE ...) de tablas, vistas y procedimientos.
 * Usa la herramienta nativa de cada motor cuando existe; si no, lo arma desde los metadatos.
 */
public final class DdlService {

    public enum ObjectKind { TABLE, VIEW, PROCEDURE }

    private final Connection connection;
    private final DbType type;

    public DdlService(Connection connection, DbType type) {
        this.connection = connection;
        this.type = type;
    }

    public String ddl(ObjectKind kind, String schema, String name) throws SQLException {
        String text = switch (type) {
            case ORACLE -> oracle(kind, schema, name);
            case MYSQL -> mysql(kind, schema, name);
            case SQL_SERVER -> kind == ObjectKind.TABLE ? fromMetadata(schema, name) : sqlServerDefinition(schema, name);
            case SYBASE -> kind == ObjectKind.TABLE ? fromMetadata(schema, name) : sybaseHelpText(schema, name);
        };
        if (text == null || text.isBlank()) {
            return "-- No se encontró la definición de " + type.qualified(schema, name)
                    + "\n-- (puede faltar permiso para ver el código del objeto)\n";
        }
        String body = text.strip() + "\n";
        if (type == DbType.SQL_SERVER || type == DbType.SYBASE) {
            body += "GO\n";
        }
        return body;
    }

    // ---------------------------------------------------------------- Oracle

    private String oracle(ObjectKind kind, String schema, String name) throws SQLException {
        String[] types = switch (kind) {
            case TABLE -> new String[]{"TABLE"};
            case VIEW -> new String[]{"VIEW"};
            case PROCEDURE -> new String[]{"PROCEDURE", "FUNCTION", "PACKAGE"};
        };
        SQLException last = null;
        for (String objectType : types) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM dual")) {
                ps.setString(1, objectType);
                ps.setString(2, name);
                ps.setString(3, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String ddl = rs.getString(1);
                        return ddl == null ? null : ddl.strip() + (kind == ObjectKind.PROCEDURE ? "\n/" : ";");
                    }
                }
            } catch (SQLException e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }

    // ---------------------------------------------------------------- MySQL

    private String mysql(ObjectKind kind, String schema, String name) throws SQLException {
        String target = type.qualified(schema, name);
        return switch (kind) {
            case TABLE -> singleValue("SHOW CREATE TABLE " + target, 2) + ";";
            case VIEW -> singleValue("SHOW CREATE VIEW " + target, 2) + ";";
            case PROCEDURE -> {
                try {
                    yield "DELIMITER //\n" + singleValue("SHOW CREATE PROCEDURE " + target, 3) + " //\nDELIMITER ;";
                } catch (SQLException e) {
                    yield "DELIMITER //\n" + singleValue("SHOW CREATE FUNCTION " + target, 3) + " //\nDELIMITER ;";
                }
            }
        };
    }

    private String singleValue(String sql, int column) throws SQLException {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(column) : null;
        }
    }

    // ---------------------------------------------------------------- SQL Server

    private String sqlServerDefinition(String schema, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT OBJECT_DEFINITION(OBJECT_ID(?))")) {
            ps.setString(1, type.qualified(schema, name));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ---------------------------------------------------------------- Sybase

    /** sp_helptext devuelve el código en filas de hasta 255 caracteres en la columna "text". */
    private String sybaseHelpText(String schema, String name) throws SQLException {
        String objectName = (schema == null || schema.isBlank() ? "" : schema + ".") + name;
        StringBuilder sb = new StringBuilder();
        try (Statement st = connection.createStatement()) {
            boolean isResultSet = st.execute("exec sp_helptext '" + objectName.replace("'", "''") + "'");
            for (int guard = 0; guard < 100; guard++) {
                if (isResultSet) {
                    try (ResultSet rs = st.getResultSet()) {
                        ResultSetMetaData md = rs.getMetaData();
                        int textColumn = -1;
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            if ("text".equalsIgnoreCase(md.getColumnLabel(i))) {
                                textColumn = i;
                            }
                        }
                        while (rs.next()) {
                            if (textColumn > 0) {
                                String part = rs.getString(textColumn);
                                sb.append(part == null ? "" : part);
                            }
                        }
                    }
                } else if (st.getUpdateCount() == -1) {
                    break;
                }
                isResultSet = st.getMoreResults();
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- genérico

    /** CREATE TABLE armado con DatabaseMetaData (columnas, nulos, defaults y llave primaria). */
    private String fromMetadata(String schema, String name) throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        String schemaPattern = schema == null || schema.isBlank() ? null : MetadataService.escape(md, schema);
        List<String> lines = new ArrayList<>();
        try (ResultSet rs = md.getColumns(null, schemaPattern, MetadataService.escape(md, name), "%")) {
            while (rs.next()) {
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                int scale = rs.getInt("DECIMAL_DIGITS");
                boolean scaleNull = rs.wasNull();
                String def = rs.getString("COLUMN_DEF");
                boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                StringBuilder col = new StringBuilder("    ")
                        .append(type.quote(rs.getString("COLUMN_NAME"))).append(' ').append(typeName);
                if (MetadataService.needsSize(typeName) && size > 0) {
                    boolean decimal = typeName.equalsIgnoreCase("decimal") || typeName.equalsIgnoreCase("numeric");
                    col.append('(').append(size).append(decimal && !scaleNull ? "," + scale : "").append(')');
                }
                if (def != null && !def.isBlank()) {
                    col.append(" DEFAULT ").append(def.strip());
                }
                col.append(nullable ? " NULL" : " NOT NULL");
                lines.add(col.toString());
            }
        }
        if (lines.isEmpty()) {
            return null;
        }
        Map<Integer, String> pk = new TreeMap<>();
        String pkName = null;
        try (ResultSet rs = md.getPrimaryKeys(null, schema == null || schema.isBlank() ? null : schema, name)) {
            while (rs.next()) {
                pk.put(rs.getInt("KEY_SEQ"), type.quote(rs.getString("COLUMN_NAME")));
                pkName = rs.getString("PK_NAME");
            }
        }
        if (!pk.isEmpty()) {
            lines.add("    " + (pkName != null ? "CONSTRAINT " + type.quote(pkName) + " " : "")
                    + "PRIMARY KEY (" + String.join(", ", pk.values()) + ")");
        }
        return "-- DDL generado desde los metadatos (no incluye índices, triggers ni permisos)\n"
                + "CREATE TABLE " + type.qualified(schema, name) + " (\n"
                + String.join(",\n", lines) + "\n)";
    }
}
