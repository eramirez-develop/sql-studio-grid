package com.quasera.sqlstudiogrid.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Guarda y carga las conexiones en un archivo .properties (contraseñas cifradas). */
public final class ProfileStore {

    private final Path file;
    private final CredentialCipher cipher;

    public ProfileStore(Path file, CredentialCipher cipher) {
        this.file = file;
        this.cipher = cipher;
    }

    public List<ConnectionProfile> load() throws IOException {
        List<ConnectionProfile> result = new ArrayList<>();
        if (!Files.exists(file)) {
            return result;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        int count = parseInt(props.getProperty("count"), 0);
        for (int i = 0; i < count; i++) {
            String prefix = "profile." + i + ".";
            DbType type;
            try {
                type = DbType.valueOf(props.getProperty(prefix + "type", ""));
            } catch (IllegalArgumentException e) {
                continue; // entrada dañada o motor desconocido
            }
            String password;
            try {
                password = cipher.decrypt(props.getProperty(prefix + "password", ""));
            } catch (IllegalStateException e) {
                password = "";
            }
            result.add(new ConnectionProfile(
                    props.getProperty(prefix + "id"),
                    props.getProperty(prefix + "name"),
                    type,
                    props.getProperty(prefix + "host"),
                    parseInt(props.getProperty(prefix + "port"), type.defaultPort()),
                    props.getProperty(prefix + "database"),
                    props.getProperty(prefix + "user"),
                    password,
                    props.getProperty(prefix + "customUrl")));
        }
        return result;
    }

    public void save(List<ConnectionProfile> profiles) throws IOException {
        Properties props = new Properties();
        props.setProperty("count", String.valueOf(profiles.size()));
        for (int i = 0; i < profiles.size(); i++) {
            ConnectionProfile p = profiles.get(i);
            String prefix = "profile." + i + ".";
            props.setProperty(prefix + "id", p.id());
            props.setProperty(prefix + "name", p.name());
            props.setProperty(prefix + "type", p.type().name());
            props.setProperty(prefix + "host", p.host());
            props.setProperty(prefix + "port", String.valueOf(p.port()));
            props.setProperty(prefix + "database", p.database());
            props.setProperty(prefix + "user", p.user());
            props.setProperty(prefix + "password", cipher.encrypt(p.password()));
            props.setProperty(prefix + "customUrl", p.customUrl());
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            props.store(writer, "SQL Studio Grid - conexiones");
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
