package com.mydog.ui.data;

import com.mydog.core.AppContext;
import com.mydog.i18n.I18n;
import com.mydog.metadata.model.TableDef;
import com.mydog.util.SqlNames;
import com.mydog.util.ValueFormatter;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Table data grid: server-side paging, sorting and a WHERE box.
 *
 * <p>Read-only on purpose in M2 — MyDog generates SQL instead of mutating cells behind your
 * back, and sorting/paging is pushed to the server because the grid only ever holds one page.
 */
public final class DataViewerTab extends BorderPane {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "data-loader");
        t.setDaemon(true);
        return t;
    });

    private final AppContext ctx;
    private final TableDef table;

    private final TableView<ObservableList<String>> grid = new TableView<>();
    private final TextField whereField = new TextField();
    private final ComboBox<Integer> pageSizeBox = new ComboBox<>();
    private final Label infoLabel = new Label();
    private final Button prevBtn = new Button("<");
    private final Button nextBtn = new Button(">");
    private final Button refreshBtn = new Button(I18n.tr("data.refresh"));

    private int page;                 // 0-based
    private long totalRows = -1;      // -1 = not counted yet
    private String sortColumn;        // null = server natural order
    private boolean sortAscending = true;
    private boolean adjustingSort;    // guards the sort-listener reentry below

    public DataViewerTab(AppContext ctx, TableDef table) {
        this.ctx = ctx;
        this.table = table;

        whereField.setPromptText(I18n.tr("data.whereHint"));
        whereField.setOnAction(e -> reloadFirstPage());
        pageSizeBox.getItems().setAll(100, 500, 1000, 5000);
        pageSizeBox.setValue(100);
        pageSizeBox.valueProperty().addListener((o, was, now) -> reloadFirstPage());
        refreshBtn.setOnAction(e -> reloadFirstPage());
        prevBtn.setOnAction(e -> { if (page > 0) { page--; loadPage(); } });
        nextBtn.setOnAction(e -> { page++; loadPage(); });

        grid.setPlaceholder(new Label(I18n.tr("data.empty")));
        grid.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        HBox toolbar = new HBox(8,
                new Label(I18n.tr("data.where")), whereField,
                new Label(I18n.tr("data.pageSize")), pageSizeBox,
                refreshBtn, prevBtn, nextBtn);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8));
        whereField.setPrefWidth(360);

        infoLabel.setPadding(new Insets(4, 8, 4, 8));
        setTop(toolbar);
        setCenter(grid);
        setBottom(infoLabel);

        installSortHooks();
        reloadFirstPage();
    }

    public String titleText() {
        return table.name();
    }

    // ------------------------------------------------------------------ queries

    private void reloadFirstPage() {
        page = 0;
        totalRows = -1;
        loadPage();
    }

    private String whereClause() {
        String w = whereField.getText().trim();
        return w.isEmpty() ? "" : " WHERE (" + w + ")";
    }

    private void loadPage() {
        int size = pageSizeBox.getValue() == null ? 100 : pageSizeBox.getValue();
        String tableRef = SqlNames.qualified(table.schema(), table.name());
        String where = whereClause();
        String order = sortColumn == null ? ""
                : " ORDER BY " + SqlNames.quote(sortColumn) + " " + (sortAscending ? "ASC" : "DESC");
        String sql = "SELECT * FROM " + tableRef + where + order
                + " LIMIT " + size + " OFFSET " + ((long) page * size);
        infoLabel.setText(I18n.tr("data.loading"));

        Task<Object[]> task = new Task<>() {
            @Override protected Object[] call() throws SQLException {
                List<String> cols = new ArrayList<>();
                List<ObservableList<String>> rows = new ArrayList<>();
                long total = -1;
                try (Connection c = ctx.openConnection(ctx.profileFor(table.schema()));
                     Statement st = c.createStatement()) {
                    try (ResultSet rs = st.executeQuery(sql)) {
                        ResultSetMetaData md = rs.getMetaData();
                        int count = md.getColumnCount();
                        for (int i = 1; i <= count; i++) cols.add(md.getColumnLabel(i));
                        while (rs.next()) {
                            ObservableList<String> row = FXCollections.observableArrayList();
                            for (int i = 1; i <= count; i++) {
                                row.add(ValueFormatter.display(rs.getObject(i)));
                            }
                            rows.add(row);
                        }
                    }
                    try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + tableRef + where)) {
                        if (rs.next()) total = rs.getLong(1);
                    }
                }
                return new Object[]{cols, rows, total};
            }
        };
        task.setOnSucceeded(e -> {
            Object[] v = task.getValue();
            @SuppressWarnings("unchecked") List<String> cols = (List<String>) v[0];
            @SuppressWarnings("unchecked") List<ObservableList<String>> rows =
                    (List<ObservableList<String>>) v[1];
            totalRows = (Long) v[2];
            buildColumns(cols);
            grid.setItems(FXCollections.observableArrayList(rows));
            updateInfo(rows.size(), size);
        });
        task.setOnFailed(e -> {
            Throwable err = task.getException();
            infoLabel.setText(I18n.tr("data.error", err == null ? "?" : String.valueOf(err.getMessage())));
        });
        POOL.submit(task);
    }

    private void buildColumns(List<String> cols) {
        if (grid.getColumns().size() == cols.size()) return;   // same shape: keep sort state
        grid.getColumns().clear();
        for (int i = 0; i < cols.size(); i++) {
            final int index = i;
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(cols.get(i));
            col.setCellValueFactory(cd -> {
                ObservableList<String> row = cd.getValue();
                return new ReadOnlyStringWrapper(index < row.size() ? row.get(index) : "");
            });
            grid.getColumns().add(col);
        }
    }

    /**
     * Header clicks become a server-side ORDER BY instead of an in-memory sort. The sort
     * list is cleared immediately so JavaFX never reorders the single page we hold.
     */
    private void installSortHooks() {
        // getSortOrder() is ObservableList<TableColumnBase<S, ?>>, so the listener is Object-typed
        grid.getSortOrder().addListener((ListChangeListener<Object>) c -> {
            if (adjustingSort || c.getList().isEmpty()) return;
            @SuppressWarnings("unchecked")
            TableColumn<ObservableList<String>, String> col =
                    (TableColumn<ObservableList<String>, String>) c.getList().get(0);
            sortColumn = col.getText();
            sortAscending = col.getSortType() != TableColumn.SortType.DESCENDING;
            adjustingSort = true;
            try {
                c.getList().clear();
                grid.getColumns().forEach(x -> x.setSortType(
                        x == col ? (sortAscending ? TableColumn.SortType.ASCENDING
                                                   : TableColumn.SortType.DESCENDING)
                                 : TableColumn.SortType.ASCENDING));
            } finally {
                adjustingSort = false;
            }
            reloadFirstPage();
        });
    }

    private void updateInfo(int shown, int pageSize) {
        String text = totalRows >= 0
                ? I18n.tr("data.rowsOf", page * pageSize + 1, page * pageSize + shown, totalRows)
                : I18n.tr("data.rows", shown);
        infoLabel.setText(table.schema() + "." + table.name() + "  —  " + text);
        prevBtn.setDisable(page == 0);
        nextBtn.setDisable(shown < pageSize);
    }
}
