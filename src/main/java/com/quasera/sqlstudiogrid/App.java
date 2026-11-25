package com.quasera.sqlstudiogrid;

import com.quasera.sqlstudiogrid.core.AppPaths;
import com.quasera.sqlstudiogrid.core.ConnectionProfile;
import com.quasera.sqlstudiogrid.core.CredentialCipher;
import com.quasera.sqlstudiogrid.core.ProfileStore;
import com.quasera.sqlstudiogrid.ui.MainFrame;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.io.IOException;
import java.util.List;

/** Punto de entrada de SQL Studio Grid. */
public final class App {

    private App() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(App::start);
    }

    private static void start() {
        installLookAndFeel();
        try {
            ProfileStore store = new ProfileStore(AppPaths.connectionsFile(), new CredentialCipher(AppPaths.keyFile()));
            List<ConnectionProfile> profiles = store.load();
            new MainFrame(store, profiles).setVisible(true);
        } catch (IOException | RuntimeException e) {
            JOptionPane.showMessageDialog(null, "No se pudo iniciar: " + e.getMessage(),
                    MainFrame.APP_NAME, JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }

    /**
     * Tema oscuro FlatLaf (estilo IDE). Se carga por reflexión para que la app
     * siga funcionando con el look and feel del sistema si FlatLaf no está presente.
     */
    private static void installLookAndFeel() {
        try {
            Class.forName("com.formdev.flatlaf.FlatDarkLaf").getMethod("setup").invoke(null);
            UIManager.put("Component.arrowType", "chevron");
            UIManager.put("TabbedPane.showTabSeparators", true);
        } catch (ReflectiveOperationException e) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // se queda el look and feel por defecto
            }
        }
    }
}
