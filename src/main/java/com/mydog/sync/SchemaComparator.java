package com.mydog.sync;

import com.mydog.metadata.DdlNormalizer;
import com.mydog.metadata.MySqlMetadataReader;
import com.mydog.metadata.model.RoutineDef;
import com.mydog.metadata.model.TableDef;
import com.mydog.metadata.model.TriggerDef;
import com.mydog.metadata.model.ViewDef;
import com.mydog.util.SqlNames;

import java.sql.Connection;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the list of changes that would turn the target schema into a copy of the source one.
 *
 * <p>The plan it produces is descriptive: it reads both sides and decides what each object needs,
 * but nothing here writes to the target. Structure differences become DDL statements held in the
 * plan; row differences become {@link DataProducer}s that are only run when the user executes or
 * exports, so a comparison of a large schema stays inside a second or two and a bounded memory
 * budget.
 *
 * <p>Applied order is the order the engine needs, not the order objects happen to be listed in:
 * tables in foreign-key dependency order, then their data, then views and routines, and triggers
 * last so a target-side trigger cannot fire while rows are being copied.
 */
public final class SchemaComparator {

    /** One object seen during {@link #candidates()}, with the sides it exists on. */
    public record Candidate(ObjectRef ref, boolean inSource, boolean inTarget) {
    }

    private static final java.util.regex.Pattern WHITESPACE_RUNS =
            java.util.regex.Pattern.compile("\\s+");

    /** Above this, COUNT(*) stops being cheap, so the preview falls back to the estimate. */
    private static final long EXACT_COUNT_LIMIT = 1_000_000;

    private final ConnectionFactory sourceFactory;
    private final ConnectionFactory targetFactory;
    private final String sourceSchema;
    private final String targetSchema;
    private final SyncOptions options;
    private final Cancellation cancelled;

    /**
     * @param cancelled shared with the executor and the UI task, so one cancel press stops the
     *                  comparison, a running export and row paging inside a producer
     */
    public SchemaComparator(ConnectionFactory sourceFactory, ConnectionFactory targetFactory,
                           String sourceSchema, String targetSchema,
                           SyncOptions options, Cancellation cancelled) {
        this.sourceFactory = sourceFactory;
        this.targetFactory = targetFactory;
        this.sourceSchema = sourceSchema;
        this.targetSchema = targetSchema;
        this.options = options;
        this.cancelled = cancelled;
    }

    // ------------------------------------------------------------------ scope listing

    /**
     * Every object name on either side, for the wizard's scope step.
     *
     * <p>Target-only names are included because {@code dropExtraneous} operates on them; without
     * them a user who wants to clean up a target could not pick what to drop.
     */
    public List<Candidate> candidates() throws SQLException {
        List<Candidate> out = new ArrayList<>();
        try (Connection src = sourceFactory.open(); Connection dst = targetFactory.open()) {
            MySqlMetadataReader sr = new MySqlMetadataReader(src);
            MySqlMetadataReader tr = new MySqlMetadataReader(dst);

            List<String> srcTables = sr.listTableNames(sourceSchema);
            List<String> dstTables = tr.listTableNames(targetSchema);
            for (String name : union(srcTables, dstTables)) {
                out.add(new Candidate(new ObjectRef(ObjectKind.TABLE, targetSchema, name),
                        containsIgnoreCase(srcTables, name), containsIgnoreCase(dstTables, name)));
            }
            List<String> srcViews = names(sr.listViews(sourceSchema), ViewDef::name);
            List<String> dstViews = names(tr.listViews(targetSchema), ViewDef::name);
            for (String name : union(srcViews, dstViews)) {
                out.add(new Candidate(new ObjectRef(ObjectKind.VIEW, targetSchema, name),
                        containsIgnoreCase(srcViews, name), containsIgnoreCase(dstViews, name)));
            }
            addRoutineCandidates(out, sr.listRoutines(sourceSchema), tr.listRoutines(targetSchema));

            List<String> srcTriggers = names(sr.listTriggers(sourceSchema), TriggerDef::name);
            List<String> dstTriggers = names(tr.listTriggers(targetSchema), TriggerDef::name);
            for (String name : union(srcTriggers, dstTriggers)) {
                out.add(new Candidate(new ObjectRef(ObjectKind.TRIGGER, targetSchema, name),
                        containsIgnoreCase(srcTriggers, name), containsIgnoreCase(dstTriggers, name)));
            }
        }
        return out;
    }

    private void addRoutineCandidates(List<Candidate> out, List<RoutineDef> src, List<RoutineDef> dst) {
        List<String> srcProcedures = new ArrayList<>();
        List<String> srcFunctions = new ArrayList<>();
        split(src, srcProcedures, srcFunctions);
        List<String> dstProcedures = new ArrayList<>();
        List<String> dstFunctions = new ArrayList<>();
        split(dst, dstProcedures, dstFunctions);
        for (String name : union(srcProcedures, dstProcedures)) {
            out.add(new Candidate(new ObjectRef(ObjectKind.PROCEDURE, targetSchema, name),
                    containsIgnoreCase(srcProcedures, name), containsIgnoreCase(dstProcedures, name)));
        }
        for (String name : union(srcFunctions, dstFunctions)) {
            out.add(new Candidate(new ObjectRef(ObjectKind.FUNCTION, targetSchema, name),
                    containsIgnoreCase(srcFunctions, name), containsIgnoreCase(dstFunctions, name)));
        }
    }

    private static void split(List<RoutineDef> routines, List<String> procedures, List<String> functions) {
        for (RoutineDef r : routines) {
            (r.kind() == RoutineDef.Kind.PROCEDURE ? procedures : functions).add(r.name());
        }
    }

    // ------------------------------------------------------------------ comparison

    public SyncPlan compare(SyncScope scope) throws SQLException {
        List<SyncAction> actions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try (Connection src = sourceFactory.open(); Connection dst = targetFactory.open()) {
            MySqlMetadataReader sr = new MySqlMetadataReader(src);
            MySqlMetadataReader tr = new MySqlMetadataReader(dst);
            DdlNormalizer.configureDefaultCollations(sr.defaultCollations());

            compareTables(sr, tr, src, dst, scope, actions, warnings);
            if (options.wantsStructure()) {
                TableContext tables = tableContext(sr, tr, actions);
                compareViews(sr, tr, scope, tables, actions, warnings);
                compareRoutines(sr, tr, scope, actions, warnings);
                compareTriggers(sr, tr, scope, tables, actions, warnings);
            }
        }
        return new SyncPlan(sourceSchema, targetSchema, actions, warnings);
    }

    // ------------------------------------------------------------------ tables

    private void compareTables(MySqlMetadataReader sr, MySqlMetadataReader tr,
                               Connection src, Connection dst, SyncScope scope,
                               List<SyncAction> actions, List<String> warnings) throws SQLException {
        List<String> srcNames = sr.listTableNames(sourceSchema);
        List<String> dstNames = tr.listTableNames(targetSchema);

        List<TableDef> selected = new ArrayList<>();
        for (String name : srcNames) {
            if (!scope.includes(ObjectKind.TABLE, name)) continue;
            selected.add(sr.readTable(sourceSchema, name));
        }
        if (options.wantsStructure()) {
            selected = TableOrdering.orderByDependency(selected);
        }

        for (TableDef source : selected) {
            TableDef target = containsIgnoreCase(dstNames, source.name())
                    ? tr.readTable(targetSchema, source.name()) : null;
            ObjectRef ref = new ObjectRef(ObjectKind.TABLE, targetSchema, source.name());
            if (options.wantsStructure()) {
                tableStructure(source, target, ref, actions, warnings);
            }
            if (options.wantsData()) {
                tableData(source, target, ref, src, dst, actions, warnings);
            }
        }

        if (options.dropExtraneous() && options.wantsStructure()) {
            for (String name : dstNames) {
                if (containsIgnoreCase(srcNames, name) || !scope.includes(ObjectKind.TABLE, name)) continue;
                actions.add(SyncAction.ddl(new ObjectRef(ObjectKind.TABLE, targetSchema, name),
                        SyncAction.Kind.DROP, List.of("DROP TABLE IF EXISTS " + target(name)),
                        "exists only on target"));
                warnings.add("DROP TABLE " + targetSchema + "." + name + " removes its rows as well");
            }
        }
    }

    private void tableStructure(TableDef source, TableDef target, ObjectRef ref,
                                List<SyncAction> actions, List<String> warnings) {
        if (target == null) {
            // the raw SHOW CREATE TABLE, not the normalized text: normalization is a comparison key
            // (line-based, whitespace-collapsed) and the table form has no portability problem to fix
            actions.add(SyncAction.ddl(ref, SyncAction.Kind.CREATE,
                    List.of(DdlRewriter.retargetSchema(source.createStatement(),
                            sourceSchema, targetSchema)), "table missing on target"));
            return;
        }
        if (DdlNormalizer.equivalent(source.createStatement(), target.createStatement())) return;

        List<String> alter = TableDiffer.alterStatements(source, target);
        if (alter.isEmpty()) {
            // normalization said the definitions differ but no column, index or charset clause
            // explains it — usually a table comment or ROW_FORMAT. Say so instead of pretending
            // the object is in sync.
            warnings.add(ref + ": definitions differ in table options only (no ALTER derived)");
            return;
        }
        for (String sql : alter) {
            String upper = sql.toUpperCase(Locale.ROOT);
            if (upper.contains("DROP COLUMN")) {
                warnings.add(ref + ": DROP COLUMN discards the data in that column");
            }
            if (upper.contains("DROP PRIMARY KEY")) {
                warnings.add(ref + ": the target primary key will be replaced");
            }
            if (upper.contains("CONVERT TO CHARACTER SET")) {
                warnings.add(ref + ": CONVERT TO CHARACTER SET rebuilds the table and may widen columns");
            }
        }
        actions.add(SyncAction.ddl(ref, SyncAction.Kind.ALTER, alter, "structure differs"));
    }

    private void tableData(TableDef source, TableDef target, ObjectRef ref,
                           Connection src, Connection dst,
                           List<SyncAction> actions, List<String> warnings) throws SQLException {
        if (target == null && !options.wantsStructure()) {
            // copying rows into a table that does not exist is an error the user can avoid early
            actions.add(SyncAction.skipped(ref, "table missing on target — sync the structure first"));
            return;
        }
        List<String> sourceKey = source.rowKeyColumns();
        List<String> targetKey = target == null ? List.of() : target.rowKeyColumns();

        if (sourceKey.isEmpty() || (target != null && targetKey.isEmpty())) {
            keylessTable(source, target, ref, src, dst, actions, warnings);
            return;
        }

        String detail = rowCountSummary(src, dst, source, target);
        if (target != null && !options.fastDataMode() && rowsAlreadyEqual(src, dst, source, target)) {
            actions.add(SyncAction.skipped(ref, "rows already equal" + detail.substring(detail.indexOf(" "))));
            return;
        }
        ModeChoice mode = chooseMode(target);
        TableDataProducer producer = new TableDataProducer(sourceFactory, targetFactory,
                source, target == null ? sourceInTarget(source) : target,
                options.chunkSize(), mode.producerMode(), cancelled.supplier());
        actions.add(SyncAction.data(ref, List.of(), producer, detail, mode.upsert()));
    }

    /**
     * Count plus {@code CHECKSUM TABLE} on both sides: equal on both means no row change is planned.
     *
     * <p>A checksum is a CRC32 fold over the rows, so a collision is possible in principle; the
     * alternative is reading every row of every table on every comparison, which no real schema
     * affords. The skip text names what was checked so the UI can say it honestly, and a checksum
     * is not taken past {@link #EXACT_COUNT_LIMIT} rows — there the plan keeps the data action and
     * the diff decides.
     */
    private boolean rowsAlreadyEqual(Connection src, Connection dst, TableDef source, TableDef target)
            throws SQLException {
        long sourceRows = cheapCount(src, source);
        long targetRows = cheapCount(dst, target);
        if (sourceRows != targetRows || sourceRows >= EXACT_COUNT_LIMIT) return false;
        String sourceChecksum = JdbcRowReader.checksum(src, source);
        String targetChecksum = JdbcRowReader.checksum(dst, target);
        return sourceChecksum != null && sourceChecksum.equals(targetChecksum);
    }

    private ModeChoice chooseMode(TableDef target) {
        if (target == null) return new ModeChoice(TableDataProducer.Mode.COPY, false);
        if (options.fastDataMode()) return new ModeChoice(TableDataProducer.Mode.COPY, true);
        return new ModeChoice(TableDataProducer.Mode.DIFF, false);
    }

    private record ModeChoice(TableDataProducer.Mode producerMode, boolean upsert) {
    }

    /**
     * A table with neither a primary key nor a NOT NULL unique key has no row identity, so a
     * keyset merge is impossible — and paging by row position would drift the moment the table is
     * written to. The fallback is a reload: delete the target rows and copy the source, skipped
     * entirely when count and server-side checksum already agree.
     */
    private void keylessTable(TableDef source, TableDef target, ObjectRef ref,
                              Connection src, Connection dst,
                              List<SyncAction> actions, List<String> warnings) throws SQLException {
        long sourceRows = JdbcRowReader.count(src, source);
        if (target != null) {
            TableDef targetInPlace = target;
            long targetRows = JdbcRowReader.count(dst, targetInPlace);
            String sourceChecksum = JdbcRowReader.checksum(src, source);
            String targetChecksum = JdbcRowReader.checksum(dst, targetInPlace);
            if (sourceRows == targetRows && sourceChecksum != null
                    && sourceChecksum.equals(targetChecksum)) {
                actions.add(SyncAction.skipped(ref,
                        "no row key; count and checksum match (" + format(sourceRows) + " rows)"));
                return;
            }
            String rows = format(sourceRows) + " -> " + format(targetRows);
            warnings.add(ref + ": no primary key or NOT NULL unique key — all target rows are deleted"
                    + " and the table is reloaded (" + rows + ")");
            TableDataProducer producer = new TableDataProducer(sourceFactory, targetFactory,
                    source, targetInPlace, options.chunkSize(), TableDataProducer.Mode.COPY, cancelled.supplier());
            actions.add(SyncAction.data(ref, List.of("DELETE FROM " + target(source.name())), producer,
                    "no row key: full reload (" + rows + ")", false));
            return;
        }
        TableDataProducer producer = new TableDataProducer(sourceFactory, targetFactory,
                source, sourceInTarget(source), options.chunkSize(),
                TableDataProducer.Mode.COPY, cancelled.supplier());
        actions.add(SyncAction.data(ref, List.of(), producer,
                "copying " + format(sourceRows) + " rows into a new table", false));
    }

    // ------------------------------------------------------------------ views

    private void compareViews(MySqlMetadataReader sr, MySqlMetadataReader tr, SyncScope scope,
                              TableContext tables, List<SyncAction> actions, List<String> warnings)
            throws SQLException {
        Map<String, ViewDef> source = new LinkedHashMap<>();
        for (ViewDef v : sr.listViews(sourceSchema)) {
            if (scope.includes(ObjectKind.VIEW, v.name())) source.put(lower(v.name()), v);
        }
        Map<String, ViewDef> target = new LinkedHashMap<>();
        for (ViewDef v : tr.listViews(targetSchema)) target.put(lower(v.name()), v);

        for (Map.Entry<String, ViewDef> e : source.entrySet()) {
            ViewDef s = e.getValue();
            ObjectRef ref = new ObjectRef(ObjectKind.VIEW, targetSchema, s.name());
            ViewDef t = target.remove(e.getKey());
            String create = portable(s.createStatement()) + ";";
            List<String> ddl;
            SyncAction.Kind kind;
            String detail;
            if (t == null) {
                kind = SyncAction.Kind.CREATE;
                ddl = List.of(create);
                detail = "view missing on target";
            } else if (!normalizedEquals(s.createStatement(), t.createStatement())) {
                // MySQL has no ALTER VIEW that can change the column list: replace the object.
                kind = SyncAction.Kind.ALTER;
                ddl = List.of("DROP VIEW IF EXISTS " + target(s.name()) + ";", create);
                detail = "view definition differs";
            } else {
                continue;
            }
            Set<String> base = DdlRewriter.readsFrom(s.createStatement(), tables.names(), targetSchema);
            String missing = firstMissing(base, tables.afterPlan());
            if (missing != null) {
                // MySQL refuses a view over a table that is not there, and DDL does not roll back, so
                // a sync that leaves a table out has to say so about its views in the preview instead
                // of failing once everything else is already applied.
                actions.add(SyncAction.skipped(ref, "base table " + missing
                        + " is neither on the target nor part of this sync"));
                warnings.add(ref + ": not created, it reads from table " + missing
                        + " which this sync does not provide");
            } else {
                actions.add(SyncAction.ddl(ref, kind, ddl, detail, base));
            }
        }
        dropExtraneous(target.values(), scope, ObjectKind.VIEW, ViewDef::name,
                name -> List.of("DROP VIEW IF EXISTS " + target(name) + ";"), actions);
    }

    // ------------------------------------------------------------------ routines

    private void compareRoutines(MySqlMetadataReader sr, MySqlMetadataReader tr, SyncScope scope,
                                 List<SyncAction> actions, List<String> warnings) throws SQLException {
        Map<String, RoutineDef> source = new LinkedHashMap<>();
        for (RoutineDef r : sr.listRoutines(sourceSchema)) {
            ObjectKind kind = kindOf(r);
            if (scope.includes(kind, r.name())) source.put(lower(kind.name()) + "." + lower(r.name()), r);
        }
        Map<String, RoutineDef> target = new LinkedHashMap<>();
        for (RoutineDef r : tr.listRoutines(targetSchema)) {
            target.put(lower(kindOf(r).name()) + "." + lower(r.name()), r);
        }

        for (Map.Entry<String, RoutineDef> e : source.entrySet()) {
            RoutineDef s = e.getValue();
            ObjectKind kind = kindOf(s);
            ObjectRef ref = new ObjectRef(kind, targetSchema, s.name());
            RoutineDef t = target.remove(e.getKey());
            String verb = kind == ObjectKind.PROCEDURE ? "PROCEDURE" : "FUNCTION";
            String create = portable(s.createStatement()) + ";";
            if (t == null) {
                actions.add(SyncAction.ddl(ref, SyncAction.Kind.CREATE, List.of(create),
                        verb.toLowerCase(Locale.ROOT) + " missing on target"));
            } else if (!normalizedEquals(s.createStatement(), t.createStatement())) {
                actions.add(SyncAction.ddl(ref, SyncAction.Kind.ALTER,
                        List.of("DROP " + verb + " IF EXISTS " + target(s.name()) + ";", create),
                        verb.toLowerCase(Locale.ROOT) + " body differs"));
            }
        }
        // MySQL has no DROP ROUTINE: the statement has to name the kind each object actually is
        if (options.dropExtraneous() && options.wantsStructure()) {
            for (RoutineDef extra : target.values()) {
                ObjectKind kind = kindOf(extra);
                if (!scope.includes(kind, extra.name())) continue;
                String verb = kind == ObjectKind.PROCEDURE ? "PROCEDURE" : "FUNCTION";
                actions.add(SyncAction.ddl(new ObjectRef(kind, targetSchema, extra.name()),
                        SyncAction.Kind.DROP,
                        List.of("DROP " + verb + " IF EXISTS " + target(extra.name()) + ";"),
                        "exists only on target"));
            }
        }
    }

    private static ObjectKind kindOf(RoutineDef r) {
        return r.kind() == RoutineDef.Kind.PROCEDURE ? ObjectKind.PROCEDURE : ObjectKind.FUNCTION;
    }

    // ------------------------------------------------------------------ triggers

    private void compareTriggers(MySqlMetadataReader sr, MySqlMetadataReader tr, SyncScope scope,
                                 TableContext tables, List<SyncAction> actions, List<String> warnings)
            throws SQLException {
        Map<String, TriggerDef> source = new LinkedHashMap<>();
        for (TriggerDef t : sr.listTriggers(sourceSchema)) {
            if (scope.includes(ObjectKind.TRIGGER, t.name())) source.put(lower(t.name()), t);
        }
        Map<String, TriggerDef> target = new LinkedHashMap<>();
        for (TriggerDef t : tr.listTriggers(targetSchema)) target.put(lower(t.name()), t);

        for (Map.Entry<String, TriggerDef> e : source.entrySet()) {
            TriggerDef s = e.getValue();
            ObjectRef ref = new ObjectRef(ObjectKind.TRIGGER, targetSchema, s.name());
            TriggerDef t = target.remove(e.getKey());
            String create = portable(s.createStatement()) + ";";
            // a trigger's table is known exactly, no parsing needed
            Set<String> base = s.table() == null || s.table().isEmpty()
                    ? Set.of() : Set.of(s.table());
            if (t == null || !normalizedEquals(s.createStatement(), t.createStatement())) {
                String missing = firstMissing(base, tables.afterPlan());
                if (missing != null) {
                    actions.add(SyncAction.skipped(ref, "table " + missing
                            + " it fires on is neither on the target nor part of this sync"));
                    warnings.add(ref + ": not created, its table " + missing
                            + " is not provided by this sync");
                } else {
                    actions.add(SyncAction.ddl(ref, t == null
                            ? SyncAction.Kind.CREATE : SyncAction.Kind.ALTER,
                            t == null ? List.of(create)
                                    : List.of("DROP TRIGGER IF EXISTS " + target(s.name()) + ";", create),
                            t == null ? "trigger missing on target" : "trigger body differs", base));
                }
            } else if (!s.table().equalsIgnoreCase(t.table())) {
                warnings.add(ref + ": trigger " + s.name() + " is attached to " + s.table()
                        + " on the source but " + t.table() + " on the target");
            }
        }
        dropExtraneous(target.values(), scope, ObjectKind.TRIGGER, TriggerDef::name,
                name -> List.of("DROP TRIGGER IF EXISTS " + target(name) + ";"), actions);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Table names on either side, plus the ones that will exist on the target after this plan's table
     * phase — what views and triggers have to be checked against before they can be created.
     */
    private record TableContext(Set<String> names, Set<String> afterPlan) {
    }

    private TableContext tableContext(MySqlMetadataReader sr, MySqlMetadataReader tr,
                                      List<SyncAction> actions) throws SQLException {
        List<String> onTarget = tr.listTableNames(targetSchema);
        Set<String> names = caseInsensitive();
        names.addAll(onTarget);
        names.addAll(sr.listTableNames(sourceSchema));

        Set<String> afterPlan = caseInsensitive();
        afterPlan.addAll(onTarget);
        for (SyncAction a : actions) {
            if (a.object().kind() != ObjectKind.TABLE || !a.isSelected()) continue;
            switch (a.kind()) {
                case DROP -> afterPlan.remove(a.object().name());
                case CREATE -> afterPlan.add(a.object().name());
                default -> { }
            }
        }
        return new TableContext(names, afterPlan);
    }

    /** The first of {@code need} that {@code have} does not contain, or null when all of them do. */
    private static String firstMissing(Set<String> need, Set<String> have) {
        for (String name : need) {
            if (!have.contains(name)) return name;
        }
        return null;
    }

    private static Set<String> caseInsensitive() {
        return new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    }

    /** Target-only objects of one kind, when the user opted into destructive cleanup. */
    private <T> void dropExtraneous(Collection<T> onlyOnTarget, SyncScope scope, ObjectKind kind,
                                    java.util.function.Function<T, String> nameOf,
                                    java.util.function.Function<String, List<String>> dropStatements,
                                    List<SyncAction> actions) {
        if (!options.dropExtraneous() || !options.wantsStructure()) return;
        for (T item : onlyOnTarget) {
            String name = nameOf.apply(item);
            if (!scope.includes(kind, name)) continue;
            actions.add(SyncAction.ddl(new ObjectRef(kind, targetSchema, name), SyncAction.Kind.DROP,
                    dropStatements.apply(name), "exists only on target"));
        }
    }

    /**
     * Source DDL made runnable on the target: DEFINER and version comments removed, and any
     * reference to the source schema redirected at the target schema.
     */
    private String portable(String sourceDdl) {
        String stripped = DdlNormalizer.normalizeObjectDdl(sourceDdl);
        return DdlRewriter.retargetSchema(stripped, sourceSchema, targetSchema);
    }

    /**
     * Whether a view / routine / trigger already matches, compared the way it would be applied.
     *
     * <p>The raw source text has to go through the same portability rewrite as the statement that
     * creates the object: a procedure body written as {@code UPDATE shop.t_users ...} legitimately
     * becomes {@code shop_copy.t_users ...} on the target, and comparing the two raw texts would
     * report that difference forever — re-creating the routine on every run, which is the sort of
     * "always dirty" plan that trains users to ignore the preview.
     */
    private boolean normalizedEquals(String sourceDdl, String targetDdl) {
        return flatten(DdlNormalizer.normalizeObjectDdl(portable(sourceDdl)))
                .equals(flatten(DdlNormalizer.normalizeObjectDdl(targetDdl)));
    }

    /**
     * Collapse whitespace runs to single spaces before comparing canonical object text.
     *
     * <p>{@link DdlNormalizer#normalizeObjectDdl} keeps a body one-line-per-source-line, so a view
     * written across two lines and the same view stored by the server as one line would differ
     * forever — an "always dirty" plan that re-creates the object on every run. Formatting carries no
     * meaning in a routine or view body, and collapsing can only ever hide a whitespace-only edit on
     * the target, which is noise by definition. Executable text is never produced this way.
     */
    private static String flatten(String canonicalDdl) {
        return WHITESPACE_RUNS.matcher(canonicalDdl).replaceAll(" ").strip();
    }

    /** The source table's shape expressed against the target schema, for a table not created yet. */
    private TableDef sourceInTarget(TableDef source) {
        return new TableDef(targetSchema, source.name(), source.createStatement(), source.columns(),
                source.indexes(), source.foreignKeys(), source.charset(), source.collation(),
                source.approxRows());
    }

    private String rowCountSummary(Connection src, Connection dst, TableDef source, TableDef target)
            throws SQLException {
        long sourceRows = cheapCount(src, source);
        long targetRows = target == null ? 0 : cheapCount(dst, target);
        String marker = (isApproximate(source) || (target != null && isApproximate(target))) ? "~" : "";
        return "rows " + marker + format(sourceRows) + " -> " + marker + format(targetRows);
    }

    private static boolean isApproximate(TableDef t) {
        return t.approxRows() >= EXACT_COUNT_LIMIT;
    }

    /** COUNT(*) on a big InnoDB table is a full index scan, so past a threshold use the estimate. */
    private static long cheapCount(Connection conn, TableDef table) throws SQLException {
        if (isApproximate(table)) return table.approxRows();
        return JdbcRowReader.count(conn, table);
    }

    private String target(String name) {
        return SqlNames.qualified(targetSchema, name);
    }

    private static String format(long value) {
        return NumberFormat.getNumberInstance(Locale.ROOT).format(value);
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    private static <T> List<String> names(List<T> items, java.util.function.Function<T, String> key) {
        List<String> out = new ArrayList<>(items.size());
        for (T item : items) out.add(key.apply(item));
        return out;
    }

    private static List<String> union(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        for (String s : b) {
            if (!containsIgnoreCase(out, s)) out.add(s);
        }
        java.util.Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String s : list) {
            if (s.equalsIgnoreCase(value)) return true;
        }
        return false;
    }
}
