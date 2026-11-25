package com.quasera.sqlstudiogrid.ui;

import com.quasera.sqlstudiogrid.connectivity.QueryExecutor;
import com.quasera.sqlstudiogrid.connectivity.QueryResult;
import com.quasera.sqlstudiogrid.core.ConnectionProfile;
import com.quasera.sqlstudiogrid.core.HistoryStore;
import com.quasera.sqlstudiogrid.metadata.MetadataCache;
import com.quasera.sqlstudiogrid.sql.SqlCompletion;
import com.quasera.sqlstudiogrid.sql.SqlCompletion.Candidate;
import com.quasera.sqlstudiogrid.sql.SqlFormatter;
import com.quasera.sqlstudiogrid.sql.SqlKeywords;
import com.quasera.sqlstudiogrid.ui.editor.CompletionPopup;
import com.quasera.sqlstudiogrid.ui.editor.LineNumberView;
import com.quasera.sqlstudiogrid.ui.editor.SqlEditor;
import com.quasera.sqlstudiogrid.ui.grid.ResultPanel;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Una consola de consultas: editor arriba, resultados abajo, con su propia sesión. */
public final class QueryConsolePanel extends JPanel {

    public interface TitleListener {
        void titleChanged(QueryConsolePanel console);
    }

    private final ConnectionProfile profile;
    private final HistoryStore history;
    private final QueryExecutor executor;
    private final SqlEditor editor = new SqlEditor();
    private final ResultPanel results = new ResultPanel();
    private final JButton runButton = new JButton("▶ Ejecutar");
    private final JButton cancelButton = new JButton("■ Cancelar");
    private final JCheckBox autoCommit = new JCheckBox("Auto-commit", true);
    private final JButton commitButton = new JButton("Commit");
    private final JButton rollbackButton = new JButton("Rollback");
    private final JSpinner maxRows = new JSpinner(new SpinnerNumberModel(500, 0, 1_000_000, 100));
    private final JLabel status = new JLabel("Sin conectar");

    private SwingWorker<Void, QueryResult> running;
    private Path file;
    private boolean dirty;
    private TitleListener titleListener = c -> { };

    public QueryConsolePanel(ConnectionProfile profile, HistoryStore history, String initialSql) {
        super(new BorderLayout());
        this.profile = profile;
        this.history = history;
        this.executor = new QueryExecutor(profile);

        editor.setSql(initialSql);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                markDirty();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                markDirty();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // estilos
            }
        });

        new CompletionPopup(editor, this::complete);

        JScrollPane editorScroll = new JScrollPane(editor);
        editorScroll.setRowHeaderView(new LineNumberView(editor));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorScroll, results);
        split.setResizeWeight(0.45);
        split.setContinuousLayout(true);

        add(buildToolbar(), BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        add(status, BorderLayout.SOUTH);

        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "execute", this::execute);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "execute-f5", this::execute);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel", this::cancel);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                "format", this::format);

        updateButtons();
        dirty = false;
    }

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        runButton.setToolTipText("Ejecuta la selección, o todo el script si no hay selección (Ctrl+Enter / F5)");
        runButton.addActionListener(e -> execute());
        cancelButton.setToolTipText("Cancela la sentencia en curso (Esc)");
        cancelButton.addActionListener(e -> cancel());
        autoCommit.addActionListener(e -> changeAutoCommit());
        commitButton.addActionListener(e -> transaction(true));
        rollbackButton.addActionListener(e -> transaction(false));
        maxRows.setToolTipText("Máximo de filas a leer por resultado (0 = sin límite)");
        ((JSpinner.DefaultEditor) maxRows.getEditor()).getTextField().setColumns(6);
        maxRows.setMaximumSize(maxRows.getPreferredSize());

        bar.add(runButton);
        bar.add(cancelButton);
        bar.addSeparator();
        bar.add(autoCommit);
        bar.add(commitButton);
        bar.add(rollbackButton);
        bar.addSeparator();
        bar.add(new JLabel(" Máx. filas: "));
        bar.add(maxRows);
        bar.add(javax.swing.Box.createHorizontalGlue());
        bar.add(new JLabel(" " + profile.type().displayName() + " · " + profile.name()));
        return bar;
    }

    private void bindKey(KeyStroke key, String name, Runnable action) {
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key, name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    public ConnectionProfile profile() {
        return profile;
    }

    public ResultPanel results() {
        return results;
    }

    public void focusEditor() {
        editor.requestFocusInWindow();
    }

    // ---------------------------------------------------------------- ejecución

    public void execute() {
        if (running != null) {
            return;
        }
        String selected = editor.getSelectedText();
        String sql = (selected != null && !selected.isBlank()) ? selected : editor.getText();
        if (sql.isBlank()) {
            return;
        }
        int limit = (Integer) maxRows.getValue();
        results.clear();
        status.setText(executor.isConnected() ? "Ejecutando..." : "Conectando a " + profile.name() + "...");
        long start = System.currentTimeMillis();

        running = new SwingWorker<>() {
            private int count;
            private boolean failed;

            @Override
            protected Void doInBackground() throws Exception {
                executor.execute(sql, limit, this::publish);
                return null;
            }

            @Override
            protected void process(List<QueryResult> chunks) {
                for (QueryResult r : chunks) {
                    results.add(r);
                    count++;
                    failed |= r.kind() == QueryResult.Kind.ERROR;
                }
                status.setText("Ejecutando... (" + count + " resultado(s))");
            }

            @Override
            protected void done() {
                String outcome;
                try {
                    get();
                    outcome = "Listo · " + count + " resultado(s) · " + (System.currentTimeMillis() - start) + " ms";
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    outcome = "Interrumpido";
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    String msg = cause instanceof SQLException sqlEx
                            ? QueryExecutor.describe(sqlEx) : String.valueOf(cause);
                    results.add(QueryResult.error(sql, msg, 0));
                    outcome = "Error: " + firstLine(msg);
                    failed = true;
                }
                record(sql, !failed, System.currentTimeMillis() - start);
                running = null;
                status.setText(outcome + (executor.isConnected() ? "" : " · sin conexión"));
                updateButtons();
            }
        };
        updateButtons();
        running.execute();
    }

    public void cancel() {
        if (running != null) {
            status.setText("Cancelando...");
            Thread t = new Thread(executor::cancel, "cancel-query");
            t.setDaemon(true);
            t.start();
        }
    }

    private void changeAutoCommit() {
        boolean value = autoCommit.isSelected();
        background("Auto-commit " + (value ? "activado" : "desactivado"), () -> executor.setAutoCommit(value));
        updateButtons();
    }

    private void transaction(boolean commit) {
        background(commit ? "COMMIT realizado" : "ROLLBACK realizado", () -> {
            if (commit) {
                executor.commit();
            } else {
                executor.rollback();
            }
        });
    }

    private interface SqlAction {
        void run() throws SQLException;
    }

    /** Ejecuta una acción corta contra la base fuera del hilo de la interfaz. */
    private void background(String okMessage, SqlAction action) {
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                action.run();
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    results.log(okMessage);
                    status.setText(okMessage);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    String msg = cause instanceof SQLException sqlEx
                            ? QueryExecutor.describe(sqlEx) : String.valueOf(cause);
                    results.log("ERROR: " + msg);
                    status.setText("Error: " + firstLine(msg));
                }
            }
        }.execute();
    }

    private void updateButtons() {
        boolean busy = running != null;
        runButton.setEnabled(!busy);
        cancelButton.setEnabled(busy);
        autoCommit.setEnabled(!busy);
        commitButton.setEnabled(!busy && !autoCommit.isSelected());
        rollbackButton.setEnabled(!busy && !autoCommit.isSelected());
    }

    private void record(String sql, boolean ok, long elapsedMs) {
        try {
            history.add(new HistoryStore.Entry(Instant.now(), profile.name(), sql.strip(), ok, elapsedMs));
        } catch (IOException e) {
            results.log("No se pudo guardar en el historial: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- edición

    /** Formatea la selección, o todo el script si no hay selección (Ctrl+Shift+F). */
    public void format() {
        String selected = editor.getSelectedText();
        if (selected == null || selected.isBlank()) {
            editor.selectAll();
            selected = editor.getText();
        }
        if (selected.isBlank()) {
            return;
        }
        editor.replaceSelection(SqlFormatter.format(selected));
        focusEditor();
    }

    /** Inserta texto en la posición del cursor (por ejemplo desde el historial). */
    public void insertText(String text) {
        editor.replaceSelection(text);
        focusEditor();
    }

    /** Sugerencias de autocompletado: columnas, tablas, esquemas y palabras clave. */
    private List<Candidate> complete(String text, int caret) throws SQLException {
        MetadataCache cache = MetadataCache.of(profile);
        List<Candidate> out = new ArrayList<>();
        String qualifier = SqlCompletion.qualifierAt(text, caret);
        if (qualifier != null) {
            String target = SqlCompletion.aliases(text).getOrDefault(qualifier.toLowerCase(Locale.ROOT), qualifier);
            List<String> parts = SqlCompletion.splitQualified(target);
            if (!parts.isEmpty()) {
                String table = parts.getLast();
                String schema = parts.size() >= 2 ? parts.get(parts.size() - 2) : null;
                for (String column : cache.columns(schema, table)) {
                    out.add(new Candidate(column, SqlCompletion.Kind.COLUMN, table));
                }
            }
            if (out.isEmpty()) {
                // el calificador puede ser un esquema: "dbo." -> tablas de dbo
                for (MetadataCache.TableRef t : cache.tables()) {
                    if (qualifier.equalsIgnoreCase(t.schema())) {
                        out.add(new Candidate(t.name(), t.view() ? SqlCompletion.Kind.VIEW : SqlCompletion.Kind.TABLE,
                                t.schema()));
                    }
                }
            }
            return out;
        }
        Set<String> seen = new java.util.HashSet<>();
        for (String ref : new LinkedHashSet<>(SqlCompletion.aliases(text).values())) {
            List<String> parts = SqlCompletion.splitQualified(ref);
            if (parts.isEmpty()) {
                continue;
            }
            String table = parts.getLast();
            String schema = parts.size() >= 2 ? parts.get(parts.size() - 2) : null;
            for (String column : cache.columns(schema, table)) {
                if (seen.add(column.toLowerCase(Locale.ROOT))) {
                    out.add(new Candidate(column, SqlCompletion.Kind.COLUMN, table));
                }
            }
        }
        Set<String> schemas = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (MetadataCache.TableRef t : cache.tables()) {
            out.add(new Candidate(t.name(), t.view() ? SqlCompletion.Kind.VIEW : SqlCompletion.Kind.TABLE, t.schema()));
            if (t.schema() != null) {
                schemas.add(t.schema());
            }
        }
        schemas.forEach(s -> out.add(new Candidate(s, SqlCompletion.Kind.SCHEMA, "")));
        new TreeSet<>(SqlKeywords.KEYWORDS).forEach(k -> out.add(new Candidate(k, SqlCompletion.Kind.KEYWORD, "")));
        return out;
    }

    // ---------------------------------------------------------------- archivos

    public Path file() {
        return file;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setTitleListener(TitleListener listener) {
        this.titleListener = listener;
    }

    public String title() {
        String base = file != null ? file.getFileName().toString() : profile.name();
        return (dirty ? "*" : "") + base;
    }

    public void load(Path path) throws IOException {
        editor.setSql(Files.readString(path, StandardCharsets.UTF_8));
        file = path;
        dirty = false;
        titleListener.titleChanged(this);
    }

    public void save(Path path) throws IOException {
        Files.writeString(path, editor.getText(), StandardCharsets.UTF_8);
        file = path;
        dirty = false;
        titleListener.titleChanged(this);
    }

    private void markDirty() {
        if (!dirty) {
            dirty = true;
            titleListener.titleChanged(this);
        }
    }

    /** Cierra la sesión en segundo plano (no bloquea la interfaz). */
    public void close() {
        if (running != null) {
            executor.cancel();
        }
        Thread t = new Thread(executor::close, "close-console");
        t.setDaemon(false);
        t.start();
    }

    private static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }
}
