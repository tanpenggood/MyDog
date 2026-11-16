package com.mydog.ui;

import com.mydog.core.AppContext;
import com.mydog.i18n.I18n;
import com.mydog.metadata.model.TableDef;
import com.mydog.ui.data.DataViewerTab;
import com.mydog.ui.editor.SqlEditorTab;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/**
 * Center area of the main window: every opened editor / data grid / DDL view is a tab here.
 * Tabs are reused by title so double-clicking the same table twice does not pile up copies.
 */
public final class Workspace extends TabPane {

    private final AppContext ctx;

    public Workspace(AppContext ctx) {
        this.ctx = ctx;
        setTabClosingPolicy(TabClosingPolicy.SELECTED_TAB);
        getStyleClass().add("workspace");
    }

    /** Drop everything (called when the selected connection changes). */
    public void closeAll() {
        getTabs().clear();
    }

    /** Query tabs are never reused: each one holds different unsaved SQL. */
    public void openEditorText(String sql) {
        Tab tab = new Tab(I18n.tr("tab.editor") + " " + (++queryCounter), new SqlEditorTab(ctx, sql));
        tab.setClosable(true);
        getTabs().add(tab);
        getSelectionModel().select(tab);
    }

    public void openDataTab(TableDef table) {
        reuseOrAdd(table.name(), DataViewerTab.class, () -> new DataViewerTab(ctx, table));
    }

    public void openStructureTab(String title, String ddl) {
        reuseOrAdd(title + " (DDL)", StructureTab.class, () -> new StructureTab(title, ddl));
    }

    private int queryCounter;

    private void reuseOrAdd(String title, Class<?> contentClass,
                            java.util.function.Supplier<javafx.scene.Node> factory) {
        for (Tab t : getTabs()) {
            if (title.equals(t.getText()) && contentClass.isInstance(t.getContent())) {
                getSelectionModel().select(t);
                return;
            }
        }
        Tab tab = new Tab(title, factory.get());
        tab.setClosable(true);
        getTabs().add(tab);
        getSelectionModel().select(tab);
    }
}
