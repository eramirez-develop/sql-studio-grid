package com.quasera.sqlstudiogrid.core;

import java.util.Objects;
import java.util.UUID;

/**
 * Datos de una conexión guardada.
 *
 * @param customUrl URL JDBC opcional; si se informa, reemplaza a host/puerto/base
 *                  (útil para parámetros especiales o para usar jConnect en Sybase).
 */
public record ConnectionProfile(
        String id,
        String name,
        DbType type,
        String host,
        int port,
        String database,
        String user,
        String password,
        String customUrl) {

    public ConnectionProfile {
        Objects.requireNonNull(type, "type");
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        name = name == null ? "" : name.trim();
        host = host == null ? "" : host.trim();
        database = database == null ? "" : database.trim();
        user = user == null ? "" : user.trim();
        password = password == null ? "" : password;
        customUrl = customUrl == null ? "" : customUrl.trim();
    }

    public String jdbcUrl() {
        return customUrl.isEmpty() ? type.buildUrl(host, port, database) : customUrl;
    }

    @Override
    public String toString() {
        return name;
    }
}
