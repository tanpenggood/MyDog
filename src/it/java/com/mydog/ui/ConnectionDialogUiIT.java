package com.mydog.ui;

import com.mydog.connection.ConnectionProfile;
import com.mydog.connection.MySqlConnectionFactory;
import com.mydog.connection.SslMode;
import com.mydog.core.AppContext;
import com.mydog.i18n.I18n;
import com.mydog.ui.dialog.ConnectionDialog;
import com.mydog.ui.sync.FxTestSupport;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.mydog.ui.sync.FxTestSupport.ensureToolkit;
import static com.mydog.ui.sync.FxTestSupport.fx;
import static com.mydog.ui.sync.FxTestSupport.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the two connection-UX fixes at the widget level, the only level where their bugs lived:
 *
 * <ul>
 *   <li>the "Test Connection" button in the edit dialog must visibly report every click — an
 *       earlier event filter consumed the ActionEvent before the handler could run, so the
 *       button did literally nothing, and no test ever noticed because nothing drove it;</li>
 *   <li>edit / delete / test must be offered on the data source's own right-click menu in the
 *       object tree, instead of duplicated as toolbar buttons.</li>
 * </ul>
 *
 * <p>Needs the docker MySQL pair (a real connectable server, because the point of the dialog's
 * test button is a round trip); aborts, not fails, when they are not up.
 */
@Tag("docker")
class ConnectionDialogUiIT {

    private static final String PASSWORD = "root123";
    private static final String PROFILE_ID = "it-conn-dlg";

    @TempDir
    static Path configDir;

    static AppContext ctx;

    @BeforeAll
    static void setUp() throws SQLException {
        ensureToolkit();
        try (Connection c = MySqlConnectionFactory.open(profile(null), PASSWORD)) {
            // reachable
        } catch (SQLException e) {
            Assumptions.abort("docker MySQL sandbox not usable (3307): " + e.getMessage()
                    + " — run: cd docker && docker compose up -d --wait");
        }
        ctx = AppContext.createIn(configDir);
        I18n.setLocale("en");   // button labels are how the clicks find their target
        ctx.connections().save(profile("conn-dlg").withEncryptedPassword(
                ctx.cipher().encrypt(PASSWORD)));
    }

    @AfterAll
    static void tearDown() {
        ConnectionDialog.testHook = null;
    }

    @Test
    void testButtonReportsTheOutcomeOfEveryClick() {
        // success first: a server that is up
        testButtonRoundTrip("127.0.0.1", 3307, true);
        // then failure: a port nothing listens on — the second click must repaint the label
        // (old failure → new failure) instead of silently doing nothing
        testButtonRoundTrip("127.0.0.1", 59999, false);
    }

    private void testButtonRoundTrip(String host, int port, boolean expectOk) {
        AtomicReference<javafx.scene.control.Dialog<ConnectionProfile>> dialogRef = new AtomicReference<>();
        AtomicReference<Label> messageRef = new AtomicReference<>();
        AtomicReference<Button> buttonRef = new AtomicReference<>();
        // what the dialog would show for that host/port: the profile from the store, re-pointed
        ConnectionProfile onScreen = new ConnectionProfile(PROFILE_ID, "conn-dlg", host, port,
                "root", ctx.connections().find(PROFILE_ID).orElseThrow().encryptedPassword(),
                null, SslMode.DISABLED, null);

        ConnectionDialog.testHook = (dialog, handles) -> {
            dialogRef.set(dialog);
            messageRef.set(handles.messageLabel());
            buttonRef.set(handles.testButton());
        };

        // the dialog is constructed and shown on the FX thread, exactly as the wizard IT does
        // it: showAndWait there starts a nested event loop, and the test drives from this thread
        Platform.runLater(() -> ConnectionDialog.showEdit(null, ctx, onScreen, () -> { }));

        waitUntil("the dialog to open", () -> dialogRef.get() != null
                && fx(() -> dialogRef.get().isShowing()));

        // firing the button is what a click is; the message line must acknowledge the click
        // before the outcome lands — that is the "user sees feedback" part of the fix
        com.mydog.ui.sync.FxTestSupport.runOnFx(() -> buttonRef.get().fire());
        waitUntil("the message label to say Testing...", () ->
                I18n.tr("conn.testing").equals(messageRef.get().getText()));

        // the button disables while a test runs, and comes back when it ends — that bounce is
        // the only "something happened" signal besides the text itself
        waitUntil("the result text to land", () -> {
            String text = messageRef.get().getText();
            return text != null && !I18n.tr("conn.testing").equals(text);
        });
        waitUntil("the button to re-enable", () -> !buttonRef.get().isDisabled());

        String text = messageRef.get().getText();
        List<String> style = messageRef.get().getStyleClass();
        if (expectOk) {
            assertThat(text).as("success message").contains("Server version");
            assertThat(style).contains("test-ok");
        } else {
            assertThat(text).as("failure message").contains("Connection failed");
            assertThat(style).contains("test-fail");
        }

        // close the dialog: showAndWait's nested event loop ends, the runLater returns
        com.mydog.ui.sync.FxTestSupport.runOnFx(() -> dialogRef.get().close());
        waitUntil("the dialog to close", () -> !fx(() -> dialogRef.get().isShowing()));
    }

    @Test
    void objectTreeOffersConnectionActionsOnRightClick() {
        // the tree must live in a showing window: its menu opens as that window's popup
        AtomicReference<ObjectTreeController> treeRef = new AtomicReference<>();
        javafx.stage.Stage stage = fx(() -> {
            javafx.stage.Stage st = new javafx.stage.Stage();
            ObjectTreeController c = new ObjectTreeController(ctx, null);
            c.showConnection(ctx.connections().find(PROFILE_ID).orElseThrow());
            st.setScene(new javafx.scene.Scene(c.view(), 300, 400));
            st.show();
            treeRef.set(c);
            return st;
        });
        ObjectTreeController controller = treeRef.get();
        waitUntil("the root row to be rendered", () -> fx(() ->
                controller.view().lookupAll(".tree-cell").size()) > 0);

        // the root of the tree is the connection itself
        Object rootPayload = fx(() -> controller.view().getRoot().getValue());
        assertThat(rootPayload).isInstanceOf(ObjectTreeController.ConnectionNode.class);

        // right-click the root row the way the OS does: a CONTEXT_MENU_REQUESTED on its cell.
        // This is the exact path that used to be dead — the menu was lazily built on
        // onShowing, which ContextMenu.show() never reaches while the item list is empty,
        // so nothing ever appeared. A menu that opens here is what the user gets.
        javafx.scene.control.ContextMenu menu = fx(() -> {
            javafx.scene.control.TreeCell<?> cell = null;
            for (var node : controller.view().lookupAll(".tree-cell")) {
                if (((javafx.scene.control.TreeCell<?>) node).getIndex() == 0) {
                    cell = (javafx.scene.control.TreeCell<?>) node;
                    break;
                }
            }
            assertThat(cell).as("the root row's cell").isNotNull();
            var ev = new javafx.scene.input.ContextMenuEvent(null, null,
                    javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    150, 12, 150, 12, false, null);
            javafx.event.Event.fireEvent(cell, ev);
            return javafx.stage.Window.getWindows().stream()
                    .filter(w -> w instanceof javafx.scene.control.ContextMenu m && m.isShowing())
                    .map(w -> (javafx.scene.control.ContextMenu) w)
                    .findFirst().orElse(null);
        });
        assertThat(menu).as("the menu that right-click must open").isNotNull();

        List<String> labels = fx(() -> menu.getItems().stream()
                .map(javafx.scene.control.MenuItem::getText)
                .toList());
        assertThat(labels).as("the connection node's menu")
                .contains(I18n.tr("conn.edit"), I18n.tr("conn.delete"), I18n.tr("conn.test"));

        // the right-click selects the row it happened on — what the menu acts on
        Object selected = fx(() -> controller.view().getSelectionModel()
                .getSelectedItem().getValue());
        assertThat(selected).isInstanceOf(ObjectTreeController.ConnectionNode.class);

        fx(() -> {
            // snapshot first: hiding mutates the window list while the stream would walk it
            var menus = javafx.stage.Window.getWindows().stream()
                    .filter(w -> w instanceof javafx.scene.control.ContextMenu m && m.isShowing())
                    .map(w -> (javafx.scene.control.ContextMenu) w)
                    .toList();
            menus.forEach(javafx.scene.control.ContextMenu::hide);
            stage.close();
            return null;
        });
    }

    @Test
    void deletingFromTheTreeLeavesNoGhostInTheToolbarCombo() {
        // a second profile to delete, so the "it-conn-dlg" one the other tests need stays alive
        ConnectionProfile victim = new ConnectionProfile("it-ghost",
                "ghost", "127.0.0.1", 3307, "root", "", null, SslMode.DISABLED, null);
        ctx.connections().save(victim);

        AtomicReference<javafx.scene.Scene> sceneRef = new AtomicReference<>();
        fx(() -> {
            javafx.scene.Scene scene = MainWindow.build(new javafx.stage.Stage(), ctx, () -> { });
            javafx.stage.Stage st = new javafx.stage.Stage();
            st.setScene(scene);
            st.show();
            sceneRef.set(scene);
            return null;
        });
        javafx.scene.Scene scene = sceneRef.get();

        // auto-answer the confirmation alert: click its OK as soon as its window appears
        javafx.collections.ObservableList<javafx.stage.Window> windows =
                javafx.stage.Window.getWindows();
        windows.addListener((javafx.collections.ListChangeListener<javafx.stage.Window>) change -> {
            while (change.next()) {
                for (javafx.stage.Window w : change.getAddedSubList()) {
                    if (w instanceof javafx.stage.Stage stg && stg.getScene() != null
                            && stg.getScene().getRoot() instanceof javafx.scene.control.DialogPane dp) {
                        var ok = dp.lookupButton(javafx.scene.control.ButtonType.OK);
                        if (ok instanceof Button b) {
                            Platform.runLater(b::fire);
                        }
                    }
                }
            }
        });

        // the combo starts on the first saved profile; select the victim so deleting the tree's
        // root connection is deleting what the combo also shows
        @SuppressWarnings("unchecked")
        javafx.scene.control.ComboBox<ConnectionProfile> combo = (javafx.scene.control.ComboBox<ConnectionProfile>)
                scene.getRoot().lookup(".combo-box");
        int victimIndex = fx(() -> combo.getItems().indexOf(
                combo.getItems().stream().filter(p -> p.id().equals("it-ghost")).findFirst().orElseThrow()));
        fx(() -> {
            combo.getSelectionModel().select(victimIndex);
            return null;
        });
        waitUntil("the tree to show the victim connection",
                () -> fx(() -> ((ObjectTreeController.ConnectionNode)
                        ((javafx.scene.control.TreeView<?>) scene.getRoot().lookup(".tree-view"))
                                .getRoot().getValue()).profile().id().equals("it-ghost")));

        // right-click the tree root: fire the event on the cell and keep the menu it opens,
        // so the delete click below goes through a reference instead of a window scan that
        // can race with auto-hide in a full-suite run
        AtomicReference<javafx.scene.control.ContextMenu> menuRef = new AtomicReference<>();
        fx(() -> {
            javafx.scene.control.TreeView<?> tree =
                    (javafx.scene.control.TreeView<?>) scene.getRoot().lookup(".tree-view");
            javafx.scene.control.TreeCell<?> cell = null;
            for (var node : tree.lookupAll(".tree-cell")) {
                if (((javafx.scene.control.TreeCell<?>) node).getIndex() == 0) {
                    cell = (javafx.scene.control.TreeCell<?>) node;
                    break;
                }
            }
            var ev = new javafx.scene.input.ContextMenuEvent(null, null,
                    javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    150, 12, 150, 12, false, null);
            javafx.event.Event.fireEvent(cell, ev);
            // the handler showed a menu synchronously — grab it before anything can hide it
            menuRef.set(javafx.stage.Window.getWindows().stream()
                    .filter(w -> w instanceof javafx.scene.control.ContextMenu m && m.isShowing())
                    .map(w -> (javafx.scene.control.ContextMenu) w)
                    .findFirst().orElse(null));
            return null;
        });
        waitUntil("the delete menu to open", () -> menuRef.get() != null);
        fx(() -> {
            menuRef.get().getItems().stream()
                    .filter(i -> I18n.tr("conn.delete").equals(i.getText()))
                    .findFirst().orElseThrow().fire();
            return null;
        });
        waitUntil("the profile to be gone from the store",
                () -> ctx.connections().find("it-ghost").isEmpty());

        // the assertion this test exists for: the combo no longer holds the deleted profile,
        // as its value or as a list entry — the tree knew, now the toolbar must too
        ConnectionProfile comboValue = fx(() -> combo.getValue());
        assertThat(comboValue).as("combo value after delete").isNotEqualTo(victim);
        List<ConnectionProfile> comboItems = fx(() -> combo.getItems());
        assertThat(comboItems).as("combo items after delete")
                .noneMatch(p -> p.id().equals("it-ghost"));

        fx(() -> {
            scene.getWindow().hide();
            return null;
        });
    }

    private static ConnectionProfile profileWithId(String id) {
        return ctx.connections().find(id).orElseThrow();
    }

    private static ConnectionProfile profile(String name) {
        return new ConnectionProfile(PROFILE_ID, name == null ? "" : name, "127.0.0.1", 3307,
                "root", "", null, SslMode.DISABLED, null);
    }
}
