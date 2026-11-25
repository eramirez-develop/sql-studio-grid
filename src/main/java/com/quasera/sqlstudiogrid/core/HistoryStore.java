package com.quasera.sqlstudiogrid.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Historial de consultas ejecutadas, guardado en un archivo de texto (una línea por ejecución).
 * Conserva las últimas {@link #MAX_ENTRIES} entradas.
 */
public final class HistoryStore {

    public static final int MAX_ENTRIES = 1000;

    public record Entry(Instant time, String connection, String sql, boolean ok, long elapsedMs) {
    }

    private final Path file;

    public HistoryStore(Path file) {
        this.file = file;
    }

    public synchronized void add(Entry entry) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        String line = entry.time().toEpochMilli() + "\t" + escape(entry.connection()) + "\t"
                + (entry.ok() ? "1" : "0") + "\t" + entry.elapsedMs() + "\t" + escape(entry.sql()) + "\n";
        Files.writeString(file, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Devuelve las entradas más recientes primero; recorta el archivo si creció demasiado. */
    public synchronized List<Entry> load() throws IOException {
        List<Entry> result = new ArrayList<>();
        if (!Files.exists(file)) {
            return result;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() > MAX_ENTRIES * 2) {
            lines = new ArrayList<>(lines.subList(lines.size() - MAX_ENTRIES, lines.size()));
            Files.write(file, lines, StandardCharsets.UTF_8);
        }
        int from = Math.max(0, lines.size() - MAX_ENTRIES);
        for (int i = lines.size() - 1; i >= from; i--) {
            String[] parts = lines.get(i).split("\t", 5);
            if (parts.length < 5) {
                continue;
            }
            try {
                result.add(new Entry(Instant.ofEpochMilli(Long.parseLong(parts[0])), unescape(parts[1]),
                        unescape(parts[4]), "1".equals(parts[2]), Long.parseLong(parts[3])));
            } catch (NumberFormatException ignored) {
                // línea dañada
            }
        }
        return result;
    }

    public synchronized void clear() throws IOException {
        Files.deleteIfExists(file);
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(switch (n) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> n;
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
