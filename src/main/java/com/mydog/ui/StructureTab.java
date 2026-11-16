package com.mydog.ui;

import com.mydog.i18n.I18n;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

/** Read-only DDL view with a copy action. */
public final class StructureTab extends BorderPane {

    private final String titleText;
    private final TextArea area = new TextArea();

    public StructureTab(String titleText, String ddl) {
        this.titleText = titleText;
        area.setEditable(false);
        area.setText(ddl == null ? "" : ddl);
        area.getStyleClass().add("ddl-view");
        area.setWrapText(false);

        Button copy = new Button(I18n.tr("struct.copy"));
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(area.getText());
            Clipboard.getSystemClipboard().setContent(content);
            copy.setText(I18n.tr("struct.copied"));
        });

        HBox bar = new HBox(8, new Label(I18n.tr("struct.hint")), copy);
        bar.setPadding(new Insets(6, 8, 6, 8));
        setTop(bar);
        setCenter(area);
    }

    public String titleText() {
        return titleText;
    }
}
