package com.quasera.sqlstudiogrid.ui.editor;

import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.Rectangle2D;

/** Números de línea para usar como "row header" del JScrollPane del editor. */
public final class LineNumberView extends JComponent {

    private static final int PADDING = 8;
    private final JTextComponent editor;

    public LineNumberView(JTextComponent editor) {
        this.editor = editor;
        setFont(editor.getFont());
        Color fg = UIManager.getColor("Label.disabledForeground");
        setForeground(fg != null ? fg : Color.GRAY);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // estilos: sin cambios de líneas
            }
        });
        editor.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                refresh();
            }
        });
    }

    private void refresh() {
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        int lines = editor.getDocument().getDefaultRootElement().getElementCount();
        int digits = Math.max(3, String.valueOf(lines).length());
        FontMetrics fm = getFontMetrics(getFont());
        int width = fm.charWidth('0') * digits + PADDING * 2;
        return new Dimension(width, Math.max(editor.getHeight(), editor.getPreferredSize().height));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Rectangle clip = g.getClipBounds();
        FontMetrics fm = g.getFontMetrics(getFont());
        g.setColor(getForeground());
        g.setFont(getFont());

        Element root = editor.getDocument().getDefaultRootElement();
        int startOffset = editor.viewToModel2D(new Point(0, clip.y));
        int endOffset = editor.viewToModel2D(new Point(0, clip.y + clip.height));
        int startLine = root.getElementIndex(Math.max(0, startOffset));
        int endLine = root.getElementIndex(Math.max(0, endOffset));
        int width = getWidth();

        for (int line = startLine; line <= endLine; line++) {
            try {
                Rectangle2D r = editor.modelToView2D(root.getElement(line).getStartOffset());
                if (r == null) {
                    continue;
                }
                String label = String.valueOf(line + 1);
                int x = width - PADDING - fm.stringWidth(label);
                int y = (int) r.getY() + fm.getAscent();
                g.drawString(label, x, y);
            } catch (BadLocationException ignored) {
                // línea fuera de rango durante una edición
            }
        }
    }
}
