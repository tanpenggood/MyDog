package com.mydog.ui;

import com.mydog.connection.ConnectionProfile;
import com.mydog.core.AppContext;
import com.mydog.i18n.I18n;
import com.mydog.ui.dialog.ConnectionDialog;
import com.mydog.ui.sync.SyncWizard;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Main window: menu + connection toolbar, object tree on the left, tabbed workspace on the
 * right, status bar at the bottom (which doubles as the connection-test output line).
 */
public final class MainWindow {

    private MainWindow() {
    }

    public static Scene build(Stage stage, AppContext ctx, Runnable rebuild) {
        BorderPane root = new BorderPane();

        Label status = new Label(I18n.tr("main.status.ready"));
        status.getStyleClass().add("status-bar");
        status.setPadding(new Insets(4, 10, 4, 10));
        status.setMaxWidth(Double.MAX_VALUE);

        Workspace workspace = new Workspace(ctx);
        ObjectTreeController tree = new ObjectTreeController(ctx, workspace);
        SplitPane split = new SplitPane(tree.view(), workspace);
        split.setDividerPositions(0.24);
        javafx.application.Platform.runLater(() -> split.setDividerPosition(0, 0.24));

        root.setTop(buildTop(stage, ctx, rebuild, status, tree, workspace));
        root.setCenter(split);
        root.setBottom(status);

        Scene scene = new Scene(root, 1100, 720);
        scene.getStylesheets().add(MainWindow.class.getResource("/com/mydog/css/app.css").toExternalForm());
        return scene;
    }

    private static VBox buildTop(Stage stage, AppContext ctx, Runnable rebuild, Label status,
                                 ObjectTreeController tree, Workspace workspace) {
        Menu langMenu = new Menu(I18n.tr("menu.language"));
        MenuItem zh = new MenuItem(I18n.tr("menu.language.zh_CN"));
        zh.setOnAction(e -> switchLocale(ctx, "zh_CN", rebuild));
        MenuItem en = new MenuItem(I18n.tr("menu.language.en"));
        en.setOnAction(e -> switchLocale(ctx, "en", rebuild));
        langMenu.getItems().addAll(zh, en);

        // the wizard preselects whatever database the tree currently has focused, when it can
        MenuItem syncItem = new MenuItem(I18n.tr("sync.title"));
        syncItem.setOnAction(e -> SyncWizard.show(stage, ctx, tree.focusedSchema()));
        Menu toolsMenu = new Menu(I18n.tr("menu.tools"));
        toolsMenu.getItems().add(syncItem);

        // the menu-bar row: New Connection comes first, then the menus (语言 / 工具)
        Button newBtn = new Button(I18n.tr("conn.new"));

        ComboBox<ConnectionProfile> connBox = new ComboBox<>();
        connBox.setPrefWidth(260);
        connBox.setPlaceholder(new Label(I18n.tr("main.noConnection")));
        connBox.setButtonCell(new ConnectionListCell());
        connBox.setCellFactory(v -> new ConnectionListCell());
        refreshConnections(connBox, ctx);

        Runnable refresh = () -> refreshConnections(connBox, ctx);
        newBtn.setOnAction(e -> ConnectionDialog.showNew(stage, ctx, refresh));

        // Edit / delete / test live on the object tree's right-click menu (see
        // ObjectTreeController.buildConnectionMenu): they act on the connection the pointer
        // is on, and the toolbar stops duplicating them.

        // selecting a connection drives the tree and clears stale tabs
        connBox.valueProperty().addListener((o, was, now) -> {
            ctx.setActiveProfile(now);
            workspace.closeAll();
            tree.showConnection(now);
            reportToStatus(status, now == null
                    ? I18n.tr("main.status.ready")
                    : I18n.tr("main.status.selected", now.displayLabel()), true);
        });
        if (connBox.getValue() != null) {
            ctx.setActiveProfile(connBox.getValue());
            tree.showConnection(connBox.getValue());
        }

        // a delete (or edit) done from the tree's own menu changed the store behind the
        // combo's back — this is how the combo finds out and drops the ghost
        tree.setOnStoreChanged(refresh);

        MenuBar menuBar = new MenuBar(langMenu, toolsMenu);
        // the button sits inside the menu row itself, before the menus, per the UI spec
        javafx.scene.layout.HBox menuRow = new javafx.scene.layout.HBox(6, newBtn, menuBar);
        HBox.setHgrow(menuBar, javafx.scene.layout.Priority.ALWAYS);
        ToolBar toolBar = new ToolBar(connBox);
        return new VBox(menuRow, toolBar);
    }

    /** Status bar doubles as the connection-test result line (green ok / red failure). */
    private static void reportToStatus(Label status, String text, boolean ok) {
        status.getStyleClass().removeAll("test-ok", "test-fail");
        status.getStyleClass().add(ok ? "test-ok" : "test-fail");
        status.setText(text);
    }

    private static void refreshConnections(ComboBox<ConnectionProfile> box, AppContext ctx) {
        var items = box.getItems();
        ConnectionProfile selected = box.getValue();
        items.setAll(ctx.connections().all());
        if (selected != null) {
            // keep the selection if it survived; otherwise the current value is stale
            // (e.g. it was just deleted) — clear it so the combo doesn't show a ghost profile
            ConnectionProfile stillThere = items.stream()
                    .filter(p -> p.id().equals(selected.id())).findFirst().orElse(null);
            if (stillThere != null) {
                box.setValue(stillThere);
            } else {
                // clearSelection() alone is NOT enough: ComboBox keeps a user-set value even
                // with no selection, so the deleted profile stayed on screen as a ghost.
                // Overwrite the value explicitly, then fall through to pick a survivor.
                box.setValue(null);
                if (!items.isEmpty()) {
                    box.getSelectionModel().selectFirst();
                }
            }
        } else if (!items.isEmpty()) {
            box.getSelectionModel().selectFirst();
        }
    }

    private static void switchLocale(AppContext ctx, String tag, Runnable rebuild) {
        if (!I18n.localeTag().equals(tag)) {
            ctx.setLocale(tag);
            rebuild.run();
        }
    }

    /** Simple cell: shows the profile display label. */
    private static class ConnectionListCell extends ListCell<ConnectionProfile> {
        @Override
        protected void updateItem(ConnectionProfile item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? I18n.tr("main.noConnection") : item.displayLabel());
        }
    }
}
