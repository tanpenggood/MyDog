package com.mydog.ui.editor;

import com.mydog.core.AppContext;
import com.mydog.i18n.I18n;
import com.mydog.sql.QueryResult;
import com.mydog.sql.SqlRunner;
import com.mydog.sql.SqlStatementSplitter;
import com.mydog.sql.highlight.SqlSyntaxStyler;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.util.Duration;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SQL worksheet: RichTextFX editor on top, results/messages below.
 *
 * <p>Highlighting is debounced and paragraph-scoped on purpose — restyling the whole document
 * on every keystroke is what makes naive RichTextFX editors crawl on a 5k-line script.
 */
public final class SqlEditorTab extends BorderPane {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sql-exec");
        t.setDaemon(true);
        return t;
    });

    private final AppContext ctx;
    private final CodeArea codeArea = new CodeArea();
    private final ResultsPanel results = new ResultsPanel();
    private final Label statusLabel = new Label();
    private final Button runBtn = new Button(I18n.tr("editor.run"));
    private final Button runAllBtn = new Button(I18n.tr("editor.runAll"));
    private final Button clearBtn = new Button(I18n.tr("editor.clear"));
    private final PauseTransition restyleDebounce = new PauseTransition(Duration.millis(180));

    private volatile boolean running;

    public SqlEditorTab(AppContext ctx, String initialSql) {
        this.ctx = ctx;
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        codeArea.getStyleClass().add("sql-editor");
        if (initialSql != null && !initialSql.isEmpty()) {
            codeArea.replaceText(0, 0, initialSql);
        }

        restyleDebounce.setOnFinished(e -> restyle());
        codeArea.textProperty().addListener((o, was, now) -> {
            restyleDebounce.stop();
            restyleDebounce.playFromStart();
        });
        restyle();

        runBtn.setOnAction(e -> run(true));
        runAllBtn.setOnAction(e -> run(false));
        clearBtn.setOnAction(e -> {
            results.clear();
            statusLabel.setText("");
        });
        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            boolean ctrl = e.isControlDown() || e.isMetaDown();
            if (ctrl && e.getCode() == KeyCode.ENTER) {
                e.consume();
                run(true);
            }
        });

        HBox toolbar = new HBox(8, runBtn, runAllBtn, clearBtn, statusLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 8, 6, 8));

        SplitPane split = new SplitPane(codeArea, results.node());
        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.setDividerPositions(0.6);
        javafx.application.Platform.runLater(() -> split.setDividerPosition(0, 0.6));

        setTop(toolbar);
        setCenter(split);
    }

    public String titleText() {
        return I18n.tr("tab.editor");
    }

    // ------------------------------------------------------------------ execution

    private void run(boolean useSelection) {
        if (running) return;
        if (ctx.activeProfile() == null) {
            statusLabel.setText(I18n.tr("editor.noConnection"));
            return;
        }
        String scope = codeArea.getText();
        if (useSelection && !codeArea.getSelectedText().isBlank()) {
            scope = codeArea.getSelectedText();
        }
        List<String> statements = SqlStatementSplitter.splitTexts(scope);
        if (statements.isEmpty()) {
            statusLabel.setText(I18n.tr("editor.nothingToRun"));
            return;
        }
        running = true;
        runBtn.setDisable(true);
        runAllBtn.setDisable(true);
        statusLabel.setText(I18n.tr("editor.running", statements.size()));
        results.clear();

        Task<List<QueryResult>> task = new Task<>() {
            @Override protected List<QueryResult> call() throws Exception {
                try (Connection c = ctx.openConnection(ctx.activeProfile())) {
                    return SqlRunner.runScript(c, statements, SqlRunner.MAX_ROWS,
                            r -> Platform.runLater(() -> results.show(r)));
                }
            }
        };
        long startedAt = System.currentTimeMillis();
        task.setOnSucceeded(e -> {
            List<QueryResult> rs = task.getValue();
            long ms = System.currentTimeMillis() - startedAt;
            Platform.runLater(() -> {
                statusLabel.setText(I18n.tr("editor.done", rs.size(), ms));
                done();
            });
        });
        task.setOnFailed(e -> Platform.runLater(() -> {
            statusLabel.setText(I18n.tr("editor.failed", String.valueOf(task.getException().getMessage())));
            done();
        }));
        POOL.submit(task);
    }

    private void done() {
        running = false;
        runBtn.setDisable(false);
        runAllBtn.setDisable(false);
    }

    private void restyle() {
        var paragraphs = codeArea.getParagraphs();
        for (int i = 0; i < paragraphs.size(); i++) {
            codeArea.setStyleSpans(i, SqlSyntaxStyler.spansFor(paragraphs.get(i).getText()));
        }
    }
}
