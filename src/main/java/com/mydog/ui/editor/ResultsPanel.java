package com.mydog.ui.editor;

import com.mydog.i18n.I18n;
import com.mydog.sql.QueryResult;
import com.mydog.util.ValueFormatter;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;

import java.util.List;

/** Bottom half of the SQL editor: a result grid plus a message log, as two tabs. */
public final class ResultsPanel {

    private final TabPane tabs = new TabPane();
    private final TableView<ObservableList<String>> grid = new TableView<>();
    private final TextArea messages = new TextArea();
    private final Label resultTitle = new Label();

    public ResultsPanel() {
        grid.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        grid.setPlaceholder(new Label(I18n.tr("editor.noResult")));

        messages.setEditable(false);
        messages.setWrapText(false);
        messages.getStyleClass().add("message-view");

        Tab resultTab = new Tab(I18n.tr("editor.tab.results"), grid);
        resultTab.setClosable(false);
        Tab messageTab = new Tab(I18n.tr("editor.tab.messages"), messages);
        messageTab.setClosable(false);
        tabs.getTabs().addAll(resultTab, messageTab);
    }

    public javafx.scene.Node node() {
        return tabs;
    }

    public void clear() {
        grid.getColumns().clear();
        grid.setItems(FXCollections.observableArrayList());
        messages.clear();
        resultTitle.setText("");
    }

    /** Show one result set in the grid. Update-count results only add a message line. */
    public void show(QueryResult r) {
        if (r.error() != null) {
            appendMessage(I18n.tr("editor.msg.error", r.sql(), r.error()));
            tabs.getSelectionModel().select(1);
            return;
        }
        if (!r.isResultSet()) {
            appendMessage(I18n.tr("editor.msg.updated", r.affectedRows(), r.millis()));
            return;
        }
        grid.getColumns().clear();
        for (int i = 0; i < r.columns().size(); i++) {
            final int index = i;
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(r.columns().get(i));
            col.setCellValueFactory(cd -> {
                List<String> row = cd.getValue();
                return new ReadOnlyStringWrapper(index < row.size() ? row.get(index) : "");
            });
            grid.getColumns().add(col);
        }
        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        for (List<Object> raw : r.rows()) {
            ObservableList<String> formatted = FXCollections.observableArrayList();
            for (Object cell : raw) formatted.add(ValueFormatter.display(cell));
            rows.add(formatted);
        }
        grid.setItems(rows);
        appendMessage(I18n.tr("editor.msg.rows", r.rowCount(), r.millis()));
        tabs.getSelectionModel().select(0);
    }

    public void appendMessage(String line) {
        messages.appendText(line + System.lineSeparator());
    }

    public void showTiming(long millis) {
        appendMessage(I18n.tr("editor.msg.elapsed", millis));
    }
}
