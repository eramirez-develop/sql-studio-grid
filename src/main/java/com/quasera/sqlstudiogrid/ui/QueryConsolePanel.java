package com.quasera.sqlstudiogrid.ui;

import com.quasera.sqlstudiogrid.connectivity.QueryExecutor;
import com.quasera.sqlstudiogrid.connectivity.QueryResult;
import com.quasera.sqlstudiogrid.core.ConnectionProfile;
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
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Una consola de consultas: editor arriba, resultados abajo, con su propia sesión. */
public final class QueryConsolePanel extends JPanel {

    public interface TitleListener {
        void titleChanged(QueryConsolePanel console);
    }

    private final ConnectionProfile profile;
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

    public QueryConsolePanel(ConnectionProfile profile, String initialSql) {
        super(new BorderLayout());
        this.profile = profile;
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
                }
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
