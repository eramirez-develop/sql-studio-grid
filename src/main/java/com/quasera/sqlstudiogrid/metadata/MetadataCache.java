package com.quasera.sqlstudiogrid.metadata;

import com.quasera.sqlstudiogrid.connectivity.ConnectionFactory;
import com.quasera.sqlstudiogrid.core.ConnectionProfile;
import com.quasera.sqlstudiogrid.core.DbType;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caché de nombres de tablas/vistas y columnas por conexión, para el autocompletado.
 * Usa su propia conexión para no interferir con las consultas en curso.
 * Todos los métodos que leen de la base deben llamarse fuera del hilo de la interfaz.
 */
public final class MetadataCache {

    /** Límite de objetos a cargar (evita listas enormes en catálogos grandes). */
    private static final int MAX_TABLES = 20_000;

    private static final Map<String, MetadataCache> CACHES = new ConcurrentHashMap<>();

    public record TableRef(String schema, String name, boolean view) {
    }

    private final ConnectionProfile profile;
    private Connection connection;
    private volatile List<TableRef> tables;
    private final Map<String, List<String>> columns = new ConcurrentHashMap<>();

    private MetadataCache(ConnectionProfile profile) {
        this.profile = profile;
    }

    public static MetadataCache of(ConnectionProfile profile) {
        return CACHES.computeIfAbsent(profile.id(), id -> new MetadataCache(profile));
    }

    /** Descarta la caché de un perfil (por ejemplo tras crear tablas o editar la conexión). */
    public static void invalidate(String profileId) {
        MetadataCache cache = CACHES.remove(profileId);
        if (cache != null) {
            cache.close();
        }
    }

    public static void closeAll() {
        CACHES.values().forEach(MetadataCache::close);
        CACHES.clear();
    }

    public synchronized List<TableRef> tables() throws SQLException {
        if (tables == null) {
            tables = loadTables();
        }
        return tables;
    }

    /** Columnas de una tabla; si no se indica esquema, se busca la tabla por nombre. */
    public synchronized List<String> columns(String schema, String table) throws SQLException {
        String resolvedSchema = schema;
        String resolvedTable = table;
        if (resolvedSchema == null || resolvedSchema.isBlank()) {
            for (TableRef t : tables()) {
                if (t.name().equalsIgnoreCase(table)) {
                    resolvedSchema = t.schema();
                    resolvedTable = t.name();
                    break;
                }
            }
        } else {
            for (TableRef t : tables()) {
                if (t.name().equalsIgnoreCase(table) && t.schema() != null && t.schema().equalsIgnoreCase(schema)) {
                    resolvedSchema = t.schema();
                    resolvedTable = t.name();
                    break;
                }
            }
        }
        String key = (resolvedSchema == null ? "" : resolvedSchema) + "." + resolvedTable;
        key = key.toLowerCase(Locale.ROOT);
        List<String> cached = columns.get(key);
        if (cached != null) {
            return cached;
        }
        List<String> loaded = loadColumns(resolvedSchema, resolvedTable);
        columns.put(key, loaded);
        return loaded;
    }

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = ConnectionFactory.open(profile);
        }
        return connection;
    }

    private List<TableRef> loadTables() throws SQLException {
        Connection c = connection();
        DatabaseMetaData md = c.getMetaData();
        String catalog = null;
        String schemaPattern = null;
        if (profile.type() == DbType.MYSQL) {
            catalog = c.getCatalog();
        } else if (profile.type() == DbType.ORACLE) {
            schemaPattern = MetadataService.escape(md, md.getUserName()); // solo el esquema del usuario
        }
        List<TableRef> result = new ArrayList<>();
        try (ResultSet rs = md.getTables(catalog, schemaPattern, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next() && result.size() < MAX_TABLES) {
                String schema = profile.type() == DbType.MYSQL ? rs.getString("TABLE_CAT") : rs.getString("TABLE_SCHEM");
                result.add(new TableRef(schema, rs.getString("TABLE_NAME"), "VIEW".equalsIgnoreCase(rs.getString("TABLE_TYPE"))));
            }
        }
        result.sort(Comparator.comparing(TableRef::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    private List<String> loadColumns(String schema, String table) throws SQLException {
        Connection c = connection();
        DatabaseMetaData md = c.getMetaData();
        boolean mysql = profile.type() == DbType.MYSQL;
        String catalog = mysql ? (schema == null ? c.getCatalog() : schema) : null;
        String schemaPattern = mysql || schema == null ? null : MetadataService.escape(md, schema);
        List<String> names = new ArrayList<>();
        try (ResultSet rs = md.getColumns(catalog, schemaPattern, MetadataService.escape(md, table), "%")) {
            while (rs.next()) {
                names.add(rs.getString("COLUMN_NAME"));
            }
        }
        return List.copyOf(names);
    }

    private synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // ya cerrada
            }
            connection = null;
        }
    }
}
