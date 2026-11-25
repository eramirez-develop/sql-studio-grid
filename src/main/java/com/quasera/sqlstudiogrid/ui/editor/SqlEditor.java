package com.quasera.sqlstudiogrid.ui.editor;

import javax.swing.AbstractAction;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Locale;
import java.util.Set;

/** Editor SQL con resaltado de sintaxis, deshacer/rehacer y sin ajuste de línea. */
public final class SqlEditor extends JTextPane {

    private static final int MAX_HIGHLIGHT_LENGTH = 400_000;

    private static final Set<String> KEYWORDS = Set.of(
            "ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "BEGIN", "BETWEEN", "BREAK", "BY", "CASE",
            "CAST", "CATCH", "CHECK", "CLOSE", "COLUMN", "COMMIT", "CONSTRAINT", "CONTINUE", "CONVERT",
            "CREATE", "CROSS", "CURSOR", "DATABASE", "DEALLOCATE", "DECLARE", "DEFAULT", "DELETE", "DESC",
            "DISTINCT", "DROP", "ELSE", "ELSIF", "END", "EXCEPT", "EXCEPTION", "EXEC", "EXECUTE", "EXISTS",
            "FETCH", "FIRST", "FOR", "FOREIGN", "FROM", "FULL", "FUNCTION", "GO", "GRANT", "GROUP",
            "HAVING", "IDENTITY", "IF", "IN", "INDEX", "INNER", "INSERT", "INTERSECT", "INTO", "IS",
            "JOIN", "KEY", "LEFT", "LIKE", "LIMIT", "LOOP", "MERGE", "NEXT", "NOCOUNT", "NOT", "NULL",
            "OF", "OFFSET", "ON", "ONLY", "OPEN", "OR", "ORDER", "OUTER", "OUTPUT", "OVER", "PACKAGE",
            "PARTITION", "PRIMARY", "PRINT", "PROC", "PROCEDURE", "RAISERROR", "REFERENCES", "REPLACE",
            "RETURN", "RETURNS", "REVOKE", "RIGHT", "ROLLBACK", "ROWNUM", "ROWS", "SELECT", "SET", "TABLE",
            "THEN", "THROW", "TOP", "TRAN", "TRANSACTION", "TRIGGER", "TRUNCATE", "TRY", "UNION",
            "UNIQUE", "UPDATE", "USE", "USING", "VALUES", "VIEW", "WHEN", "WHERE", "WHILE", "WITH",
            // tipos comunes
            "BIGINT", "BIT", "CHAR", "DATE", "DATETIME", "DECIMAL", "FLOAT", "INT", "INTEGER", "MONEY",
            "NCHAR", "NUMBER", "NUMERIC", "NVARCHAR", "SMALLINT", "TEXT", "TIMESTAMP", "TINYINT",
            "VARCHAR", "VARCHAR2", "CLOB", "BLOB");

    private final UndoManager undo = new UndoManager();
    private final Timer highlightTimer;
    private final SimpleAttributeSet normal = new SimpleAttributeSet();
    private final SimpleAttributeSet keyword = new SimpleAttributeSet();
    private final SimpleAttributeSet string = new SimpleAttributeSet();
    private final SimpleAttributeSet comment = new SimpleAttributeSet();
    private final SimpleAttributeSet number = new SimpleAttributeSet();

    public SqlEditor() {
        setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        configureColors();

        highlightTimer = new Timer(250, e -> highlight());
        highlightTimer.setRepeats(false);

        getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                highlightTimer.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                highlightTimer.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // cambios de estilo: no se re-resalta
            }
        });

        // El resaltado cambia atributos; esos cambios no deben entrar al deshacer.
        getDocument().addUndoableEditListener(e -> {
            if (e.getEdit() instanceof AbstractDocument.DefaultDocumentEvent de
                    && de.getType() == DocumentEvent.EventType.CHANGE) {
                return;
            }
            undo.addEdit(e.getEdit());
        });

        bind(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo", () -> {
            try {
                if (undo.canUndo()) {
                    undo.undo();
                }
            } catch (CannotUndoException ignored) {
                // nada que deshacer
            }
        });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo", () -> {
            try {
                if (undo.canRedo()) {
                    undo.redo();
                }
            } catch (CannotRedoException ignored) {
                // nada que rehacer
            }
        });
    }

    /** Reemplaza el texto, lo resalta y limpia el historial de deshacer. */
    public void setSql(String sql) {
        setText(sql == null ? "" : sql);
        highlight();
        undo.discardAllEdits();
        setCaretPosition(0);
    }

    private void bind(KeyStroke key, String name, Runnable action) {
        getInputMap().put(key, name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void configureColors() {
        Color bg = UIManager.getColor("TextPane.background");
        if (bg == null) {
            bg = Color.WHITE;
        }
        boolean dark = (bg.getRed() * 299 + bg.getGreen() * 587 + bg.getBlue() * 114) / 1000 < 128;
        Color fg = UIManager.getColor("TextPane.foreground");
        StyleConstants.setForeground(normal, fg != null ? fg : (dark ? Color.LIGHT_GRAY : Color.BLACK));
        StyleConstants.setForeground(keyword, dark ? new Color(0xCC7832) : new Color(0x0033B3));
        StyleConstants.setBold(keyword, true);
        StyleConstants.setForeground(string, dark ? new Color(0x6A8759) : new Color(0x067D17));
        StyleConstants.setForeground(comment, dark ? new Color(0x808080) : new Color(0x8C8C8C));
        StyleConstants.setItalic(comment, true);
        StyleConstants.setForeground(number, dark ? new Color(0x6897BB) : new Color(0x1750EB));
    }

    private void highlight() {
        StyledDocument doc = getStyledDocument();
        int length = doc.getLength();
        if (length > MAX_HIGHLIGHT_LENGTH) {
            return;
        }
        String text;
        try {
            text = doc.getText(0, length);
        } catch (BadLocationException e) {
            return;
        }
        doc.setCharacterAttributes(0, length, normal, true);
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            char next = i + 1 < length ? text.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                int end = text.indexOf('\n', i);
                end = end < 0 ? length : end;
                doc.setCharacterAttributes(i, end - i, comment, true);
                i = end;
            } else if (c == '/' && next == '*') {
                int end = text.indexOf("*/", i + 2);
                end = end < 0 ? length : end + 2;
                doc.setCharacterAttributes(i, end - i, comment, true);
                i = end;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < length) {
                    if (text.charAt(j) == '\'') {
                        if (j + 1 < length && text.charAt(j + 1) == '\'') {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                int end = Math.min(j + 1, length);
                doc.setCharacterAttributes(i, end - i, string, true);
                i = end;
            } else if (Character.isDigit(c) && (i == 0 || !isIdentifierPart(text.charAt(i - 1)))) {
                int j = i;
                while (j < length && (Character.isDigit(text.charAt(j)) || text.charAt(j) == '.')) {
                    j++;
                }
                doc.setCharacterAttributes(i, j - i, number, true);
                i = j;
            } else if (Character.isLetter(c) || c == '_' || c == '@' || c == '#') {
                int j = i;
                while (j < length && isIdentifierPart(text.charAt(j))) {
                    j++;
                }
                String word = text.substring(i, j).toUpperCase(Locale.ROOT);
                if (KEYWORDS.contains(word)) {
                    doc.setCharacterAttributes(i, j - i, keyword, true);
                }
                i = j;
            } else {
                i++;
            }
        }
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '@' || c == '#' || c == '$';
    }

    /** Sin ajuste de línea: el editor se ensancha y aparece scroll horizontal. */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        Component parent = getParent();
        return parent == null || getUI().getPreferredSize(this).width <= parent.getSize().width;
    }
}
