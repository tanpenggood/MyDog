package com.mydog.ui.sync;

import com.mydog.connection.ConnectionProfile;
import com.mydog.core.AppContext;
import com.mydog.i18n.I18n;
import com.mydog.sync.DataCounts;
import com.mydog.sync.ObjectKind;
import com.mydog.sync.SchemaComparator;
import com.mydog.sync.SyncAction;
import com.mydog.sync.SyncMode;
import com.mydog.sync.SyncPlan;
import com.mydog.sync.SyncReport;
import com.mydog.sync.SyncScope;

import javafx.beans.property.BooleanProperty;
import javafx.event.ActionEvent;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.io.File;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The five-step sync wizard: endpoints → scope → compare → preview → execute.
 *
 * <p>No step talks to a server directly. Everything blocking goes through {@link SyncWizardModel}
 * as a {@link Task} on its own thread, with the progress bar and the log bound to that task, so the
 * window stays responsive while a large schema is compared and Cancel can reach a transfer in
 * flight.
 *
 * <p>Panes are built once and then cached, which is what lets the combo boxes, the name filter and
 * the table ticks survive pressing Back. The wizard is modal, so a language change applies the next
 * time it is opened rather than mid-flow.
 */
public final class SyncWizard {

    private static final int STEP_ENDPOINTS = 0;
    private static final int STEP_SCOPE = 1;
    private static final int STEP_COMPARE = 2;
    private static final int STEP_PREVIEW = 3;
    private static final int STEP_EXECUTE = 4;
    private static final int STEP_COUNT = 5;

    private static final String[] STEP_KEYS = {
            "sync.step.connections", "sync.step.scope", "sync.step.compare",
            "sync.step.preview", "sync.step.execute",
    };

    private final AppContext ctx;
    private final SyncWizardModel model;
    private final Dialog<Void> dialog = new Dialog<>();
    private final StackPane body = new StackPane();
    private final Label stepTitle = new Label();
    private final Label stepCounter = new Label();
    private final Label statusLabel = new Label();
    private final Node[] panes = new Node[STEP_COUNT];

    private Button backButton;
    private Button nextButton;
    private Button closeButton;

    private ComboBox<ConnectionProfile> sourceConnection;
    private ComboBox<String> sourceSchemaBox;
    private ComboBox<ConnectionProfile> targetConnection;
    private ComboBox<String> targetSchemaBox;
    private ToggleGroup modeGroup;
    private CheckBox dropCheck;
    private TextField chunkField;
    private CheckBox fastCheck;

    private final ObservableList<CandidateRow> candidateRows = FXCollections.observableArrayList();
    private FilteredList<CandidateRow> candidateView;
    private TableView<CandidateRow> candidateTable;
    private CheckBox selectAllCheck;
    private boolean applyingSelectAll;
    private Label selectionSummary;
    private ProgressBar candidatesProgress;
    private boolean candidatesLoaded;

    private ProgressBar taskProgress;
    private Label taskMessage;
    private TextArea compareSummary;
    private Button compareButton;

    private final ObservableList<ActionRow> actionRows = FXCollections.observableArrayList();
    private TableView<ActionRow> previewTable;
    private Label previewSummary;

    private ProgressBar executeProgress;
    private TextArea executeLog;
    private TextArea reportArea;
    private Button runButton;
    private Button cancelButton;

    private int step;
    private boolean compareRequested;
    private SyncScope scope = SyncScope.everything();
    private final String preferredSourceSchema;

    private SyncWizard(AppContext ctx, String preferredSourceSchema) {
        this.ctx = ctx;
        this.model = new SyncWizardModel(ctx);
        this.preferredSourceSchema = preferredSourceSchema;
    }

    public static void show(Window owner, AppContext ctx) {
        show(owner, ctx, null);
    }

    /** @param preferredSourceSchema schema to preselect as the source, when opened from the tree */
    public static void show(Window owner, AppContext ctx, String preferredSourceSchema) {
        open(owner, ctx, preferredSourceSchema).dialog.showAndWait();
    }

    /**
     * Builds the dialog without opening it.
     *
     * <p>{@link #show} blocks in a nested event loop, so a test that wants to press the buttons has
     * to hold the wizard first. That is the only reason this is not inlined into {@code show}.
     */
    static SyncWizard open(Window owner, AppContext ctx, String preferredSourceSchema) {
        SyncWizard wizard = new SyncWizard(ctx, preferredSourceSchema);
        wizard.configure(owner);
        if (ctx.connections().all().isEmpty()) {
            wizard.note(I18n.tr("sync.noConnections"), true);
        }
        wizard.showStep(STEP_ENDPOINTS);
        return wizard;
    }

    void showAndWait() {
        dialog.showAndWait();
    }

    /** The state the steps share: what the plan and the report ended up being. */
    SyncWizardModel model() {
        return model;
    }

    /** The step panes, in order, for a test that walks the scene graph. */
    Node paneOf(int index) {
        return panes[index];
    }

    int currentStep() {
        return step;
    }

    /** The shell, for a test that has to observe the window the nested modal loop runs in. */
    Dialog<Void> dialog() {
        return dialog;
    }

    /** The status line the user reads when a step refuses to advance. */
    String statusText() {
        return statusLabel.getText();
    }

    // ================================================================== shell

    private void configure(Window owner) {
        dialog.initOwner(owner);
        dialog.setTitle(I18n.tr("sync.title"));
        dialog.setResizable(true);
        // DDL cannot be rolled back, but a transfer in flight should not outlive the window.
        dialog.setOnCloseRequest(e -> model.cancel());

        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().setAll(ButtonType.PREVIOUS, ButtonType.NEXT, ButtonType.CLOSE);
        backButton = (Button) pane.lookupButton(ButtonType.PREVIOUS);
        nextButton = (Button) pane.lookupButton(ButtonType.NEXT);
        closeButton = (Button) pane.lookupButton(ButtonType.CLOSE);
        // A JavaFX Dialog closes itself when *any* button in its button bar is pressed — the bar
        // exists to end the dialog, not to navigate inside it. Back and Next therefore take their
        // own action in a filter and consume the event, before that built-in behaviour runs; without
        // this the first Next press would dismiss the wizard instead of advancing it.
        backButton.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            showStep(Math.max(STEP_ENDPOINTS, step - 1));
        });
        nextButton.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            if (advance()) showStep(Math.min(STEP_COUNT - 1, step + 1));
        });
        // Close is left to close the dialog, but a run in flight should not outlive the window.
        closeButton.addEventFilter(ActionEvent.ACTION, e -> model.cancel());

        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(920);
        statusLabel.setMinHeight(Region.USE_PREF_SIZE);
        stepTitle.setStyle("-fx-font-weight: bold;");
        stepCounter.setStyle("-fx-opacity: 0.7;");
        HBox headerRow = new HBox(12, stepTitle, spacer(), stepCounter);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        VBox header = new VBox(4, headerRow, statusLabel);
        header.setPadding(new Insets(12, 14, 8, 14));

        body.setPadding(new Insets(0, 14, 8, 14));
        body.setMinSize(860, 470);
        BorderPane root = new BorderPane(body, header, null, null, null);
        pane.setContent(root);
    }

    /** Validation between steps; false keeps the user where they are, with an explanation. */
    private boolean advance() {
        if (step == STEP_ENDPOINTS) {
            captureEndpoints();
            if (!model.endpointsChosen()) {
                note(I18n.tr("sync.error.pickConnections"), true);
                return false;
            }
            if (model.endpointsIdentical()) {
                note(I18n.tr("sync.error.sameSchema"), true);
                return false;
            }
            compareRequested = false;
            // a different pair of databases means a different object list: step 2 must re-read it
            // rather than offer the previous schemas' tables to tick
            candidatesLoaded = false;
            return true;
        }
        if (step == STEP_SCOPE) {
            scope = scopeFromSelection();
            compareRequested = false;
            return true;
        }
        if (step == STEP_PREVIEW) {
            applyPreviewSelection();
            if (model.plan() == null) {
                note(I18n.tr("sync.preview.empty"), true);
                return false;
            }
        }
        return true;
    }

    private void showStep(int index) {
        step = index;
        backButton.setDisable(index == STEP_ENDPOINTS);
        nextButton.setVisible(index != STEP_EXECUTE);
        nextButton.setManaged(index != STEP_EXECUTE);
        // JavaFX names these buttons from the *operating system* locale; the wizard speaks the
        // language the user picked inside the app, so all three labels are set here
        backButton.setText(I18n.tr("sync.back"));
        nextButton.setText(index == STEP_PREVIEW
                ? I18n.tr("sync.apply") : I18n.tr("sync.next"));
        closeButton.setText(model.report() != null || index == STEP_EXECUTE
                ? I18n.tr("sync.close") : I18n.tr("sync.dismiss"));
        stepTitle.setText(I18n.tr(STEP_KEYS[index]));
        stepCounter.setText(I18n.tr("sync.step.of", index + 1, STEP_COUNT));
        if (panes[index] == null) {
            panes[index] = switch (index) {
                case STEP_ENDPOINTS -> stepEndpoints();
                case STEP_SCOPE -> stepScope();
                case STEP_COMPARE -> stepCompare();
                case STEP_PREVIEW -> stepPreview();
                default -> stepExecute();
            };
        }
        body.getChildren().setAll(panes[index]);
        if (index == STEP_EXECUTE) refreshExecuteControls();
        if (index == STEP_SCOPE && !candidatesLoaded) loadCandidates();
        if (index == STEP_COMPARE && !compareRequested) {
            compareRequested = true;
            runCompare();
        }
    }

    private void note(String text, boolean error) {
        statusLabel.setText(text == null ? "" : text);
        statusLabel.setStyle(error
                ? "-fx-text-fill: #c0392b; -fx-font-weight: bold;"
                : "-fx-text-fill: #1e8e4a;");
        if (text != null && !text.isEmpty() && executeLog != null && step == STEP_EXECUTE) {
            executeLog.appendText(text + "\n");
        }
    }

    // ================================================================== step 1: endpoints

    private Node stepEndpoints() {
        List<ConnectionProfile> profiles = new ArrayList<>(ctx.connections().all());
        sourceConnection = connectionBox(profiles);
        targetConnection = connectionBox(profiles);
        sourceSchemaBox = schemaBox();
        targetSchemaBox = schemaBox();
        sourceConnection.valueProperty().addListener(
                (o, was, now) -> reloadSchemas(sourceConnection, sourceSchemaBox, true));
        targetConnection.valueProperty().addListener(
                (o, was, now) -> reloadSchemas(targetConnection, targetSchemaBox, false));
        reloadSchemas(sourceConnection, sourceSchemaBox, true);
        reloadSchemas(targetConnection, targetSchemaBox, false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(12);
        ColumnConstraints labelColumn = new ColumnConstraints(120);
        ColumnConstraints growFirst = new ColumnConstraints();
        growFirst.setHgrow(Priority.ALWAYS);
        growFirst.setFillWidth(true);
        ColumnConstraints growSecond = new ColumnConstraints();
        growSecond.setHgrow(Priority.ALWAYS);
        growSecond.setFillWidth(true);
        grid.getColumnConstraints().setAll(labelColumn, growFirst, growSecond);
        grid.addRow(0, strong(I18n.tr("sync.source")), sourceConnection, sourceSchemaBox);
        grid.addRow(1, strong(I18n.tr("sync.target")), targetConnection, targetSchemaBox);

        modeGroup = new ToggleGroup();
        RadioButton both = modeRadio(SyncMode.STRUCTURE_AND_DATA, "sync.mode.both");
        HBox modes = new HBox(16,
                modeRadio(SyncMode.STRUCTURE_ONLY, "sync.mode.structure"),
                modeRadio(SyncMode.DATA_ONLY, "sync.mode.data"), both);
        both.setSelected(true);
        grid.addRow(2, strong(I18n.tr("sync.mode")), modes, new Region());

        dropCheck = new CheckBox(I18n.tr("sync.dropExtraneous"));
        dropCheck.setTooltip(new Tooltip(I18n.tr("sync.dropExtraneous.help")));
        grid.addRow(3, strong(I18n.tr("sync.options")), dropCheck, new Region());

        chunkField = new TextField(String.valueOf(model.chunkSize()));
        chunkField.setPrefWidth(110);
        chunkField.setTooltip(new Tooltip(I18n.tr("sync.chunkSize.help")));
        fastCheck = new CheckBox(I18n.tr("sync.fast"));
        fastCheck.setTooltip(new Tooltip(I18n.tr("sync.fast.help")));
        HBox chunkRow = new HBox(10, chunkField, fastCheck);
        chunkRow.setAlignment(Pos.CENTER_LEFT);
        grid.addRow(4, strong(I18n.tr("sync.chunkSize")), chunkRow, new Region());

        return new VBox(14, grid, new Separator(), muted(I18n.tr("sync.endpoints.hint")));
    }

    private Label strong(String text) {
        Label label = new Label(text);
        label.setMinWidth(110);
        return label;
    }

    private Label muted(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMaxWidth(860);
        label.setStyle("-fx-opacity: 0.75;");
        return label;
    }

    private RadioButton modeRadio(SyncMode mode, String key) {
        RadioButton button = new RadioButton(I18n.tr(key));
        button.setUserData(mode);
        button.setToggleGroup(modeGroup);
        return button;
    }

    private ComboBox<ConnectionProfile> connectionBox(List<ConnectionProfile> profiles) {
        ComboBox<ConnectionProfile> box = new ComboBox<>(FXCollections.observableArrayList(profiles));
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(ConnectionProfile profile) {
                return profile == null ? "" : profile.displayLabel();
            }

            @Override
            public ConnectionProfile fromString(String text) {
                return null;
            }
        });
        box.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(ConnectionProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.displayLabel());
            }
        });
        box.setMaxWidth(Double.MAX_VALUE);
        box.setPromptText(I18n.tr("sync.connection"));
        if (profiles.size() == 1) box.getSelectionModel().select(0);
        return box;
    }

    private ComboBox<String> schemaBox() {
        ComboBox<String> box = new ComboBox<>();
        box.setEditable(true);
        box.setPromptText(I18n.tr("sync.database"));
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    /**
     * Fills a schema combo for the currently selected profile.
     *
     * <p>A preselected profile never fires the value listener, so this runs once directly as well as
     * from it.
     */
    private void reloadSchemas(ComboBox<ConnectionProfile> connection,
                               ComboBox<String> schemas, boolean isSource) {
        ConnectionProfile profile = connection.getValue();
        if (profile == null) return;
        Task<List<String>> task = model.schemasTask(profile);
        task.setOnSucceeded(e -> {
            List<String> names = new ArrayList<>(task.getValue());
            if (names.isEmpty() && !profile.defaultSchema().isEmpty()) {
                names.add(profile.defaultSchema());
            }
            schemas.getItems().setAll(names);
            String preferred = isSource ? preferredSourceSchema : null;
            if (preferred != null && names.contains(preferred)) {
                schemas.setValue(preferred);
            } else if (names.contains(profile.defaultSchema())) {
                schemas.setValue(profile.defaultSchema());
            } else if (!names.isEmpty()) {
                schemas.setValue(names.get(0));
            }
        });
        task.setOnFailed(e -> {
            if (!profile.defaultSchema().isEmpty()) {
                schemas.getItems().setAll(profile.defaultSchema());
                schemas.setValue(profile.defaultSchema());
            }
            note(I18n.tr("sync.error.schemaList", rootMessage(task.getException())), true);
        });
        start(task);
    }

    /** Copies the step 1 controls into the model, before anything reads the endpoints. */
    private void captureEndpoints() {
        if (sourceConnection == null) return;
        model.setSource(sourceConnection.getValue(), trim(sourceSchemaBox.getValue()));
        model.setTarget(targetConnection.getValue(), trim(targetSchemaBox.getValue()));
        Object chosen = modeGroup == null || modeGroup.getSelectedToggle() == null
                ? null : modeGroup.getSelectedToggle().getUserData();
        model.setMode(chosen instanceof SyncMode mode ? mode : SyncMode.STRUCTURE_AND_DATA);
        model.setDropExtraneous(dropCheck.isSelected());
        model.setFastDataMode(fastCheck.isSelected());
        try {
            model.setChunkSize(Integer.parseInt(chunkField.getText().trim()));
        } catch (NumberFormatException ignored) {
            // SyncOptions clamps the value, so an unparsable field just keeps the previous one
        }
    }

    private static String trim(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    // ================================================================== step 2: scope

    private Node stepScope() {
        TextField filter = new TextField();
        filter.setPromptText(I18n.tr("sync.filter"));
        filter.setPrefColumnCount(26);
        selectAllCheck = new CheckBox(I18n.tr("sync.selectAll"));
        selectAllCheck.setSelected(true);
        selectionSummary = new Label();
        candidatesProgress = new ProgressBar(-1);
        candidatesProgress.setPrefWidth(160);

        HBox toolbar = new HBox(12, filter, selectAllCheck, candidatesProgress,
                spacer(), selectionSummary);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        candidateView = new FilteredList<>(candidateRows, r -> true);
        filter.textProperty().addListener((o, was, now) -> {
            String needle = now == null ? "" : now.trim().toLowerCase();
            candidateView.setPredicate(needle.isEmpty() ? r -> true
                    : r -> r.name.toLowerCase().contains(needle));
            // the filter only changes what is on screen: re-applying "select all" here would tick
            // rows the user deliberately un-ticked before filtering, and for a target-only object
            // that turns a search into a scheduled DELETE
            updateSelectionSummary();
        });
        selectAllCheck.selectedProperty().addListener((o, was, now) -> {
            if (applyingSelectAll) return;          // programmatic sync, not a click
            applyingSelectAll = true;
            try {
                for (CandidateRow row : candidateView) row.selected.set(now);
            } finally {
                applyingSelectAll = false;
            }
        });
        candidateRows.addListener((ListChangeListener<CandidateRow>) change -> {
            while (change.next()) {
                if (!change.wasAdded()) continue;
                for (CandidateRow row : change.getAddedSubList()) {
                    row.selected.addListener((o, was, now) -> updateSelectionSummary());
                }
            }
            updateSelectionSummary();
        });

        candidateTable = new TableView<>(candidateView);
        candidateTable.setEditable(true);
        candidateTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        candidateTable.setPlaceholder(new Label(I18n.tr("sync.scope.loading")));

        TableColumn<CandidateRow, Boolean> picked = new TableColumn<>(I18n.tr("sync.selected"));
        picked.setCellValueFactory(cell -> cell.getValue().selected);
        picked.setCellFactory(col -> new CheckBoxTableCell<>());
        picked.setSortable(false);
        picked.setMaxWidth(60);

        TableColumn<CandidateRow, String> kind = textColumn(I18n.tr("sync.kind"),
                row -> I18n.tr(row.kind.messageKey()));
        kind.setMaxWidth(130);
        TableColumn<CandidateRow, String> name = textColumn(I18n.tr("sync.object"), row -> row.name);
        TableColumn<CandidateRow, String> inSource = textColumn(I18n.tr("sync.inSource"),
                row -> row.inSource ? "✓" : "—");
        inSource.setMaxWidth(100);
        TableColumn<CandidateRow, String> inTarget = textColumn(I18n.tr("sync.inTarget"),
                row -> row.inTarget ? "✓" : "—");
        inTarget.setMaxWidth(100);
        candidateTable.getColumns().setAll(List.of(picked, kind, name, inSource, inTarget));
        candidateTable.getSortOrder().add(kind);
        updateSelectionSummary();

        return new BorderPane(candidateTable, toolbar, null,
                muted(I18n.tr("sync.scope.hint")), null);
    }

    private <T> TableColumn<T, String> textColumn(String title, Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }

    private Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private void loadCandidates() {
        captureEndpoints();
        candidateRows.clear();
        candidatesProgress.setVisible(true);
        candidatesProgress.setManaged(true);
        Task<List<SchemaComparator.Candidate>> task = model.candidatesTask();
        task.setOnSucceeded(e -> {
            candidatesLoaded = true;
            candidatesProgress.setVisible(false);
            candidatesProgress.setManaged(false);
            candidateRows.setAll(task.getValue().stream().map(CandidateRow::new).toList());
            // deliberately not selectAllCheck.setSelected(true): that would fire its listener and
            // tick the target-only rows too, whose sync action is a DELETE
        });
        task.setOnFailed(e -> {
            candidatesProgress.setVisible(false);
            candidatesProgress.setManaged(false);
            candidateTable.setPlaceholder(new Label(
                    I18n.tr("sync.error.compareFailed", rootMessage(task.getException()))));
            note(I18n.tr("sync.error.compareFailed", rootMessage(task.getException())), true);
        });
        start(task);
    }

    /**
     * The "N of M selected" line, and the select-all box itself.
     *
     * <p>Select-all follows the rows rather than driving them only one way: once any row is
     * un-ticked the box must stop claiming that everything is included, otherwise the one control
     * the user trusts most is the one that lies.
     */
    private void updateSelectionSummary() {
        if (selectionSummary == null) return;
        int picked = 0;
        for (CandidateRow row : candidateRows) {
            if (row.selected.get()) picked++;
        }
        selectionSummary.setText(I18n.tr("sync.selectedOf", picked, candidateRows.size()));
        if (!applyingSelectAll && candidateView != null) {
            int visible = candidateView.size();
            int visiblePicked = 0;
            for (CandidateRow row : candidateView) {
                if (row.selected.get()) visiblePicked++;
            }
            applyingSelectAll = true;
            try {
                // what the box would do if clicked now: everything on screen, and only that
                selectAllCheck.setSelected(visible > 0 && visiblePicked == visible);
            } finally {
                applyingSelectAll = false;
            }
        }
    }

    /**
     * The ticked names, per kind.
     *
     * <p>A kind whose every candidate is ticked collapses to the empty set — the engine's "all
     * objects of this kind" — so a table created on the source after this list was read is still
     * picked up by the comparison.
     */
    private SyncScope scopeFromSelection() {
        Map<ObjectKind, Set<String>> every = new EnumMap<>(ObjectKind.class);
        Map<ObjectKind, Set<String>> chosen = new EnumMap<>(ObjectKind.class);
        for (ObjectKind kind : ObjectKind.values()) {
            every.put(kind, new HashSet<>());
            chosen.put(kind, new HashSet<>());
        }
        for (CandidateRow row : candidateRows) {
            every.get(row.kind).add(row.name);
            if (row.selected.get()) chosen.get(row.kind).add(row.name);
        }
        return new SyncScope(names(ObjectKind.TABLE, every, chosen),
                names(ObjectKind.VIEW, every, chosen),
                names(ObjectKind.PROCEDURE, every, chosen),
                names(ObjectKind.FUNCTION, every, chosen),
                names(ObjectKind.TRIGGER, every, chosen));
    }

    private static Set<String> names(ObjectKind kind, Map<ObjectKind, Set<String>> every,
                                     Map<ObjectKind, Set<String>> chosen) {
        return every.get(kind).equals(chosen.get(kind)) ? Set.of() : chosen.get(kind);
    }

    // ================================================================== step 3: compare

    private Node stepCompare() {
        compareButton = new Button(I18n.tr("sync.compare.run"));
        compareButton.setOnAction(e -> runCompare());
        taskProgress = new ProgressBar(0);
        taskProgress.setPrefWidth(360);
        taskMessage = new Label();
        compareSummary = new TextArea();
        compareSummary.setEditable(false);
        compareSummary.setWrapText(false);
        compareSummary.setStyle("-fx-font-family: monospace;");
        VBox.setVgrow(compareSummary, Priority.ALWAYS);

        HBox controls = new HBox(12, compareButton, taskProgress, spacer(), taskMessage);
        controls.setAlignment(Pos.CENTER_LEFT);
        return new BorderPane(compareSummary, controls, null,
                muted(I18n.tr("sync.compare.hint")), null);
    }

    private void runCompare() {
        captureEndpoints();
        if (!model.endpointsChosen()) {
            note(I18n.tr("sync.error.pickConnections"), true);
            return;
        }
        compareButton.setDisable(true);
        compareSummary.clear();
        model.beginRun();
        Task<SyncPlan> task = model.compareTask(scope);
        taskProgress.progressProperty().bind(task.progressProperty());
        taskMessage.textProperty().bind(task.messageProperty());
        task.valueProperty().addListener((o, was, plan) -> onCompared(plan));
        task.setOnFailed(e -> {
            compareButton.setDisable(false);
            note(I18n.tr("sync.error.compareFailed", rootMessage(task.getException())), true);
        });
        task.setOnCancelled(e -> compareButton.setDisable(false));
        start(task);
    }

    private void onCompared(SyncPlan plan) {
        compareButton.setDisable(false);
        if (plan == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append(plan.sourceLabel()).append("  ->  ").append(plan.targetLabel());
        sb.append('\n').append(I18n.tr("sync.compare.done", plan.selectedActions().size(),
                plan.ddlCount(), plan.dataActionCount()));
        if (!plan.warnings().isEmpty()) {
            sb.append("\n\n").append(I18n.tr("sync.warnings")).append(":\n");
            for (String warning : plan.warnings()) sb.append("  ! ").append(warning).append('\n');
        }
        if (!plan.actions().isEmpty()) {
            sb.append("\n").append(I18n.tr("sync.preview")).append(":\n");
            for (SyncAction action : plan.actions()) {
                sb.append(String.format("  %-7s %-32s %s%n", action.kind(),
                        action.object().qualified(),
                        action.detail() == null ? "" : action.detail()));
            }
        }
        compareSummary.setText(sb.toString());
        note(I18n.tr(plan.isEmpty() ? "sync.compare.none" : "sync.compare.ready"), plan.isEmpty());
        buildPreviewRows(plan);
        // the model dropped the previous run's report when this plan replaced it
        refreshExecuteControls();
    }

    // ================================================================== step 4: preview

    private Node stepPreview() {
        Button export = new Button(I18n.tr("sync.exportScript"));
        export.setOnAction(e -> exportScript());
        previewSummary = new Label();
        HBox top = new HBox(12, export, spacer(), previewSummary);
        top.setAlignment(Pos.CENTER_LEFT);

        previewTable = new TableView<>(actionRows);
        previewTable.setEditable(true);
        previewTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        previewTable.setPlaceholder(new Label(I18n.tr("sync.preview.empty")));

        TableColumn<ActionRow, Boolean> picked = new TableColumn<>(I18n.tr("sync.apply"));
        picked.setCellValueFactory(cell -> cell.getValue().selected);
        picked.setCellFactory(col -> new CheckBoxTableCell<>());
        picked.setSortable(false);
        picked.setMaxWidth(80);

        TableColumn<ActionRow, String> kind = textColumn(I18n.tr("sync.kind"),
                row -> I18n.tr("sync.kind." + row.action.kind().name().toLowerCase()));
        kind.setMaxWidth(90);
        TableColumn<ActionRow, String> object = textColumn(I18n.tr("sync.object"),
                row -> row.action.object().qualified());
        TableColumn<ActionRow, String> detail = textColumn(I18n.tr("sync.detail"),
                row -> row.action.detail() == null ? "" : row.action.detail());
        TableColumn<ActionRow, String> statements = textColumn(I18n.tr("sync.stmtCount"),
                row -> row.action.statements().isEmpty() ? ""
                        : String.valueOf(row.action.statements().size()));
        statements.setMaxWidth(80);
        previewTable.getColumns().setAll(List.of(picked, kind, object, detail, statements));
        actionRows.addListener((ListChangeListener<ActionRow>) change -> updatePreviewSummary());
        updatePreviewSummary();

        return new BorderPane(previewTable, top, null, muted(I18n.tr("sync.preview.hint")), null);
    }

    private void buildPreviewRows(SyncPlan plan) {
        List<ActionRow> rows = new ArrayList<>();
        for (SyncAction action : plan.actions()) {
            rows.add(new ActionRow(action));
        }
        actionRows.setAll(rows);
        updatePreviewSummary();
    }

    private void updatePreviewSummary() {
        if (previewSummary == null) return;
        previewSummary.setText(I18n.tr("sync.preview.changes", countSelected(), actionRows.size()));
    }

    private int countSelected() {
        int count = 0;
        for (ActionRow row : actionRows) {
            if (row.selected.get()) count++;
        }
        return count;
    }

    private void applyPreviewSelection() {
        for (ActionRow row : actionRows) {
            row.action.setSelected(row.selected.get());
        }
    }

    private void exportScript() {
        applyPreviewSelection();
        if (model.plan() == null) {
            note(I18n.tr("sync.preview.empty"), true);
            return;
        }
        File file = chooseFile(I18n.tr("sync.exportScript"),
                "mydog-" + model.sourceSchema() + "-to-" + model.targetSchema()
                        + "-" + stamp() + ".sql", "*.sql");
        if (file == null) return;
        note(I18n.tr("sync.export.writing"), false);
        Task<Path> task = model.exportTask(file.toPath());
        task.setOnSucceeded(e -> note(I18n.tr("sync.exported", task.getValue()), false));
        task.setOnFailed(e -> note(I18n.tr("sync.error.exportFailed",
                rootMessage(task.getException())), true));
        start(task);
    }

    // ================================================================== step 5: execute

    private Node stepExecute() {
        runButton = new Button(I18n.tr("sync.execute.run"));
        runButton.setOnAction(e -> runExecute());
        runButton.setDisable(model.plan() == null || model.plan().selectedActions().isEmpty());
        cancelButton = new Button(I18n.tr("sync.execute.cancel"));
        cancelButton.setDisable(true);
        cancelButton.setOnAction(e -> {
            model.cancel();
            cancelButton.setDisable(true);
            note(I18n.tr("sync.execute.cancelling"), false);
        });
        executeProgress = new ProgressBar(0);
        executeProgress.setPrefWidth(320);
        Button exportReport = new Button(I18n.tr("sync.report.export"));
        exportReport.setOnAction(e -> exportReport());

        executeLog = new TextArea();
        executeLog.setEditable(false);
        executeLog.setWrapText(false);
        executeLog.setPromptText(I18n.tr("sync.execute.log.hint"));
        executeLog.setStyle("-fx-font-family: monospace;");
        reportArea = new TextArea();
        reportArea.setEditable(false);
        reportArea.setWrapText(false);
        reportArea.setPromptText(I18n.tr("sync.report.hint"));
        reportArea.setStyle("-fx-font-family: monospace;");
        if (model.report() != null) reportArea.setText(model.reportText());

        SplitPane split = new SplitPane(labeled(I18n.tr("sync.execute.log"), executeLog),
                labeled(I18n.tr("sync.report.title"), reportArea));
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.5);

        HBox controls = new HBox(12, runButton, cancelButton, executeProgress, spacer(),
                exportReport);
        controls.setAlignment(Pos.CENTER_LEFT);
        return new BorderPane(split, controls, null, null, null);
    }

    private VBox labeled(String title, Node content) {
        VBox box = new VBox(4, new Label(title), content);
        VBox.setVgrow(content, Priority.ALWAYS);
        return box;
    }

    /**
     * Step 5 shows what steps 3 and 4 produced, and a trip through Back can change both — most
     * obviously a re-comparison, which invalidates the report of the run before it.
     */
    private void refreshExecuteControls() {
        if (runButton == null) return;
        runButton.setDisable(model.plan() == null || model.plan().selectedActions().isEmpty());
        reportArea.setText(model.report() == null ? "" : model.reportText());
    }

    private void runExecute() {
        applyPreviewSelection();
        if (model.plan() == null || model.plan().selectedActions().isEmpty()) {
            note(I18n.tr("sync.nothingSelected"), true);
            return;
        }
        runButton.setDisable(true);
        cancelButton.setDisable(false);
        executeLog.clear();
        reportArea.clear();
        model.beginRun();
        Task<SyncReport> task = model.executeTask();
        executeProgress.progressProperty().bind(task.progressProperty());
        task.messageProperty().addListener((o, was, now) -> {
            if (now != null && !now.isEmpty()) executeLog.appendText(now + "\n");
        });
        task.valueProperty().addListener((o, was, report) -> onExecuted(report));
        task.setOnFailed(e -> {
            runButton.setDisable(false);
            cancelButton.setDisable(true);
            note(I18n.tr("sync.error.executeFailed", rootMessage(task.getException())), true);
        });
        start(task);
    }

    private void onExecuted(SyncReport report) {
        cancelButton.setDisable(true);
        if (report == null) return;
        refreshExecuteControls();
        DataCounts totals = report.totals();
        if (report.hasErrors()) {
            note(I18n.tr("sync.execute.withErrors", report.errors().size()), true);
        } else if (report.cancelled()) {
            note(I18n.tr("sync.execute.cancelled"), true);
        } else {
            note(I18n.tr("sync.execute.done", totals.inserts(), totals.updates(),
                    totals.deletes()), false);
        }
        closeButton.setText(I18n.tr("sync.close"));
    }

    private void exportReport() {
        if (model.report() == null) {
            note(I18n.tr("sync.report.hint"), true);
            return;
        }
        File file = chooseFile(I18n.tr("sync.report.export"),
                "mydog-sync-report-" + stamp() + ".txt", "*.txt");
        if (file == null) return;
        Task<Path> task = model.exportReportTask(file.toPath());
        task.setOnSucceeded(e -> note(I18n.tr("sync.exported", task.getValue()), false));
        task.setOnFailed(e -> note(I18n.tr("sync.error.exportFailed",
                rootMessage(task.getException())), true));
        start(task);
    }

    // ================================================================== plumbing

    private void start(Task<?> task) {
        Thread thread = new Thread(task, "mydog-sync-task");
        thread.setDaemon(true);
        thread.start();
    }

    private File chooseFile(String title, String fileName, String extension) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.setInitialFileName(fileName);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(title, extension));
        // the wizard's own window, so the chooser stays in front of it and never orphaned
        Window owner = dialog.getDialogPane().getScene().getWindow();
        return chooser.showSaveDialog(owner);
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    }

    /** JDBC buries the useful message several causes deep; show that one, not a SQLState. */
    private static String rootMessage(Throwable error) {
        if (error == null) return "?";
        Throwable cursor = error;
        String message = cursor.getMessage();
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
            if (cursor.getMessage() != null && !cursor.getMessage().isEmpty()) {
                message = cursor.getMessage();
            }
        }
        return message == null || message.isEmpty() ? cursor.getClass().getSimpleName() : message;
    }

    // ================================================================== row wrappers

    /**
     * A candidate with an observable tick box.
     *
     * <p>The engine's records stay free of JavaFX types, so the checkbox state lives here: the cell
     * editor mutates this property, and the wizard reads it back when leaving the step.
     */
    private static final class CandidateRow {
        private final String name;
        private final ObjectKind kind;
        private final boolean inSource;
        private final boolean inTarget;
        private final BooleanProperty selected;

        CandidateRow(SchemaComparator.Candidate candidate) {
            this.name = candidate.ref().name();
            this.kind = candidate.ref().kind();
            this.inSource = candidate.inSource();
            this.inTarget = candidate.inTarget();
            // target-only objects start unticked: syncing them means deleting them
            this.selected = new SimpleBooleanProperty(candidate.inSource());
        }

        @Override
        public String toString() {
            return kind + " " + name;
        }
    }

    private static final class ActionRow {
        private final SyncAction action;
        private final BooleanProperty selected;

        ActionRow(SyncAction action) {
            this.action = action;
            this.selected = new SimpleBooleanProperty(action.isSelected());
        }

        @Override
        public String toString() {
            return action.object().kind() + " " + action.object().name() + " (" + action.kind() + ")";
        }
    }
}
