package com.quasera.sqlstudiogrid.ui;

import com.quasera.sqlstudiogrid.connectivity.ConnectionFactory;
import com.quasera.sqlstudiogrid.connectivity.QueryExecutor;
import com.quasera.sqlstudiogrid.core.ConnectionProfile;
import com.quasera.sqlstudiogrid.core.DbType;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/** Diálogo para crear o editar una conexión, con botón "Probar conexión". */
public final class ConnectionDialog extends JDialog {

    private final JTextField name = new JTextField(28);
    private final JComboBox<DbType> type = new JComboBox<>(DbType.values());
    private final JTextField host = new JTextField("localhost");
    private final JTextField port = new JTextField();
    private final JTextField database = new JTextField();
    private final JTextField user = new JTextField();
    private final JPasswordField password = new JPasswordField();
    private final JTextField customUrl = new JTextField();
    private final JLabel urlPreview = new JLabel(" ");
    private final JButton testButton = new JButton("Probar conexión");
    private final JLabel databaseLabel = new JLabel("Base de datos:");

    private final String existingId;
    private ConnectionProfile result;

    private ConnectionDialog(Window owner, ConnectionProfile existing) {
        super(owner, existing == null ? "Nueva conexión" : "Editar conexión", ModalityType.APPLICATION_MODAL);
        this.existingId = existing == null ? null : existing.id();

        if (existing != null) {
            name.setText(existing.name());
            type.setSelectedItem(existing.type());
            host.setText(existing.host());
            port.setText(String.valueOf(existing.port()));
            database.setText(existing.database());
            user.setText(existing.user());
            password.setText(existing.password());
            customUrl.setText(existing.customUrl());
        } else {
            port.setText(String.valueOf(DbType.SQL_SERVER.defaultPort()));
        }

        type.addActionListener(e -> onTypeChanged());
        DocumentListener preview = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updatePreview();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updatePreview();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updatePreview();
            }
        };
        for (JTextField f : new JTextField[]{host, port, database, customUrl}) {
            f.getDocument().addDocumentListener(preview);
        }

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        int row = 0;
        row = addRow(form, row, new JLabel("Nombre:"), name);
        row = addRow(form, row, new JLabel("Motor:"), type);
        row = addRow(form, row, new JLabel("Host:"), host);
        row = addRow(form, row, new JLabel("Puerto:"), port);
        row = addRow(form, row, databaseLabel, database);
        row = addRow(form, row, new JLabel("Usuario:"), user);
        row = addRow(form, row, new JLabel("Contraseña:"), password);
        row = addRow(form, row, new JLabel("URL JDBC (opcional):"), customUrl);
        customUrl.setToolTipText("Si se llena, se usa en lugar de host/puerto/base. Ej.: jdbc:sybase:Tds:host:5000/base");

        urlPreview.setFont(urlPreview.getFont().deriveFont(Font.ITALIC, urlPreview.getFont().getSize2D() - 1f));
        urlPreview.setForeground(UIManager.getColor("Label.disabledForeground"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = row;
        gc.gridwidth = 2;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(6, 4, 4, 4);
        form.add(urlPreview, gc);

        JButton save = new JButton("Guardar");
        JButton cancel = new JButton("Cancelar");
        testButton.addActionListener(e -> test());
        save.addActionListener(e -> save());
        cancel.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(testButton);
        buttons.add(save);
        buttons.add(cancel);

        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(save);
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        updateDatabaseLabel();
        updatePreview();
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /** Muestra el diálogo; devuelve el perfil guardado o vacío si se canceló. */
    public static Optional<ConnectionProfile> show(Window owner, ConnectionProfile existing) {
        ConnectionDialog dialog = new ConnectionDialog(owner, existing);
        dialog.setVisible(true);
        return Optional.ofNullable(dialog.result);
    }

    private static int addRow(JPanel form, int row, JComponent label, JComponent field) {
        GridBagConstraints l = new GridBagConstraints();
        l.gridx = 0;
        l.gridy = row;
        l.anchor = GridBagConstraints.EAST;
        l.insets = new Insets(4, 4, 4, 8);
        form.add(label, l);
        GridBagConstraints f = new GridBagConstraints();
        f.gridx = 1;
        f.gridy = row;
        f.fill = GridBagConstraints.HORIZONTAL;
        f.weightx = 1;
        f.insets = new Insets(4, 0, 4, 4);
        form.add(field, f);
        return row + 1;
    }

    private DbType selectedType() {
        return (DbType) type.getSelectedItem();
    }

    private void onTypeChanged() {
        String current = port.getText().trim();
        boolean isDefault = current.isEmpty();
        for (DbType t : DbType.values()) {
            if (current.equals(String.valueOf(t.defaultPort()))) {
                isDefault = true;
            }
        }
        if (isDefault) {
            port.setText(String.valueOf(selectedType().defaultPort()));
        }
        updateDatabaseLabel();
        updatePreview();
    }

    private void updateDatabaseLabel() {
        databaseLabel.setText(selectedType() == DbType.ORACLE ? "Servicio (SID/servicio):" : "Base de datos:");
    }

    private void updatePreview() {
        try {
            urlPreview.setText("URL: " + build(false).jdbcUrl());
        } catch (IllegalArgumentException e) {
            urlPreview.setText(e.getMessage());
        }
    }

    private ConnectionProfile build(boolean requireName) {
        String n = name.getText().trim();
        if (requireName && n.isEmpty()) {
            throw new IllegalArgumentException("Ingrese un nombre para la conexión.");
        }
        int p;
        String portText = port.getText().trim();
        try {
            p = portText.isEmpty() ? selectedType().defaultPort() : Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("El puerto debe ser un número.");
        }
        if (p <= 0 || p > 65535) {
            throw new IllegalArgumentException("El puerto debe estar entre 1 y 65535.");
        }
        return new ConnectionProfile(existingId, n.isEmpty() ? host.getText().trim() : n, selectedType(),
                host.getText(), p, database.getText(), user.getText(),
                new String(password.getPassword()), customUrl.getText());
    }

    private void save() {
        try {
            result = build(true);
            dispose();
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Conexión", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void test() {
        ConnectionProfile profile;
        try {
            profile = build(false);
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(this, e.getMessage(), "Conexión", JOptionPane.WARNING_MESSAGE);
            return;
        }
        testButton.setEnabled(false);
        testButton.setText("Probando...");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                try (Connection c = ConnectionFactory.open(profile)) {
                    DatabaseMetaData md = c.getMetaData();
                    return md.getDatabaseProductName() + " " + md.getDatabaseProductVersion()
                            + "\nDriver: " + md.getDriverName() + " " + md.getDriverVersion();
                }
            }

            @Override
            protected void done() {
                testButton.setEnabled(true);
                testButton.setText("Probar conexión");
                try {
                    JOptionPane.showMessageDialog(ConnectionDialog.this, "Conexión exitosa.\n\n" + get(),
                            "Probar conexión", JOptionPane.INFORMATION_MESSAGE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String msg = cause instanceof SQLException sqlEx
                            ? QueryExecutor.describe(sqlEx) : String.valueOf(cause);
                    JOptionPane.showMessageDialog(ConnectionDialog.this, "No se pudo conectar:\n\n" + msg,
                            "Probar conexión", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
}
