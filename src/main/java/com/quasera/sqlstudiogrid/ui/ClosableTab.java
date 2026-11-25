package com.quasera.sqlstudiogrid.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.awt.Insets;

/** Encabezado de pestaña con botón × para cerrarla. */
final class ClosableTab extends JPanel {

    private final JLabel label;

    ClosableTab(String title, Runnable onClose) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 0));
        setOpaque(false);
        label = new JLabel(title);
        JButton close = new JButton("×");
        close.setMargin(new Insets(0, 4, 0, 4));
        close.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 2));
        close.setContentAreaFilled(false);
        close.setFocusable(false);
        close.setToolTipText("Cerrar (Ctrl+W)");
        close.addActionListener(e -> onClose.run());
        add(label);
        add(close);
    }

    void setTitle(String title) {
        label.setText(title);
    }
}
