package com.quasera.sqlstudiogrid.ui;

import com.quasera.sqlstudiogrid.connectivity.ConnectionFactory;
import com.quasera.sqlstudiogrid.connectivity.QueryExecutor;
import com.quasera.sqlstudiogrid.core.ConnectionProfile;
import com.quasera.sqlstudiogrid.metadata.DdlService;
import com.quasera.sqlstudiogrid.metadata.MetadataService;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Árbol de conexiones → esquemas → tablas / vistas / procedimientos → columnas.
 * Carga perezosa: cada nivel se lee al expandirlo. Usa una conexión de metadatos
 * por perfil, separada de las consolas.
 */
public final class ExplorerPanel extends JPanel {

    public interface Actions {
        void openConsole(ConnectionProfile profile, String sql, boolean run);

        void newConnection();

        void editConnection(ConnectionProfile profile);

        void deleteConnection(ConnectionProfile profile);
    }

    enum Kind { ROOT, CONNECTION, SCHEMA, TABLES, VIEWS, PROCEDURES, TABLE, VIEW, PROCEDURE, COLUMN, LOADING, INFO }

    record NodeData(Kind kind, String label, ConnectionProfile profile, String schema, String name) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final int PREVIEW_ROWS = 200;

    private final Actions actions;
    private final DefaultMutableTreeNode root =
            new DefaultMutableTreeNode(new NodeData(Kind.ROOT, "Conexiones", null, null, null));
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final Map<String, Connection> metaConnections = new ConcurrentHashMap<>();

    public ExplorerPanel(Actions actions) {
        super(new BorderLayout());
        this.actions = actions;

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setToggleClickCount(0); // el doble clic se maneja abajo
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new NodeRenderer());
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                if (needsLoad(node)) {
                    load(node);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
                // nada
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && e.getButton() == MouseEvent.BUTTON1) {
                    onDoubleClick(e);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybePopup(e);
            }
        });

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        JButton add = new JButton("+ Conexión");
        add.setToolTipText("Nueva conexión");
        add.addActionListener(e -> actions.newConnection());
        JButton refresh = new JButton("⟳");
        refresh.setToolTipText("Refrescar el nodo seleccionado");
        refresh.addActionListener(e -> selectedNode().ifPresent(this::refresh));
        bar.add(add);
        bar.add(refresh);

        add(bar, BorderLayout.NORTH);
        add(new JScrollPane(tree), BorderLayout.CENTER);
    }

    public void setProfiles(List<ConnectionProfile> profiles) {
        root.removeAllChildren();
        for (ConnectionProfile p : profiles) {
            DefaultMutableTreeNode node = node(Kind.CONNECTION, p.name(), p, null, null);
            node.add(loadingNode());
            root.add(node);
        }
        model.reload();
    }

    public Optional<ConnectionProfile> selectedProfile() {
        return selectedNode().map(n -> data(n).profile());
    }

    /** Cierra la conexión de metadatos de un perfil y colapsa su nodo. */
    public void disconnect(ConnectionProfile profile) {
        Connection c = metaConnections.remove(profile.id());
        if (c != null) {
            closeQuietly(c);
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) root.getChildAt(i);
            if (data(n).profile() != null && data(n).profile().id().equals(profile.id())) {
                resetChildren(n);
            }
        }
    }

    public void closeAll() {
        metaConnections.values().forEach(ExplorerPanel::closeQuietly);
        metaConnections.clear();
    }

    // ---------------------------------------------------------------- carga

    private boolean needsLoad(DefaultMutableTreeNode node) {
        return node.getChildCount() == 1
                && data((DefaultMutableTreeNode) node.getChildAt(0)).kind() == Kind.LOADING;
    }

    private void load(DefaultMutableTreeNode node) {
        NodeData d = data(node);
        new SwingWorker<List<DefaultMutableTreeNode>, Void>() {
            @Override
            protected List<DefaultMutableTreeNode> doInBackground() throws Exception {
                Connection c = metaConnection(d.profile());
                synchronized (c) {
                    return children(new MetadataService(c, d.profile().type()), d);
                }
            }

            @Override
            protected void done() {
                node.removeAllChildren();
                try {
                    List<DefaultMutableTreeNode> children = get();
                    if (children.isEmpty()) {
                        node.add(node(Kind.INFO, "(vacío)", d.profile(), d.schema(), null));
                    }
                    children.forEach(node::add);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String msg = cause instanceof SQLException sqlEx
                            ? QueryExecutor.describe(sqlEx) : String.valueOf(cause);
                    node.add(node(Kind.INFO, "Error: " + msg.lines().findFirst().orElse(msg),
                            d.profile(), d.schema(), null));
                    if (d.kind() == Kind.CONNECTION) {
                        Connection broken = metaConnections.remove(d.profile().id());
                        if (broken != null) {
                            closeQuietly(broken);
                        }
                    }
                }
                model.nodeStructureChanged(node);
                tree.expandPath(new TreePath(node.getPath()));
            }
        }.execute();
    }

    private List<DefaultMutableTreeNode> children(MetadataService meta, NodeData d) throws SQLException {
        List<DefaultMutableTreeNode> out = new ArrayList<>();
        ConnectionProfile p = d.profile();
        switch (d.kind()) {
            case CONNECTION -> {
                List<String> schemas = meta.schemas();
                if (schemas.isEmpty()) {
                    out.add(lazy(node(Kind.SCHEMA, "(por defecto)", p, "", null)));
                }
                for (String s : schemas) {
                    out.add(lazy(node(Kind.SCHEMA, s, p, s, null)));
                }
            }
            case SCHEMA -> {
                out.add(lazy(node(Kind.TABLES, "Tablas", p, d.schema(), null)));
                out.add(lazy(node(Kind.VIEWS, "Vistas", p, d.schema(), null)));
                out.add(lazy(node(Kind.PROCEDURES, "Procedimientos", p, d.schema(), null)));
            }
            case TABLES -> meta.tables(d.schema()).forEach(t -> out.add(lazy(node(Kind.TABLE, t, p, d.schema(), t))));
            case VIEWS -> meta.views(d.schema()).forEach(v -> out.add(lazy(node(Kind.VIEW, v, p, d.schema(), v))));
            case PROCEDURES -> meta.procedures(d.schema())
                    .forEach(pr -> out.add(node(Kind.PROCEDURE, pr, p, d.schema(), pr)));
            case TABLE, VIEW -> meta.columns(d.schema(), d.name())
                    .forEach(col -> out.add(node(Kind.COLUMN, col, p, d.schema(), d.name())));
            default -> {
                // sin hijos
            }
        }
        return out;
    }

    private Connection metaConnection(ConnectionProfile profile) throws SQLException {
        synchronized (metaConnections) {
            Connection c = metaConnections.get(profile.id());
            if (c != null) {
                try {
                    if (!c.isClosed()) {
                        return c;
                    }
                } catch (SQLException ignored) {
                    // se reabre abajo
                }
            }
            c = ConnectionFactory.open(profile);
            metaConnections.put(profile.id(), c);
            return c;
        }
    }

    private void refresh(DefaultMutableTreeNode node) {
        NodeData d = data(node);
        if (d.kind() == Kind.PROCEDURE || d.kind() == Kind.COLUMN || d.kind() == Kind.INFO) {
            DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
            if (parent != null && parent != root) {
                refresh(parent);
            }
            return;
        }
        TreePath path = new TreePath(node.getPath());
        resetChildren(node);
        tree.expandPath(path); // dispara la carga
    }

    private void resetChildren(DefaultMutableTreeNode node) {
        TreePath path = new TreePath(node.getPath());
        tree.collapsePath(path);
        node.removeAllChildren();
        node.add(loadingNode());
        model.nodeStructureChanged(node);
    }

    // ---------------------------------------------------------------- interacción

    private void onDoubleClick(MouseEvent e) {
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        if (path == null) {
            return;
        }
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        NodeData d = data(node);
        if (d.kind() == Kind.TABLE || d.kind() == Kind.VIEW) {
            preview(d);
        } else if (tree.isExpanded(path)) {
            tree.collapsePath(path);
        } else {
            tree.expandPath(path);
        }
    }

    private void preview(NodeData d) {
        actions.openConsole(d.profile(), d.profile().type().previewSql(d.schema(), d.name(), PREVIEW_ROWS), true);
    }

    private void maybePopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        TreePath path = tree.getPathForLocation(e.getX(), e.getY());
        if (path == null) {
            return;
        }
        tree.setSelectionPath(path);
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        NodeData d = data(node);
        if (d.profile() == null) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        switch (d.kind()) {
            case TABLE, VIEW -> {
                menu.add(item("Ver primeros " + PREVIEW_ROWS + " registros", () -> preview(d)));
                menu.add(item("Nueva consola con SELECT", () -> actions.openConsole(d.profile(),
                        d.profile().type().previewSql(d.schema(), d.name(), PREVIEW_ROWS), false)));
                menu.add(item("Copiar nombre calificado", () ->
                        copy(d.profile().type().qualified(d.schema(), d.name()))));
                menu.add(item("Ver DDL", () -> showDdl(d)));
                menu.addSeparator();
            }
            case PROCEDURE -> {
                menu.add(item("Ver código (DDL)", () -> showDdl(d)));
                menu.add(item("Copiar nombre", () -> copy(d.name())));
                menu.addSeparator();
            }
            default -> {
                // menú común
            }
        }
        menu.add(item("Nueva consola", () -> actions.openConsole(d.profile(), "", false)));
        menu.add(item("Refrescar", () -> refresh(node)));
        if (d.kind() == Kind.CONNECTION) {
            menu.addSeparator();
            menu.add(item("Desconectar", () -> disconnect(d.profile())));
            menu.add(item("Editar conexión...", () -> actions.editConnection(d.profile())));
            menu.add(item("Eliminar conexión", () -> actions.deleteConnection(d.profile())));
        }
        menu.show(tree, e.getX(), e.getY());
    }

    /** Lee el DDL del objeto en segundo plano y lo abre en una consola nueva. */
    private void showDdl(NodeData d) {
        DdlService.ObjectKind kind = switch (d.kind()) {
            case VIEW -> DdlService.ObjectKind.VIEW;
            case PROCEDURE -> DdlService.ObjectKind.PROCEDURE;
            default -> DdlService.ObjectKind.TABLE;
        };
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                Connection c = metaConnection(d.profile());
                synchronized (c) {
                    return new DdlService(c, d.profile().type()).ddl(kind, d.schema(), d.name());
                }
            }

            @Override
            protected void done() {
                setCursor(java.awt.Cursor.getDefaultCursor());
                try {
                    actions.openConsole(d.profile(), get(), false);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String msg = cause instanceof SQLException sqlEx
                            ? QueryExecutor.describe(sqlEx) : String.valueOf(cause);
                    JOptionPane.showMessageDialog(ExplorerPanel.this, "No se pudo obtener el DDL:\n\n" + msg,
                            "Ver DDL", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static JMenuItem item(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }

    private static void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    // ---------------------------------------------------------------- utilidades

    private Optional<DefaultMutableTreeNode> selectedNode() {
        TreePath path = tree.getSelectionPath();
        return path == null ? Optional.empty() : Optional.of((DefaultMutableTreeNode) path.getLastPathComponent());
    }

    private static NodeData data(DefaultMutableTreeNode node) {
        return (NodeData) node.getUserObject();
    }

    private static DefaultMutableTreeNode node(Kind kind, String label, ConnectionProfile p, String schema, String name) {
        boolean leaf = kind == Kind.PROCEDURE || kind == Kind.COLUMN || kind == Kind.INFO || kind == Kind.LOADING;
        return new DefaultMutableTreeNode(new NodeData(kind, label, p, schema, name), !leaf);
    }

    private static DefaultMutableTreeNode lazy(DefaultMutableTreeNode node) {
        node.add(loadingNode());
        return node;
    }

    private static DefaultMutableTreeNode loadingNode() {
        return node(Kind.LOADING, "Cargando...", null, null, null);
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // ya cerrada
        }
    }

    /** Iconos del look and feel según el tipo de nodo. */
    private static final class NodeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof NodeData d) {
                Icon icon = switch (d.kind()) {
                    case CONNECTION -> UIManager.getIcon("FileView.hardDriveIcon");
                    case SCHEMA, TABLES, VIEWS, PROCEDURES -> expanded
                            ? UIManager.getIcon("Tree.openIcon") : UIManager.getIcon("Tree.closedIcon");
                    case TABLE, VIEW, PROCEDURE -> UIManager.getIcon("FileView.fileIcon");
                    default -> null;
                };
                setIcon(icon);
            }
            return this;
        }
    }
}
