package com.quasera.sqlstudiogrid.ui;

import com.quasera.sqlstudiogrid.core.ConnectionProfile;
import com.quasera.sqlstudiogrid.core.HistoryStore;
import com.quasera.sqlstudiogrid.core.ProfileStore;
import com.quasera.sqlstudiogrid.metadata.MetadataCache;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Ventana principal: explorador a la izquierda, consolas en pestañas a la derecha. */
public final class MainFrame extends JFrame implements ExplorerPanel.Actions {

    public static final String APP_NAME = "SQL Studio Grid";
    public static final String VERSION = "0.2.0";

    private static final String CARD_WELCOME = "welcome";
    private static final String CARD_CONSOLES = "consoles";

    private final ProfileStore store;
    private final HistoryStore history;
    private HistoryDialog historyDialog;
    private final List<ConnectionProfile> profiles = new ArrayList<>();
    private final ExplorerPanel explorer = new ExplorerPanel(this);
    private final JTabbedPane consoles = new JTabbedPane();
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final JLabel statusBar = new JLabel(" ");

    public MainFrame(ProfileStore store, HistoryStore history, List<ConnectionProfile> initialProfiles) {
        super(APP_NAME);
        this.store = store;
        this.history = history;
        this.profiles.addAll(initialProfiles);

        consoles.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        consoles.addChangeListener(e -> activeConsole().ifPresent(QueryConsolePanel::focusEditor));

        center.add(buildWelcome(), CARD_WELCOME);
        center.add(consoles, CARD_CONSOLES);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, explorer, center);
        split.setDividerLocation(300);
        split.setContinuousLayout(true);

        statusBar.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);
        setJMenuBar(buildMenu());

        explorer.setProfiles(profiles);
        updateCenter();
        updateStatus();

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }
        });
        setSize(1280, 800);
        setLocationRelativeTo(null);
    }

    // ---------------------------------------------------------------- menú

    private JMenuBar buildMenu() {
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("Archivo");
        file.add(item("Nueva conexión...", KeyStroke.getKeyStroke(KeyEvent.VK_N,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), this::newConnection));
        file.add(item("Nueva consola", KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK),
                this::newConsole));
        file.addSeparator();
        file.add(item("Abrir script...", KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK),
                this::openScript));
        file.add(item("Guardar script", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK),
                () -> saveScript(false)));
        file.add(item("Guardar script como...", KeyStroke.getKeyStroke(KeyEvent.VK_S,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), () -> saveScript(true)));
        file.add(item("Cerrar consola", KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK),
                () -> activeConsole().ifPresent(this::closeConsole)));
        file.addSeparator();
        file.add(item("Salir", null, this::exit));

        JMenu query = new JMenu("Consulta");
        query.add(item("Ejecutar (Ctrl+Enter / F5)", null,
                () -> activeConsole().ifPresent(QueryConsolePanel::execute)));
        query.add(item("Cancelar (Esc)", null, () -> activeConsole().ifPresent(QueryConsolePanel::cancel)));
        query.addSeparator();
        query.add(item("Formatear SQL (Ctrl+Shift+F)", null,
                () -> activeConsole().ifPresent(QueryConsolePanel::format)));
        query.add(item("Actualizar datos del autocompletado", null, this::refreshCompletion));
        query.addSeparator();
        query.add(item("Historial de consultas...", KeyStroke.getKeyStroke(KeyEvent.VK_H,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), this::showHistory));
        query.addSeparator();
        query.add(item("Exportar resultado a CSV...", KeyStroke.getKeyStroke(KeyEvent.VK_E,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                () -> activeConsole().ifPresent(c -> c.results().exportSelected())));

        JMenu help = new JMenu("Ayuda");
        help.add(item("Acerca de " + APP_NAME, null, () -> JOptionPane.showMessageDialog(this,
                APP_NAME + " " + VERSION + "\nCliente de bases de datos de escritorio.\n\n"
                        + "Motores: SQL Server, Sybase ASE, Oracle, MySQL\n"
                        + "Java " + System.getProperty("java.version"),
                "Acerca de", JOptionPane.INFORMATION_MESSAGE)));

        bar.add(file);
        bar.add(query);
        bar.add(help);
        return bar;
    }

    private static JMenuItem item(String text, KeyStroke key, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        if (key != null) {
            item.setAccelerator(key);
        }
        item.addActionListener(e -> action.run());
        return item;
    }

    private JPanel buildWelcome() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel title = new JLabel("<html><div style='text-align:center'><h1>" + APP_NAME + "</h1>"
                + "Cree una conexión para empezar, o haga doble clic en una tabla del explorador.<br><br>"
                + "Ctrl+T nueva consola · Ctrl+Enter ejecutar · Ctrl+O abrir script</div></html>",
                SwingConstants.CENTER);
        JButton create = new JButton("+ Nueva conexión");
        create.setMargin(new Insets(6, 16, 6, 16));
        create.addActionListener(e -> newConnection());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER));
        buttons.add(create);
        JPanel box = new JPanel(new BorderLayout());
        box.add(title, BorderLayout.CENTER);
        box.add(buttons, BorderLayout.SOUTH);
        panel.add(box, BorderLayout.CENTER);
        return panel;
    }

    // ---------------------------------------------------------------- conexiones

    @Override
    public void newConnection() {
        ConnectionDialog.show(this, null).ifPresent(p -> {
            profiles.add(p);
            persist();
            explorer.setProfiles(profiles);
            updateStatus();
        });
    }

    @Override
    public void editConnection(ConnectionProfile profile) {
        ConnectionDialog.show(this, profile).ifPresent(updated -> {
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).id().equals(updated.id())) {
                    profiles.set(i, updated);
                }
            }
            persist();
            MetadataCache.invalidate(profile.id());
            explorer.disconnect(profile);
            explorer.setProfiles(profiles);
            statusBar.setText("Conexión actualizada. Las consolas abiertas siguen usando la configuración anterior.");
        });
    }

    @Override
    public void deleteConnection(ConnectionProfile profile) {
        int answer = JOptionPane.showConfirmDialog(this,
                "¿Eliminar la conexión \"" + profile.name() + "\"?", "Eliminar conexión",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.YES_OPTION) {
            return;
        }
        profiles.removeIf(p -> p.id().equals(profile.id()));
        persist();
        MetadataCache.invalidate(profile.id());
        explorer.disconnect(profile);
        explorer.setProfiles(profiles);
        updateStatus();
    }

    private void persist() {
        try {
            store.save(profiles);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "No se pudieron guardar las conexiones: " + e.getMessage(),
                    APP_NAME, JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------------------------------------------------------------- consolas

    @Override
    public void openConsole(ConnectionProfile profile, String sql, boolean run) {
        QueryConsolePanel console = new QueryConsolePanel(profile, history, sql);
        addConsole(console);
        if (run) {
            SwingUtilities.invokeLater(console::execute);
        }
    }

    private void addConsole(QueryConsolePanel console) {
        consoles.addTab(console.title(), console);
        int index = consoles.indexOfComponent(console);
        consoles.setTabComponentAt(index, new ClosableTab(console.title(), () -> closeConsole(console)));
        consoles.setToolTipTextAt(index, console.profile().type().displayName() + " · " + console.profile().jdbcUrl());
        console.setTitleListener(c -> {
            int i = consoles.indexOfComponent(c);
            if (i >= 0 && consoles.getTabComponentAt(i) instanceof ClosableTab tab) {
                tab.setTitle(c.title());
            }
        });
        consoles.setSelectedComponent(console);
        updateCenter();
        console.focusEditor();
    }

    private void newConsole() {
        chooseProfile().ifPresent(p -> openConsole(p, "", false));
    }

    private Optional<ConnectionProfile> chooseProfile() {
        Optional<ConnectionProfile> selected = explorer.selectedProfile();
        if (selected.isPresent()) {
            return selected;
        }
        if (profiles.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Primero cree una conexión.", APP_NAME,
                    JOptionPane.INFORMATION_MESSAGE);
            newConnection();
            return Optional.empty();
        }
        if (profiles.size() == 1) {
            return Optional.of(profiles.getFirst());
        }
        JComboBox<ConnectionProfile> combo = new JComboBox<>(profiles.toArray(ConnectionProfile[]::new));
        int answer = JOptionPane.showConfirmDialog(this, combo, "Elija la conexión",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        return answer == JOptionPane.OK_OPTION
                ? Optional.ofNullable((ConnectionProfile) combo.getSelectedItem()) : Optional.empty();
    }

    private Optional<QueryConsolePanel> activeConsole() {
        return consoles.getSelectedComponent() instanceof QueryConsolePanel c ? Optional.of(c) : Optional.empty();
    }

    /** @return false si el usuario canceló el cierre */
    private boolean closeConsole(QueryConsolePanel console) {
        if (console.isDirty()) {
            consoles.setSelectedComponent(console);
            int answer = JOptionPane.showConfirmDialog(this,
                    "La consola \"" + console.title().replace("*", "") + "\" tiene cambios sin guardar. ¿Guardar?",
                    APP_NAME, JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) {
                return false;
            }
            if (answer == JOptionPane.YES_OPTION && !save(console, false)) {
                return false;
            }
        }
        console.close();
        consoles.remove(console);
        updateCenter();
        return true;
    }

    private void openScript() {
        JFileChooser chooser = sqlChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path path = chooser.getSelectedFile().toPath();
        chooseProfile().ifPresent(profile -> {
            QueryConsolePanel console = new QueryConsolePanel(profile, history, "");
            try {
                console.load(path);
                addConsole(console);
            } catch (IOException e) {
                console.close();
                JOptionPane.showMessageDialog(this, "No se pudo abrir: " + e.getMessage(), APP_NAME,
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void saveScript(boolean saveAs) {
        activeConsole().ifPresent(c -> save(c, saveAs));
    }

    private boolean save(QueryConsolePanel console, boolean saveAs) {
        Path target = console.file();
        if (target == null || saveAs) {
            JFileChooser chooser = sqlChooser();
            chooser.setSelectedFile(new File(target != null ? target.getFileName().toString() : "consulta.sql"));
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return false;
            }
            File f = chooser.getSelectedFile();
            if (!f.getName().contains(".")) {
                f = new File(f.getParentFile(), f.getName() + ".sql");
            }
            target = f.toPath();
        }
        try {
            console.save(target);
            statusBar.setText("Guardado: " + target);
            return true;
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "No se pudo guardar: " + e.getMessage(), APP_NAME,
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private static JFileChooser sqlChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Scripts SQL (*.sql)", "sql"));
        return chooser;
    }

    private void showHistory() {
        if (historyDialog == null || !historyDialog.isDisplayable()) {
            historyDialog = new HistoryDialog(this, history, sql -> {
                Optional<QueryConsolePanel> console = activeConsole();
                if (console.isPresent()) {
                    console.get().insertText(sql);
                } else {
                    chooseProfile().ifPresent(p -> openConsole(p, sql, false));
                }
            });
        }
        historyDialog.setVisible(true);
        historyDialog.toFront();
    }

    private void refreshCompletion() {
        Optional<ConnectionProfile> profile = activeConsole().map(QueryConsolePanel::profile)
                .or(explorer::selectedProfile);
        profile.ifPresent(p -> {
            MetadataCache.invalidate(p.id());
            statusBar.setText("Se volverán a leer tablas y columnas de \"" + p.name() + "\" en el próximo autocompletado.");
        });
    }

    // ---------------------------------------------------------------- general

    private void updateCenter() {
        cards.show(center, consoles.getTabCount() == 0 ? CARD_WELCOME : CARD_CONSOLES);
    }

    private void updateStatus() {
        statusBar.setText(profiles.size() + " conexión(es) guardada(s) · configuración en "
                + System.getProperty("user.home") + File.separator + ".sql-studio-grid");
    }

    private void exit() {
        for (int i = consoles.getTabCount() - 1; i >= 0; i--) {
            if (consoles.getComponentAt(i) instanceof QueryConsolePanel c && !closeConsole(c)) {
                return;
            }
        }
        explorer.closeAll();
        MetadataCache.closeAll();
        dispose();
        System.exit(0);
    }
}
