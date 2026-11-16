package com.mydog.ui;

import com.mydog.connection.ConnectionProfile;
import com.mydog.core.AppContext;
import com.mydog.i18n.I18n;
import com.mydog.ui.dialog.ConnectionDialog;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * M1 main window: toolbar (connection selector + language menu) + welcome placeholder
 * + status bar. Object tree and editor tabs arrive in M2.
 */
public final class MainWindow {

    private MainWindow() {
    }

    public static Scene build(Stage stage, AppContext ctx, Runnable rebuild) {
        BorderPane root = new BorderPane();

        // status bar first: the toolbar reports connection test results into it
        Label status = new Label(I18n.tr("main.status.ready"));
        status.getStyleClass().add("status-bar");
        status.setPadding(new Insets(4, 10, 4, 10));
        status.setMaxWidth(Double.MAX_VALUE);
        root.setBottom(status);

        root.setTop(buildTop(stage, ctx, rebuild, status));

        Label welcome = new Label(I18n.tr("main.welcome"));
        welcome.getStyleClass().add("welcome");
        StackPane center = new StackPane(welcome);
        root.setCenter(center);

        Scene scene = new Scene(root);
        scene.getStylesheets().add(MainWindow.class.getResource("/com/mydog/css/app.css").toExternalForm());
        return scene;
    }

    private static VBox buildTop(Stage stage, AppContext ctx, Runnable rebuild, Label status) {
        // menu bar
        Menu langMenu = new Menu(I18n.tr("menu.language"));
        MenuItem zh = new MenuItem(I18n.tr("menu.language.zh_CN"));
        zh.setOnAction(e -> switchLocale(ctx, "zh_CN", rebuild));
        MenuItem en = new MenuItem(I18n.tr("menu.language.en"));
        en.setOnAction(e -> switchLocale(ctx, "en", rebuild));
        langMenu.getItems().addAll(zh, en);
        MenuBar menuBar = new MenuBar(langMenu);

        // toolbar: connection combo + actions
        ComboBox<ConnectionProfile> connBox = new ComboBox<>();
        connBox.setPrefWidth(240);
        connBox.setPlaceholder(new Label(I18n.tr("main.noConnection")));
        connBox.setButtonCell(new ConnectionListCell());
        connBox.setCellFactory(v -> new ConnectionListCell());
        refreshConnections(connBox, ctx);

        Button newBtn = new Button(I18n.tr("conn.new"));
        Button editBtn = new Button(I18n.tr("conn.edit"));
        Button delBtn = new Button(I18n.tr("conn.delete"));
        Button testBtn = new Button(I18n.tr("conn.test"));

        Runnable refresh = () -> refreshConnections(connBox, ctx);
        newBtn.setOnAction(e -> ConnectionDialog.showNew(stage, ctx, refresh));
        editBtn.disableProperty().bind(connBox.valueProperty().isNull());
        editBtn.setOnAction(e -> ConnectionDialog.showEdit(stage, ctx, connBox.getValue(), refresh));
        delBtn.disableProperty().bind(connBox.valueProperty().isNull());
        delBtn.setOnAction(e -> confirmDelete(stage, ctx, connBox.getValue(), refresh));
        testBtn.disableProperty().bind(connBox.valueProperty().isNull());
        testBtn.setOnAction(e -> {
            ConnectionProfile selected = connBox.getValue();
            if (selected != null) {
                ConnectionDialog.testConnection(ctx, selected,
                        (ok, text) -> reportToStatus(status, text, ok));
            }
        });

        ToolBar toolBar = new ToolBar(connBox, newBtn, editBtn, delBtn,
                new javafx.scene.control.Separator(), testBtn);

        VBox top = new VBox(menuBar, toolBar);
        return top;
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
            items.stream().filter(p -> p.id().equals(selected.id())).findFirst().ifPresent(box::setValue);
        } else if (!items.isEmpty()) {
            box.getSelectionModel().selectFirst();
        }
    }

    private static void confirmDelete(Stage stage, AppContext ctx, ConnectionProfile p, Runnable refresh) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                I18n.tr("conn.deleteConfirm", p.name()),
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        alert.initOwner(stage);
        alert.setHeaderText(null);
        alert.showAndWait().ifPresent(bt -> {
            if (bt == javafx.scene.control.ButtonType.OK) {
                ctx.connections().delete(p.id());
                refresh.run();
            }
        });
    }

    private static void switchLocale(AppContext ctx, String tag, Runnable rebuild) {
        if (!I18n.localeTag().equals(tag)) {
            ctx.setLocale(tag);
            rebuild.run();
        }
    }

    /** Simple cell: shows connection name (or host when name is blank). */
    private static class ConnectionListCell extends javafx.scene.control.ListCell<ConnectionProfile> {
        @Override
        protected void updateItem(ConnectionProfile item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(I18n.tr("main.noConnection"));
            } else {
                String label = item.name().isBlank()
                        ? item.user() + "@" + item.host() + ":" + item.port()
                        : item.name();
                setText(label);
            }
        }
    }
}
