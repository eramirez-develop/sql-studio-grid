package com.quasera.sqlstudiogrid.metadata;

import com.quasera.sqlstudiogrid.core.DbType;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Lee la estructura de la base (esquemas, tablas, vistas, procedimientos, columnas)
 * usando DatabaseMetaData, que funciona igual en los cuatro motores.
 * En MySQL los "esquemas" son catálogos.
 */
public final class MetadataService {

    private final Connection connection;
    private final DbType type;

    public MetadataService(Connection connection, DbType type) {
        this.connection = connection;
        this.type = type;
    }

    public List<String> schemas() throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        List<String> names = new ArrayList<>();
        if (type == DbType.MYSQL) {
            try (ResultSet rs = md.getCatalogs()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        } else {
            try (ResultSet rs = md.getSchemas()) {
                while (rs.next()) {
                    names.add(rs.getString("TABLE_SCHEM"));
                }
            }
        }
        return sorted(names);
    }

    public List<String> tables(String schema) throws SQLException {
        return objects(schema, "TABLE");
    }

    public List<String> views(String schema) throws SQLException {
        return objects(schema, "VIEW");
    }

    private List<String> objects(String schema, String tableType) throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        List<String> names = new ArrayList<>();
        try (ResultSet rs = md.getTables(catalog(schema), schemaPattern(md, schema), "%", new String[]{tableType})) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
        }
        return sorted(names);
    }

    public List<String> procedures(String schema) throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        List<String> names = new ArrayList<>();
        try (ResultSet rs = md.getProcedures(catalog(schema), schemaPattern(md, schema), "%")) {
            while (rs.next()) {
                String name = rs.getString("PROCEDURE_NAME");
                // SQL Server y Sybase devuelven "nombre;1"
                names.add(name == null ? null : name.replaceFirst(";\\d+$", ""));
            }
        }
        return sorted(names);
    }

    public List<String> columns(String schema, String table) throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        List<String> result = new ArrayList<>();
        try (ResultSet rs = md.getColumns(catalog(schema), schemaPattern(md, schema), escape(md, table), "%")) {
            while (rs.next()) {
                String typeName = rs.getString("TYPE_NAME");
                int size = rs.getInt("COLUMN_SIZE");
                boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                result.add(rs.getString("COLUMN_NAME") + "  " + typeName
                        + (size > 0 && needsSize(typeName) ? "(" + size + ")" : "")
                        + (nullable ? "" : "  NOT NULL"));
            }
        }
        return result;
    }

    static boolean needsSize(String typeName) {
        if (typeName == null) {
            return false;
        }
        String t = typeName.toLowerCase();
        return t.contains("char") || t.contains("binary") || t.equals("decimal") || t.equals("numeric")
                || t.equals("number") || t.equals("raw");
    }

    private String catalog(String schema) {
        return type == DbType.MYSQL ? blankToNull(schema) : null;
    }

    private String schemaPattern(DatabaseMetaData md, String schema) throws SQLException {
        if (type == DbType.MYSQL || schema == null || schema.isBlank()) {
            return null;
        }
        return escape(md, schema);
    }

    /** Escapa los comodines '_' y '%' que DatabaseMetaData interpreta como patrón. */
    public static String escape(DatabaseMetaData md, String value) throws SQLException {
        String esc = md.getSearchStringEscape();
        if (esc == null || esc.isEmpty() || value == null) {
            return value;
        }
        return value.replace(esc, esc + esc).replace("_", esc + "_").replace("%", esc + "%");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static List<String> sorted(List<String> names) {
        return names.stream().filter(Objects::nonNull).distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
}
