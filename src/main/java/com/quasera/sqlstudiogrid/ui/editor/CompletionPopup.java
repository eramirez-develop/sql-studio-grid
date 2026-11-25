package com.quasera.sqlstudiogrid.ui.editor;

import com.quasera.sqlstudiogrid.sql.SqlCompletion;
import com.quasera.sqlstudiogrid.sql.SqlCompletion.Candidate;
import com.quasera.sqlstudiogrid.sql.SqlTokenizer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/**
 * Lista emergente de autocompletado para el editor SQL.
 * Se abre con Ctrl+Espacio, al escribir un punto o tras escribir 2 letras.
 * Las sugerencias se calculan en segundo plano (pueden requerir leer metadatos).
 */
public final class CompletionPopup {

    /** Fuente de sugerencias; se llama fuera del hilo de la interfaz. */
    public interface Provider {
        List<Candidate> candidates(String text, int caret) throws Exception;
    }

    private static final int MAX_ITEMS = 200;
    private static final int AUTO_PREFIX_LENGTH = 2;

    private final JTextComponent editor;
    private final Provider provider;
    private final JPopupMenu popup = new JPopupMenu();
    private final DefaultListModel<Candidate> model = new DefaultListModel<>();
    private final JList<Candidate> list = new JList<>(model);
    private final Timer autoTimer;
    private final JScrollPane scroll;

    private List<Candidate> base = List.of();
    private String baseQualifier;
    private int anchor;
    private SwingWorker<List<Candidate>, Void> pending;
    private boolean applying;

    public CompletionPopup(JTextComponent editor, Provider provider) {
        this.editor = editor;
        this.provider = provider;

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFocusable(false);
        list.setCellRenderer(new CandidateRenderer());
        list.setFont(editor.getFont().deriveFont(13f));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    accept();
                }
            }
        });
        scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(380, 220));
        popup.setFocusable(false);
        popup.add(scroll);

        autoTimer = new Timer(300, e -> trigger(false));
        autoTimer.setRepeats(false);

        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK), "complete");
        editor.getActionMap().put("complete", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                trigger(true);
            }
        });

        editor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!popup.isVisible()) {
                    return;
                }
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_UP -> move(-1);
                    case KeyEvent.VK_DOWN -> move(1);
                    case KeyEvent.VK_PAGE_UP -> move(-8);
                    case KeyEvent.VK_PAGE_DOWN -> move(8);
                    case KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> accept();
                    case KeyEvent.VK_ESCAPE -> hide();
                    default -> {
                        return;
                    }
                }
                e.consume();
            }
        });

        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                if (applying) {
                    return;
                }
                if (popup.isVisible()) {
                    SwingUtilities.invokeLater(CompletionPopup.this::refilter);
                    return;
                }
                String inserted = insertedText(e);
                if (".".equals(inserted)) {
                    autoTimer.stop();
                    SwingUtilities.invokeLater(() -> trigger(false));
                } else if (inserted.length() == 1 && SqlTokenizer.isWordPart(inserted.charAt(0))) {
                    autoTimer.restart();
                } else {
                    autoTimer.stop();
                }
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                autoTimer.stop();
                if (popup.isVisible() && !applying) {
                    SwingUtilities.invokeLater(CompletionPopup.this::refilter);
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // estilos
            }
        });

        editor.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                hide();
            }
        });
        editor.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                hide();
            }
        });
    }

    private String insertedText(DocumentEvent e) {
        try {
            return e.getDocument().getText(e.getOffset(), e.getLength());
        } catch (BadLocationException ex) {
            return "";
        }
    }

    /** @param explicit true si el usuario lo pidió (Ctrl+Espacio): se abre aunque no haya prefijo */
    public void trigger(boolean explicit) {
        String text = text();
        int caret = editor.getCaretPosition();
        String qualifier = SqlCompletion.qualifierAt(text, caret);
        String prefix = SqlCompletion.prefixAt(text, caret);
        if (!explicit && qualifier == null && prefix.length() < AUTO_PREFIX_LENGTH) {
            return;
        }
        if (!explicit && insideStringOrComment(text, caret)) {
            return;
        }
        if (pending != null) {
            pending.cancel(true);
        }
        pending = new SwingWorker<>() {
            @Override
            protected List<Candidate> doInBackground() throws Exception {
                return provider.candidates(text, caret);
            }

            @Override
            protected void done() {
                if (isCancelled() || pending != this) {
                    return;
                }
                pending = null;
                try {
                    base = get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    base = List.of();
                }
                baseQualifier = qualifier;
                refilter();
            }
        };
        pending.execute();
    }

    private void refilter() {
        if (!editor.isShowing()) {
            hide();
            return;
        }
        String text = text();
        int caret = editor.getCaretPosition();
        String prefix = SqlCompletion.prefixAt(text, caret);
        String qualifier = SqlCompletion.qualifierAt(text, caret);
        if (!Objects.equals(qualifier, baseQualifier)) {
            hide();
            if (qualifier != null) {
                trigger(true);
            }
            return;
        }
        anchor = caret - prefix.length();
        List<Candidate> filtered = SqlCompletion.filter(base, prefix, MAX_ITEMS);
        if (filtered.isEmpty() || (filtered.size() == 1 && filtered.getFirst().text().equals(prefix))) {
            hide();
            return;
        }
        model.clear();
        filtered.forEach(model::addElement);
        list.setSelectedIndex(0);
        list.ensureIndexIsVisible(0);
        java.awt.Rectangle cell = list.getCellBounds(0, 0);
        int rowHeight = cell == null ? 20 : cell.height;
        scroll.setPreferredSize(new Dimension(380, Math.min(filtered.size(), 10) * rowHeight + 4));
        popup.pack();
        try {
            Rectangle2D r = editor.modelToView2D(anchor);
            if (r != null) {
                popup.show(editor, (int) r.getX(), (int) (r.getY() + r.getHeight()));
                editor.requestFocusInWindow();
            }
        } catch (BadLocationException e) {
            hide();
        }
    }

    private void move(int delta) {
        int size = model.getSize();
        if (size == 0) {
            return;
        }
        int index = Math.max(0, Math.min(size - 1, list.getSelectedIndex() + delta));
        list.setSelectedIndex(index);
        list.ensureIndexIsVisible(index);
    }

    private void accept() {
        Candidate c = list.getSelectedValue();
        hide();
        if (c == null) {
            return;
        }
        int caret = editor.getCaretPosition();
        Document doc = editor.getDocument();
        applying = true;
        try {
            if (caret > anchor) {
                doc.remove(anchor, caret - anchor);
            }
            doc.insertString(anchor, c.text(), null);
        } catch (BadLocationException ignored) {
            // el texto cambió mientras tanto
        } finally {
            applying = false;
        }
    }

    public void hide() {
        if (popup.isVisible()) {
            popup.setVisible(false);
        }
    }

    private String text() {
        try {
            Document doc = editor.getDocument();
            return doc.getText(0, doc.getLength());
        } catch (BadLocationException e) {
            return "";
        }
    }

    /** Evita abrir la lista automáticamente dentro de una cadena o comentario de la línea actual. */
    private static boolean insideStringOrComment(String text, int caret) {
        int lineStart = text.lastIndexOf('\n', Math.max(0, caret - 1)) + 1;
        boolean inString = false;
        for (int i = lineStart; i < caret; i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                inString = !inString;
            } else if (!inString && c == '-' && i + 1 < caret && text.charAt(i + 1) == '-') {
                return true;
            }
        }
        return inString;
    }

    /** Muestra el texto y, en gris, el tipo de sugerencia. */
    private static final class CandidateRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof Candidate c) {
                String kind = switch (c.kind()) {
                    case KEYWORD -> "palabra clave";
                    case TABLE -> "tabla";
                    case VIEW -> "vista";
                    case COLUMN -> "columna";
                    case SCHEMA -> "esquema";
                };
                String detail = c.detail() == null || c.detail().isBlank() ? kind : kind + " · " + c.detail();
                Color gray = UIManager.getColor("Label.disabledForeground");
                String hex = gray == null ? "#888888" : String.format("#%06x", gray.getRGB() & 0xFFFFFF);
                label.setText("<html>" + escape(c.text()) + "&nbsp;&nbsp;<font color='" + hex + "'>"
                        + escape(detail) + "</font></html>");
            }
            return label;
        }

        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
