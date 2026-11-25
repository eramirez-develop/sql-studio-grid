package com.quasera.sqlstudiogrid.core;

import com.quasera.sqlstudiogrid.connectivity.SqlScriptSplitter;

/**
 * Motores de base de datos soportados y sus particularidades
 * (driver, URL, comillas, consulta de vista previa y separadores de script).
 */
public enum DbType {

    SQL_SERVER("SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433),
    SYBASE("Sybase ASE", "net.sourceforge.jtds.jdbc.Driver", 5000),
    ORACLE("Oracle", "oracle.jdbc.OracleDriver", 1521),
    MYSQL("MySQL", "com.mysql.cj.jdbc.Driver", 3306);

    private final String displayName;
    private final String driverClass;
    private final int defaultPort;

    DbType(String displayName, String driverClass, int defaultPort) {
        this.displayName = displayName;
        this.driverClass = driverClass;
        this.defaultPort = defaultPort;
    }

    public String displayName() {
        return displayName;
    }

    public String driverClass() {
        return driverClass;
    }

    public int defaultPort() {
        return defaultPort;
    }

    /** Construye la URL JDBC a partir de host, puerto y base de datos (o servicio en Oracle). */
    public String buildUrl(String host, int port, String database) {
        String h = (host == null || host.isBlank()) ? "localhost" : host.trim();
        int p = port > 0 ? port : defaultPort;
        String db = database == null ? "" : database.trim();
        return switch (this) {
            case SQL_SERVER -> "jdbc:sqlserver://" + h + ":" + p
                    + (db.isEmpty() ? "" : ";databaseName=" + db)
                    + ";encrypt=true;trustServerCertificate=true";
            case SYBASE -> "jdbc:jtds:sybase://" + h + ":" + p + (db.isEmpty() ? "" : "/" + db);
            case ORACLE -> "jdbc:oracle:thin:@//" + h + ":" + p + "/" + db;
            case MYSQL -> "jdbc:mysql://" + h + ":" + p + "/" + db;
        };
    }

    /** Pone comillas a un identificador según el motor. */
    public String quote(String identifier) {
        return switch (this) {
            case SQL_SERVER -> "[" + identifier.replace("]", "]]") + "]";
            // Sybase: se deja sin comillas (quoted_identifier suele estar desactivado)
            case SYBASE -> identifier;
            case ORACLE -> "\"" + identifier.replace("\"", "\"\"") + "\"";
            case MYSQL -> "`" + identifier.replace("`", "``") + "`";
        };
    }

    /** Nombre calificado esquema.tabla (sin esquema si viene vacío). */
    public String qualified(String schema, String name) {
        return (schema == null || schema.isBlank()) ? quote(name) : quote(schema) + "." + quote(name);
    }

    /** Consulta para ver las primeras filas de una tabla o vista. */
    public String previewSql(String schema, String table, int limit) {
        String target = qualified(schema, table);
        return switch (this) {
            case SQL_SERVER, SYBASE -> "SELECT TOP " + limit + " * FROM " + target;
            case ORACLE -> "SELECT * FROM " + target + " FETCH FIRST " + limit + " ROWS ONLY";
            case MYSQL -> "SELECT * FROM " + target + " LIMIT " + limit;
        };
    }

    /**
     * Cómo se divide un script en lotes:
     * SQL Server y Sybase usan GO (como SSMS), Oracle ';' y '/', MySQL ';'.
     */
    public SqlScriptSplitter.Options scriptOptions() {
        return switch (this) {
            case SQL_SERVER, SYBASE -> new SqlScriptSplitter.Options(false, true, false, true);
            case ORACLE -> new SqlScriptSplitter.Options(true, false, true, false);
            case MYSQL -> new SqlScriptSplitter.Options(true, false, false, false);
        };
    }

    @Override
    public String toString() {
        return displayName;
    }
}
