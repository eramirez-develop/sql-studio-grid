package com.quasera.sqlstudiogrid.ui.grid;

import com.quasera.sqlstudiogrid.connectivity.QueryResult;

import javax.swing.BorderFactory;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;

/** Pestañas de resultados (una por ResultSet) más la pestaña de mensajes. */
public final class ResultPanel extends JPanel {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int SAMPLE_ROWS_FOR_WIDTH = 100;
    private static final int MAX_COLUMN_WIDTH = 400;

    private final JTabbedPane tabs = new JTabbedPane(JTabbedPane.BOTTOM);
    private final JTextArea messages = new JTextArea();
    private int resultCount;

    public ResultPanel() {
        super(new BorderLayout());
        messages.setEditable(false);
        messages.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        tabs.addTab("Mensajes", new JScrollPane(messages));
        add(tabs, BorderLayout.CENTER);
    }

    public void clear() {
        while (tabs.getTabCount() > 1) {
            tabs.removeTabAt(0);
        }
        messages.setText("");
        resultCount = 0;
    }

    public void add(QueryResult result) {
        log(result);
        switch (result.kind()) {
            case RESULT_SET -> {
                resultCount++;
                int index = tabs.getTabCount() - 1;
                tabs.insertTab("Resultado " + resultCount, null, buildGrid(result), firstLine(result.sql()), index);
                if (resultCount == 1) {
                    tabs.setSelectedIndex(0);
                }
            }
            case ERROR -> tabs.setSelectedIndex(tabs.getTabCount() - 1);
            case UPDATE, MESSAGE -> {
                if (resultCount == 0) {
                    tabs.setSelectedIndex(tabs.getTabCount() - 1);
                }
            }
        }
    }

    public void log(String text) {
        messages.append("[" + LocalTime.now().format(TIME) + "] " + text + "\n");
    }

    private void log(QueryResult result) {
        messages.append("[" + LocalTime.now().format(TIME) + "] " + firstLine(result.sql()) + "\n");
        for (String line : result.summary().split("\n")) {
            messages.append("    " + line + "\n");
        }
        messages.setCaretPosition(messages.getDocument().getLength());
    }

    /** Tabla actualmente visible (o null si se ve la pestaña de mensajes). */
    public JTable selectedTable() {
        Component c = tabs.getSelectedComponent();
        if (c instanceof JPanel panel && panel.getClientProperty("table") instanceof JTable table) {
            return table;
        }
        return null;
    }

    private JPanel buildGrid(QueryResult result) {
        ResultTableModel model = new ResultTableModel(result.columns(), result.columnTypes(), result.rows());
        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setCellSelectionEnabled(true);
        table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Object.class, new ValueRenderer());

        TableRowSorter<ResultTableModel> sorter = new TableRowSorter<>(model);
        for (int c = 0; c < model.getColumnCount(); c++) {
            sorter.setComparator(c, VALUE_COMPARATOR);
        }
        table.setRowSorter(sorter);
        sizeColumns(table);
        installPopup(table);

        JScrollPane scroll = new JScrollPane(table);
        JLabel footer = new JLabel(" " + result.rows().size() + " fila(s) · " + result.elapsedMs() + " ms"
                + (result.truncated() ? " · limitado: aumente \"Máx. filas\" para ver más" : ""));
        footer.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));

        JPanel panel = new JPanel(new BorderLayout());
        panel.putClientProperty("table", table);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    private void sizeColumns(JTable table) {
        FontMetrics fm = table.getFontMetrics(table.getFont());
        ResultTableModel model = (ResultTableModel) table.getModel();
        int rows = Math.min(model.getRowCount(), SAMPLE_ROWS_FOR_WIDTH);
        for (int c = 0; c < model.getColumnCount(); c++) {
            int width = fm.stringWidth(model.getColumnName(c)) + 24;
            for (int r = 0; r < rows; r++) {
                Object value = model.getValueAt(r, c);
                width = Math.max(width, fm.stringWidth(value == null ? "<null>" : String.valueOf(value)) + 16);
            }
            TableColumn column = table.getColumnModel().getColumn(c);
            column.setPreferredWidth(Math.min(width, MAX_COLUMN_WIDTH));
            column.setHeaderValue(model.getColumnName(c));
        }
        // Tipo de dato como tooltip del encabezado
        table.getTableHeader().setToolTipText(null);
        table.getTableHeader().addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int viewCol = table.getTableHeader().columnAtPoint(e.getPoint());
                if (viewCol >= 0) {
                    int col = table.convertColumnIndexToModel(viewCol);
                    table.getTableHeader().setToolTipText(model.getColumnName(col) + " : " + model.getColumnType(col));
                }
            }
        });
    }

    private void installPopup(JTable table) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyCell = new JMenuItem("Copiar celda");
        copyCell.addActionListener(e -> {
            int r = table.getSelectedRow();
            int c = table.getSelectedColumn();
            if (r >= 0 && c >= 0) {
                Object v = table.getValueAt(r, c);
                copy(v == null ? "" : String.valueOf(v));
            }
        });
        JMenuItem copyRows = new JMenuItem("Copiar filas seleccionadas (con encabezado)");
        copyRows.addActionListener(e -> copy(selectedRowsAsText(table)));
        JMenuItem export = new JMenuItem("Exportar a CSV...");
        export.addActionListener(e -> exportCsv(table));
        menu.add(copyCell);
        menu.add(copyRows);
        menu.addSeparator();
        menu.add(export);

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShow(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShow(e);
            }

            private void maybeShow(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int r = table.rowAtPoint(e.getPoint());
                    int c = table.columnAtPoint(e.getPoint());
                    if (r >= 0 && c >= 0 && !table.isCellSelected(r, c)) {
                        table.changeSelection(r, c, false, false);
                    }
                    menu.show(table, e.getX(), e.getY());
                }
            }
        });
    }

    private static String selectedRowsAsText(JTable table) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < table.getColumnCount(); c++) {
            sb.append(c > 0 ? "\t" : "").append(table.getColumnName(c));
        }
        sb.append('\n');
        for (int r : table.getSelectedRows()) {
            for (int c = 0; c < table.getColumnCount(); c++) {
                Object v = table.getValueAt(r, c);
                sb.append(c > 0 ? "\t" : "").append(v == null ? "" : v);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public void exportSelected() {
        JTable table = selectedTable();
        if (table == null) {
            JOptionPane.showMessageDialog(this, "Seleccione primero una pestaña de resultados.",
                    "Exportar", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        exportCsv(table);
    }

    private void exportCsv(JTable table) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Archivo CSV (*.csv)", "csv"));
        chooser.setSelectedFile(new File("resultado.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".csv")) {
            file = new File(file.getParentFile(), file.getName() + ".csv");
        }
        try {
            CsvExporter.export(table.getModel(), file.toPath(), ',');
            log("Exportado a " + file.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "No se pudo exportar: " + ex.getMessage(),
                    "Exportar", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private static String firstLine(String sql) {
        if (sql == null) {
            return "";
        }
        String s = sql.strip();
        int nl = s.indexOf('\n');
        String line = nl < 0 ? s : s.substring(0, nl).strip() + " ...";
        return line.length() > 120 ? line.substring(0, 117) + "..." : line;
    }

    /** Compara números y fechas por su valor; el resto como texto. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final Comparator<Object> VALUE_COMPARATOR = (a, b) -> {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        if (a instanceof Comparable ca && b != null && a.getClass() == b.getClass()) {
            return ca.compareTo(b);
        }
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    };

    /** Muestra NULL en gris y alinea números a la derecha. */
    private static final class ValueRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (value == null) {
                setText("<null>");
                setHorizontalAlignment(SwingConstants.LEFT);
                if (!isSelected) {
                    Color gray = UIManager.getColor("Label.disabledForeground");
                    setForeground(gray != null ? gray : Color.GRAY);
                }
            } else {
                setHorizontalAlignment(value instanceof Number ? SwingConstants.RIGHT : SwingConstants.LEFT);
                if (!isSelected) {
                    setForeground(table.getForeground());
                }
            }
            return c;
        }
    }
}
