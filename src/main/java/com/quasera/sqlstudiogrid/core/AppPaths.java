package com.quasera.sqlstudiogrid.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Rutas de configuración local: ~/.sql-studio-grid */
public final class AppPaths {

    private AppPaths() {
    }

    public static Path home() {
        Path dir = Path.of(System.getProperty("user.home"), ".sql-studio-grid");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear " + dir, e);
        }
        return dir;
    }

    public static Path connectionsFile() {
        return home().resolve("connections.properties");
    }

    public static Path historyFile() {
        return home().resolve("history.tsv");
    }

    public static Path keyFile() {
        return home().resolve("secret.key");
    }
}
