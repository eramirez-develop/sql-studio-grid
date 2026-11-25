package com.quasera.sqlstudiogrid.ui.grid;

import javax.swing.table.TableModel;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exporta un TableModel a CSV en UTF-8 con BOM (Excel lo abre con tildes correctas). */
public final class CsvExporter {

    private CsvExporter() {
    }

    public static void export(TableModel model, Path file, char separator) throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write('﻿');
            int columns = model.getColumnCount();
            for (int c = 0; c < columns; c++) {
                if (c > 0) {
                    w.write(separator);
                }
                w.write(escape(model.getColumnName(c), separator));
            }
            w.write("\r\n");
            for (int r = 0; r < model.getRowCount(); r++) {
                for (int c = 0; c < columns; c++) {
                    if (c > 0) {
                        w.write(separator);
                    }
                    Object value = model.getValueAt(r, c);
                    w.write(value == null ? "" : escape(String.valueOf(value), separator));
                }
                w.write("\r\n");
            }
        }
    }

    static String escape(String value, char separator) {
        boolean needsQuotes = value.indexOf(separator) >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        return needsQuotes ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
