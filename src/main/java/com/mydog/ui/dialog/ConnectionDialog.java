package com.mydog.ui.dialog;

import com.mydog.connection.ConnectionProfile;
import com.mydog.connection.MySqlConnectionFactory;
import com.mydog.connection.SslMode;
import com.mydog.core.AppContext;
import com.mydog.i18n.I18n;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

/** New/edit connection dialog with async "test connection". */
public final class ConnectionDialog {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectionDialog.class);

    private ConnectionDialog() {
    }

    public static void showNew(Stage owner, AppContext ctx, Runnable onChanged) {
        show(owner, ctx, ConnectionProfile.empty(), true, onChanged);
    }

    public static void showEdit(Stage owner, AppContext ctx, ConnectionProfile current, Runnable onChanged) {
        show(owner, ctx, current, false, onChanged);
    }

    private static void show(Stage owner, AppContext ctx, ConnectionProfile current,
                             boolean isNew, Runnable onChanged) {
        Dialog<ConnectionProfile> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(isNew ? I18n.tr("conn.new") : I18n.tr("conn.edit"));

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));

        TextField name = new TextField(current.name());
        name.setPromptText(I18n.tr("conn.name"));
        TextField host = new TextField(current.host());
        TextField port = new TextField(String.valueOf(current.port()));
        port.setPrefWidth(80);
        TextField user = new TextField(current.user());
        PasswordField password = new PasswordField();
        // show a blank password field; keep saved password unless user types a new one
        password.setPromptText(current.encryptedPassword() == null || current.encryptedPassword().isEmpty()
                ? "" : "********");
        TextField schema = new TextField(current.defaultSchema());
        ComboBox<SslMode> ssl = new ComboBox<>();
        ssl.getItems().addAll(SslMode.values());
        ssl.setValue(current.sslMode());

        int r = 0;
        grid.add(new Label(I18n.tr("conn.name")), 0, r);
        grid.add(name, 1, r++);
        grid.add(new Label(I18n.tr("conn.host")), 0, r);
        HBox hostPort = new HBox(8, host, port);
        grid.add(hostPort, 1, r++);
        grid.add(new Label(I18n.tr("conn.user")), 0, r);
        grid.add(user, 1, r++);
        grid.add(new Label(I18n.tr("conn.password")), 0, r);
        grid.add(password, 1, r++);
        grid.add(new Label(I18n.tr("conn.defaultSchema")), 0, r);
        grid.add(schema, 1, r++);
        grid.add(new Label(I18n.tr("conn.sslMode")), 0, r);
        grid.add(ssl, 1, r++);

        Label message = new Label();
        message.setWrapText(true);
        // Dialog is not resizable and sizes to content: without an explicit width the
        // wrapping label collapses to a narrow strip and long errors get cut to "…".
        message.setPrefWidth(440);
        message.setPadding(new Insets(4, 0, 0, 0));
        grid.add(message, 0, r, 2, 1);

        Button testBtn = new Button(I18n.tr("conn.test"));
        testBtn.addEventFilter(javafx.event.ActionEvent.ACTION, e -> e.consume()); // never close dialog
        testBtn.setOnAction(e -> {
            ConnectionProfile draft = collect(current, name, host, port, user, password, schema, ssl, ctx, false);
            // the draft only carries the stored ciphertext; test with what is on screen now
            testConnectionAsync(draft, plainPassword(ctx, current, password), testBtn, message, null);
        });
        HBox buttons = new HBox(10, testBtn);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(0, 20, 10, 20));

        BorderPaneWithButtons content = new BorderPaneWithButtons(grid, buttons);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText(I18n.tr("conn.save"));
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText(I18n.tr("conn.cancel"));

        dialog.setResultConverter(bt -> {
            if (bt == ButtonType.OK) {
                return collect(current, name, host, port, user, password, schema, ssl, ctx, true);
            }
            return null;
        });

        Optional<ConnectionProfile> result = dialog.showAndWait();
        result.ifPresent(p -> {
            ctx.connections().save(p);
            onChanged.run();
        });
    }

    private static ConnectionProfile collect(ConnectionProfile current, TextField name, TextField host,
                                             TextField port, TextField user, PasswordField password,
                                             TextField schema, ComboBox<SslMode> ssl,
                                             AppContext ctx, boolean persistPassword) {
        String enc = current.encryptedPassword() == null ? "" : current.encryptedPassword();
        if (persistPassword) {
            String typed = password.getText();
            if (typed != null && !typed.isEmpty()) {
                enc = ctx.cipher().encrypt(typed);
            }
            // blank field + "********" placeholder means "keep the stored password"
        }
        int portNum;
        try {
            portNum = Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException e) {
            portNum = 3306;
        }
        return new ConnectionProfile(
                current.id(), name.getText().trim(), host.getText().trim(), portNum,
                user.getText().trim(), enc, schema.getText().trim(), ssl.getValue(), null);
    }

    /**
     * Toolbar test: result is reported to the caller (main window status bar) instead of a
     * throwaway popup, which could end up hidden behind the main window on macOS.
     */
    public static void testConnection(AppContext ctx, ConnectionProfile p,
                                      java.util.function.BiConsumer<Boolean, String> reporter) {
        LOG.info("testing connection '{}' -> {}:{}", p.name(), p.host(), p.port());
        reporter.accept(false, I18n.tr("conn.testing"));
        testConnectionAsync(p, ctx.cipher().decrypt(p.encryptedPassword()), null, null, reporter);
    }

    /** Password to hand to the driver: freshly typed text, else the stored ciphertext decrypted. */
    private static String plainPassword(AppContext ctx, ConnectionProfile current, PasswordField password) {
        String typed = password.getText();
        if (typed != null && !typed.isEmpty()) {
            return typed;
        }
        return ctx.cipher().decrypt(current.encryptedPassword());
    }

    private static void testConnectionAsync(ConnectionProfile p, String plainPassword,
                                            Button sourceBtn, Label messageLabel,
                                            java.util.function.BiConsumer<Boolean, String> reporter) {
        if (sourceBtn != null) {
            sourceBtn.setDisable(true);
        }
        if (messageLabel != null) {
            messageLabel.setText(I18n.tr("conn.testing"));
        }
        Thread t = new Thread(() -> {
            String text;
            boolean ok;
            try (Connection conn = MySqlConnectionFactory.open(p, plainPassword)) {
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT VERSION()")) {
                    String version = rs.next() ? rs.getString(1) : "?";
                    text = I18n.tr("conn.test.success", version);
                    ok = true;
                }
            } catch (Exception e) {
                text = I18n.tr("conn.test.failed", rootMessage(e));
                ok = false;
            }
            final String fText = text;
            final boolean fOk = ok;
            Platform.runLater(() -> {
                if (sourceBtn != null) sourceBtn.setDisable(false);
                if (messageLabel != null) {
                    messageLabel.getStyleClass().removeAll("test-ok", "test-fail");
                    messageLabel.getStyleClass().add(fOk ? "test-ok" : "test-fail");
                    messageLabel.setText(fText);
                }
                if (reporter != null) {
                    reporter.accept(fOk, fText);
                }
            });
        }, "conn-test");
        t.setDaemon(true);
        t.start();
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    /** Grid on top, test button row at bottom. */
    private static class BorderPaneWithButtons extends javafx.scene.layout.BorderPane {
        BorderPaneWithButtons(javafx.scene.Node center, javafx.scene.Node bottom) {
            setCenter(center);
            setBottom(bottom);
        }
    }
}
