package com.mydog.ui;

import com.mydog.connection.ConnectionProfile;
import com.mydog.core.AppContext;
import com.mydog.i18n.I18n;
import com.mydog.metadata.MySqlMetadataReader;
import com.mydog.metadata.model.ColumnDef;
import com.mydog.metadata.model.RoutineDef;
import com.mydog.metadata.model.TableDef;
import com.mydog.metadata.model.TriggerDef;
import com.mydog.metadata.model.ViewDef;
import com.mydog.ui.sync.SyncWizard;
import com.mydog.util.SqlNames;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Left-hand object browser: schema -> category -> object -> (table columns).
 *
 * <p>Every level loads on a background thread with its own short-lived JDBC connection,
 * because a single MySQL connection is not thread-safe and tree expansions can overlap.
 * The FX thread is never blocked on I/O.
 */
public final class ObjectTreeController {

    /** Payload types carried by tree items. */
    public record SchemaNode(String name) {}
    public record CategoryNode(String schema, String category) {}   // tables|views|routines|triggers
    public record TableNode(TableDef table) {}
    public record ViewNode(ViewDef view) {}
    public record RoutineNode(RoutineDef routine) {}
    public record TriggerNode(TriggerDef trigger) {}
    public record ColumnNode(ColumnDef column) {}

    private static final List<String> CATEGORIES = List.of("tables", "views", "routines", "triggers");

    private final AppContext ctx;
    private final Workspace workspace;
    private final TreeView<Object> tree;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "metadata-loader");
        t.setDaemon(true);
        return t;
    });

    public ObjectTreeController(AppContext ctx, Workspace workspace) {
        this.ctx = ctx;
        this.workspace = workspace;
        this.tree = new TreeView<>();
        this.tree.setRoot(new TreeItem<>(I18n.tr("tree.noConnection")));
        this.tree.setCellFactory(v -> new TreeCell<>() {
            @Override
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : label(item));
            }
        });
        installContextMenu();
        tree.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now != null && now.getValue() instanceof TableNode tn) {
                ctx.setActiveTable(tn.table());
            }
        });
    }

    public TreeView<Object> view() {
        return tree;
    }

    /**
     * The database the selection belongs to, or null when nothing schema-shaped is selected.
     *
     * <p>Used to preselect the source side of the sync wizard from wherever the user is in the tree.
     */
    public String focusedSchema() {
        Object node = tree.getSelectionModel().getSelectedItem() == null ? null
                : tree.getSelectionModel().getSelectedItem().getValue();
        return switch (node) {
            case SchemaNode sn -> sn.name();
            case CategoryNode cn -> cn.schema();
            case TableNode tn -> tn.table().schema();
            case ViewNode vn -> vn.view().schema();
            case RoutineNode rn -> rn.routine().schema();
            case TriggerNode tn -> tn.trigger().schema();
            case null, default -> null;
        };
    }

    /** Rebuild the schema level for the given profile (called when a connection is picked). */
    public void showConnection(ConnectionProfile profile) {
        if (profile == null) {
            tree.setRoot(new TreeItem<>(I18n.tr("tree.noConnection")));
            return;
        }
        TreeItem<Object> root = new TreeItem<>(profile.displayLabel());
        root.getChildren().add(loading());
        // the listener must be registered before the initial expansion, or the
        // first expand event is missed and the tree sticks on "Loading..."
        root.expandedProperty().addListener((o, was, open) -> {
            if (open && isLoadingOnly(root)) {
                loadSchemas(profile, root);
            }
        });
        tree.setRoot(root);
        root.setExpanded(true);
    }

    private void loadSchemas(ConnectionProfile profile, TreeItem<Object> root) {
        load(() -> {
            try (Connection c = ctx.openConnection(profile)) {
                return new MySqlMetadataReader(c).listSchemas().stream()
                        .map(s -> (Object) new SchemaNode(s.name())).toList();
            }
        }, root, schemas -> root.getChildren().setAll(
                schemas.stream().map(this::schemaItem).toList()));
    }

    // ------------------------------------------------------------------ node building

    private TreeItem<Object> schemaItem(Object payload) {
        SchemaNode node = (SchemaNode) payload;
        TreeItem<Object> item = new TreeItem<>(node);
        item.getChildren().add(loading());
        item.expandedProperty().addListener((o, was, open) -> {
            if (open && isLoadingOnly(item)) {
                item.getChildren().clear();
                for (String cat : CATEGORIES) {
                    item.getChildren().add(categoryItem(node.name(), cat));
                }
            }
        });
        return item;
    }

    private TreeItem<Object> categoryItem(String schema, String category) {
        TreeItem<Object> item = new TreeItem<>(new CategoryNode(schema, category));
        item.getChildren().add(loading());
        item.expandedProperty().addListener((o, was, open) -> {
            if (!open || !isLoadingOnly(item)) return;
            ConnectionProfile profile = ctx.profileFor(schema);
            if (profile == null) return;
            load(() -> fetchCategory(profile, schema, category), item, objs -> {
                item.getChildren().clear();
                item.getChildren().setAll(objs.stream().map(this::objectItem).toList());
            });
        });
        return item;
    }

    private TreeItem<Object> objectItem(Object payload) {
        TreeItem<Object> item = new TreeItem<>(payload);
        if (payload instanceof TableNode tn) {
            item.getChildren().add(loading());
            item.expandedProperty().addListener((o, was, open) -> {
                if (!open || !isLoadingOnly(item)) return;
                item.getChildren().clear();
                tn.table().columns().forEach(c -> item.getChildren().add(new TreeItem<>(new ColumnNode(c))));
            });
        }
        return item;
    }

    private List<?> fetchCategory(ConnectionProfile profile, String schema, String category) throws SQLException {
        try (Connection c = ctx.openConnection(profile)) {
            MySqlMetadataReader r = new MySqlMetadataReader(c);
            // plain loops: readTable() throws SQLException, which a stream lambda cannot carry
            List<Object> out = new ArrayList<>();
            switch (category) {
                case "tables" -> {
                    for (String name : r.listTableNames(schema)) {
                        out.add(new TableNode(r.readTable(schema, name)));
                    }
                }
                case "views" -> r.listViews(schema).forEach(v -> out.add(new ViewNode(v)));
                case "routines" -> r.listRoutines(schema).forEach(x -> out.add(new RoutineNode(x)));
                default -> r.listTriggers(schema).forEach(t -> out.add(new TriggerNode(t)));
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ context menu

    private void installContextMenu() {
        ContextMenu menu = new ContextMenu();
        tree.setContextMenu(menu);
        menu.setOnShowing(e -> buildMenu(menu));
        tree.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && e.getButton().equals(javafx.scene.input.MouseButton.PRIMARY)) {
                openSelected();
            }
        });
    }

    private void buildMenu(ContextMenu menu) {
        TreeItem<Object> sel = tree.getSelectionModel().getSelectedItem();
        menu.getItems().clear();
        if (sel == null || sel.getValue() == null) return;
        Object node = sel.getValue();
        if (node instanceof TableNode tn) {
            menu.getItems().addAll(
                    item(I18n.tr("tree.viewData"), () -> workspace.openDataTab(tn.table())),
                    item(I18n.tr("tree.viewStructure"), () -> workspace.openStructureTab(
                            SqlNames.qualified(tn.table().schema(), tn.table().name()),
                            tn.table().createStatement())),
                    new SeparatorMenuItem(),
                    item(I18n.tr("tree.genSelect"), () -> workspace.openEditorText(
                            SqlNames.selectAll(tn.table(), 100))),
                    item(I18n.tr("tree.genInsert"), () -> workspace.openEditorText(
                            SqlNames.insertTemplate(tn.table()))),
                    item(I18n.tr("tree.genUpdate"), () -> workspace.openEditorText(
                            SqlNames.updateTemplate(tn.table()))));
        } else if (node instanceof ViewNode vn) {
            menu.getItems().addAll(
                    item(I18n.tr("tree.viewStructure"), () -> workspace.openStructureTab(vn.view().name(),
                            vn.view().createStatement())),
                    item(I18n.tr("tree.genSelect"), () -> workspace.openEditorText(
                            "SELECT * FROM " + SqlNames.qualified(vn.view().schema(), vn.view().name()) + " LIMIT 100")));
        } else if (node instanceof RoutineNode rn) {
            menu.getItems().add(item(I18n.tr("tree.viewStructure"),
                    () -> workspace.openStructureTab(rn.routine().name(), rn.routine().createStatement())));
        } else if (node instanceof TriggerNode tr) {
            menu.getItems().add(item(I18n.tr("tree.viewStructure"),
                    () -> workspace.openStructureTab(tr.trigger().name(), tr.trigger().createStatement())));
        } else if (node instanceof SchemaNode sn) {
            menu.getItems().addAll(
                    item(I18n.tr("tree.newQuery"),
                            () -> workspace.openEditorText("SELECT NOW(); -- " + sn.name())),
                    item(I18n.tr("tree.sync"),
                            () -> SyncWizard.show(view().getScene().getWindow(), ctx, sn.name())));
        }
        if (!menu.getItems().isEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
            menu.getItems().add(item(I18n.tr("tree.refresh"), () -> refreshFrom(sel)));
        }
    }

    private void openSelected() {
        TreeItem<Object> sel = tree.getSelectionModel().getSelectedItem();
        if (sel == null || sel.getValue() == null) return;
        if (sel.getValue() instanceof TableNode tn) {
            workspace.openDataTab(tn.table());
        } else if (sel.getValue() instanceof ViewNode vn) {
            workspace.openStructureTab(vn.view().name(), vn.view().createStatement());
        } else if (sel.getValue() instanceof RoutineNode rn) {
            workspace.openStructureTab(rn.routine().name(), rn.routine().createStatement());
        } else if (sel.getValue() instanceof TriggerNode tr) {
            workspace.openStructureTab(tr.trigger().name(), tr.trigger().createStatement());
        }
    }

    private void refreshFrom(TreeItem<Object> sel) {
        TreeItem<Object> parent = sel.getParent();
        if (parent != null) {
            parent.setExpanded(false);
            parent.setExpanded(true);
        } else {
            showConnection(ctx.activeProfile());
        }
    }

    // ------------------------------------------------------------------ plumbing

    private interface Loader {
        List<?> load() throws Exception;
    }

    /** Run a metadata query off the FX thread, then fill the item (Task events are on FX thread). */
    private void load(Loader loader, TreeItem<Object> item, java.util.function.Consumer<List<?>> filler) {
        Task<List<?>> task = new Task<>() {
            @Override protected List<?> call() throws Exception {
                return loader.load();
            }
        };
        task.setOnSucceeded(e -> filler.accept(task.getValue()));
        task.setOnFailed(e -> {
            Throwable err = task.getException();
            Platform.runLater(() -> {
                item.getChildren().clear();
                item.getChildren().add(errorItem(err));
            });
        });
        pool.submit(task);
    }

    private static TreeItem<Object> loading() {
        return new TreeItem<>(I18n.tr("tree.loading"));
    }

    private static TreeItem<Object> errorItem(Throwable err) {
        TreeItem<Object> item = new TreeItem<>(I18n.tr("tree.error", err.getMessage()));
        item.setExpanded(false);
        return item;
    }

    private static boolean isLoading(TreeItem<Object> item) {
        return item != null && item.getValue() instanceof String;
    }

    private static boolean isLoadingOnly(TreeItem<Object> item) {
        return item.getChildren().size() == 1 && isLoading(item.getChildren().get(0));
    }

    private static MenuItem item(String text, Runnable action) {
        MenuItem mi = new MenuItem(text);
        mi.setOnAction(e -> action.run());
        return mi;
    }

    private String label(Object node) {
        if (node instanceof SchemaNode n) return n.name();
        if (node instanceof CategoryNode n) return I18n.tr("tree.category." + n.category());
        if (node instanceof TableNode n) return n.table().name();
        if (node instanceof ViewNode n) return n.view().name();
        if (node instanceof RoutineNode n) return n.routine().name()
                + (n.routine().kind() == RoutineDef.Kind.FUNCTION ? " ()" : "");
        if (node instanceof TriggerNode n) return n.trigger().name();
        if (node instanceof ColumnNode n) return "  " + n.column().name() + "  " + n.column().type();
        return String.valueOf(node);
    }
}
