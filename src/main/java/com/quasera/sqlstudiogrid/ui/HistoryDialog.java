package com.quasera.sqlstudiogrid.ui;

import com.quasera.sqlstudiogrid.core.HistoryStore;
import com.quasera.sqlstudiogrid.core.HistoryStore.Entry;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Ventana con el historial de consultas: buscar, ver e insertar en la consola. */
public final class HistoryDialog extends JDialog {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final HistoryStore store;
    private final Consumer<String> insert;
    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> visible = new ArrayList<>();
    private final HistoryModel model = new HistoryModel();
    private final JTable table = new JTable(model);
    private final JTextArea preview = new JTextArea();
    private final JTextField filter = new JTextField(30);

    public HistoryDialog(Window owner, HistoryStore store, Consumer<String> insert) {
        super(owner, "Historial de consultas", ModalityType.MODELESS);
        this.store = store;
        this.insert = insert;

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(140);
        table.getColumnModel().getColumn(1).setPreferredWidth(140);
        table.getColumnModel().getColumn(2).setPreferredWidth(50);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        table.getColumnModel().getColumn(4).setPreferredWidth(500);
        table.getSelectionModel().addListSelectionListener(e -> showSelected());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    insertSelected();
                }
            }
        });

        preview.setEditable(false);
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Buscar:"));
        top.add(filter);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(preview));
        split.setResizeWeight(0.6);

        JButton insertButton = new JButton("Insertar en consola");
        insertButton.addActionListener(e -> insertSelected());
        JButton copyButton = new JButton("Copiar");
        copyButton.addActionListener(e -> {
            Entry entry = selected();
            if (entry != null) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(entry.sql()), null);
            }
        });
        JButton clearButton = new JButton("Borrar historial");
        clearButton.addActionListener(e -> clearHistory());
        JButton closeButton = new JButton("Cerrar");
        closeButton.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(clearButton);
        buttons.add(copyButton);
        buttons.add(insertButton);
        buttons.add(closeButton);

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        content.add(top, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        reload();
        setSize(950, 600);
        setLocationRelativeTo(owner);
    }

    private void reload() {
        all.clear();
        try {
            all.addAll(store.load());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "No se pudo leer el historial: " + e.getMessage(),
                    getTitle(), JOptionPane.ERROR_MESSAGE);
        }
        applyFilter();
    }

    private void applyFilter() {
        String f = filter.getText().trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (Entry e : all) {
            if (f.isEmpty() || e.sql().toLowerCase(Locale.ROOT).contains(f)
                    || e.connection().toLowerCase(Locale.ROOT).contains(f)) {
                visible.add(e);
            }
        }
        model.fireTableDataChanged();
        if (!visible.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        } else {
            preview.setText("");
        }
    }

    private Entry selected() {
        int row = table.getSelectedRow();
        return row < 0 ? null : visible.get(table.convertRowIndexToModel(row));
    }

    private void showSelected() {
        Entry e = selected();
        preview.setText(e == null ? "" : e.sql());
        preview.setCaretPosition(0);
    }

    private void insertSelected() {
        Entry e = selected();
        if (e != null) {
            insert.accept(e.sql());
        }
    }

    private void clearHistory() {
        int answer = JOptionPane.showConfirmDialog(this, "¿Borrar todo el historial?", getTitle(),
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.YES_OPTION) {
            try {
                store.clear();
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, "No se pudo borrar: " + e.getMessage(),
                        getTitle(), JOptionPane.ERROR_MESSAGE);
            }
            reload();
        }
    }

    private final class HistoryModel extends AbstractTableModel {
        private final String[] columns = {"Fecha", "Conexión", "Estado", "ms", "SQL"};

        @Override
        public int getRowCount() {
            return visible.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            Entry e = visible.get(row);
            return switch (column) {
                case 0 -> TIME.format(e.time());
                case 1 -> e.connection();
                case 2 -> e.ok() ? "OK" : "Error";
                case 3 -> e.elapsedMs();
                default -> e.sql().replaceAll("\\s+", " ").strip();
            };
        }
    }
}
