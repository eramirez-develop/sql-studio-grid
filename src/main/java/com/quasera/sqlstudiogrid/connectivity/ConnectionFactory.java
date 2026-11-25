package com.quasera.sqlstudiogrid.connectivity;

import com.quasera.sqlstudiogrid.core.ConnectionProfile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** Abre conexiones JDBC a partir de un perfil. */
public final class ConnectionFactory {

    private static final int LOGIN_TIMEOUT_SECONDS = 15;

    private ConnectionFactory() {
    }

    public static Connection open(ConnectionProfile profile) throws SQLException {
        try {
            Class.forName(profile.type().driverClass());
        } catch (ClassNotFoundException e) {
            throw new SQLException("No se encontró el driver JDBC " + profile.type().driverClass()
                    + ". Verifique que esté en el classpath.", e);
        }
        Properties props = new Properties();
        if (!profile.user().isEmpty()) {
            props.setProperty("user", profile.user());
        }
        props.setProperty("password", profile.password());
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        return DriverManager.getConnection(profile.jdbcUrl(), props);
    }
}
