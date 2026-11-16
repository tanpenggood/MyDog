package com.mydog.sync;

import com.mydog.metadata.DdlNormalizer;
import com.mydog.metadata.TableDdlParser;
import com.mydog.metadata.TableDdlParser.Column;
import com.mydog.metadata.TableDdlParser.Constraint;
import com.mydog.metadata.TableDdlParser.Kind;
import com.mydog.metadata.model.TableDef;
import com.mydog.util.SqlNames;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns two SHOW CREATE TABLE texts into the ALTER statements that make the target look like
 * the source.
 *
 * <p>Columns are matched with a longest-common-subsequence walk over their names, so a column
 * inserted in the middle becomes one {@code ADD ... AFTER} instead of a rebuild of everything
 * after it. Indexes and foreign keys are matched by name, which is what MySQL exposes and what
 * a rename therefore correctly shows up as (drop + add).
 *
 * <p>Output is a single ALTER with comma-joined clauses when possible: one statement per table
 * means one table rebuild, not one per column.
 */
public final class TableDiffer {

    private TableDiffer() {
    }

    /** ALTER clauses, empty when the structures are already equivalent. */
    public static List<String> alterClauses(TableDef source, TableDef target) {
        TableDdlParser.ParsedTable src = TableDdlParser.parse(source.createStatement());
        TableDdlParser.ParsedTable dst = TableDdlParser.parse(target.createStatement());
        List<String> clauses = new ArrayList<>();

        clauses.addAll(columnClauses(src.columns(), dst.columns()));
        clauses.addAll(constraintClauses(src.constraints(), dst.constraints()));

        String charsetChange = charsetClause(src.tableOptions(), dst.tableOptions());
        if (charsetChange != null) clauses.add(charsetChange);
        return clauses;
    }

    /** One ALTER TABLE statement per table, or an empty list when nothing differs. */
    public static List<String> alterStatements(TableDef source, TableDef target) {
        List<String> clauses = alterClauses(source, target);
        if (clauses.isEmpty()) return List.of();
        // the statement is applied to the target; naming source.schema() would alter the wrong database
        String tableRef = SqlNames.qualified(target.schema(), target.name());
        // a DROP PRIMARY KEY must lead the statement, otherwise a later ADD PRIMARY KEY fails
        List<String> ordered = new ArrayList<>(clauses.size());
        for (String c : clauses) {
            if (c.startsWith("DROP PRIMARY KEY")) ordered.add(0, c); else ordered.add(c);
        }
        return List.of("ALTER TABLE " + tableRef + " " + String.join(", ", ordered));
    }

    // ------------------------------------------------------------------ columns

    private static List<String> columnClauses(List<Column> srcCols, List<Column> dstCols) {
        List<String> srcNames = srcCols.stream().map(Column::name).toList();
        List<String> dstNames = dstCols.stream().map(Column::name).toList();
        List<String> out = new ArrayList<>();

        int[][] lcs = lcsTable(srcNames, dstNames);
        List<int[]> pairs = walkLcs(lcs, srcNames, dstNames);
        Set<Integer> matchedSrc = new LinkedHashSet<>();
        Set<Integer> matchedDst = new LinkedHashSet<>();
        for (int[] pair : pairs) {
            matchedSrc.add(pair[0]);
            matchedDst.add(pair[1]);
        }

        // dropped: in target, not matched
        for (int j = 0; j < dstCols.size(); j++) {
            if (!matchedDst.contains(j)) {
                out.add("DROP COLUMN " + SqlNames.quote(dstCols.get(j).name()));
            }
        }
        // added or changed, walking source order so AFTER positions are right
        for (int i = 0; i < srcCols.size(); i++) {
            Column c = srcCols.get(i);
            int j = pairOf(pairs, i);
            if (j < 0) {
                String position = i == 0 ? " FIRST"
                        : " AFTER " + SqlNames.quote(srcCols.get(i - 1).name());
                out.add("ADD COLUMN " + c.definition() + position);
            } else if (!definitionEquivalent(c.definition(), dstCols.get(j).definition())) {
                out.add("MODIFY COLUMN " + c.definition());
            }
        }
        return out;
    }

    /**
     * Definitions are compared through the table normalizer so the same rules apply everywhere
     * (int display width, CURRENT_TIMESTAMP spelling, redundant collation). Wrapping each one in
     * a one-column CREATE TABLE is cheaper than duplicating those rules here.
     */
    private static boolean definitionEquivalent(String sourceDefinition, String targetDefinition) {
        return DdlNormalizer.normalizeTableDdl("CREATE TABLE `x` (\n  " + sourceDefinition + "\n)")
                .equals(DdlNormalizer.normalizeTableDdl("CREATE TABLE `x` (\n  " + targetDefinition + "\n)"));
    }

    // ------------------------------------------------------------------ indexes and keys

    private static List<String> constraintClauses(List<Constraint> src, List<Constraint> dst) {
        List<String> out = new ArrayList<>();
        for (Constraint d : dst) {
            Constraint s = findByName(src, d.name());
            if (s == null) {
                out.add(dropClause(d));
            } else if (!textEquivalent(s.text(), d.text())) {
                out.add(dropClause(d));
                out.add(addClause(s));
            }
        }
        for (Constraint s : src) {
            if (findByName(dst, s.name()) == null) {
                out.add(addClause(s));
            }
        }
        return out;
    }

    private static boolean textEquivalent(String a, String b) {
        return DdlNormalizer.normalizeTableDdl("CREATE TABLE `x` (\n  " + a + "\n)")
                .equals(DdlNormalizer.normalizeTableDdl("CREATE TABLE `x` (\n  " + b + "\n)"));
    }

    private static String dropClause(Constraint c) {
        return switch (c.kind()) {
            case PRIMARY_KEY -> "DROP PRIMARY KEY";
            case FOREIGN_KEY -> "DROP FOREIGN KEY " + SqlNames.quote(c.name());
            default -> "DROP INDEX " + SqlNames.quote(c.name());
        };
    }

    private static String addClause(Constraint c) {
        return switch (c.kind()) {
            case PRIMARY_KEY -> "ADD PRIMARY KEY " + c.text().substring("PRIMARY KEY".length()).trim();
            case FOREIGN_KEY -> "ADD " + c.text();
            default -> "ADD " + c.text();
        };
    }

    private static Constraint findByName(List<Constraint> list, String name) {
        for (Constraint c : list) {
            if (c.name().equalsIgnoreCase(name)) return c;
        }
        return null;
    }

    // ------------------------------------------------------------------ table options

    /** Charset/collation drift needs CONVERT TO, which also rewrites every column. */
    private static String charsetClause(String srcOptions, String dstOptions) {
        String srcCharset = option(srcOptions, "CHARSET");
        String dstCharset = option(dstOptions, "CHARSET");
        String srcCollation = option(srcOptions, "COLLATE");
        if (srcCharset != null && dstCharset != null && !srcCharset.equalsIgnoreCase(dstCharset)) {
            return srcCollation != null
                    ? "CONVERT TO CHARACTER SET " + srcCharset + " COLLATE " + srcCollation
                    : "CONVERT TO CHARACTER SET " + srcCharset;
        }
        return null;
    }

    private static String option(String tableOptions, String key) {
        if (tableOptions == null) return null;
        String upper = tableOptions.toUpperCase();
        int at = upper.indexOf("DEFAULT " + key + "=");
        if (at < 0) at = upper.indexOf(key + "=");
        if (at < 0) return null;
        int valueStart = tableOptions.indexOf('=', at) + 1;
        int end = valueStart;
        while (end < tableOptions.length() && !Character.isWhitespace(tableOptions.charAt(end))) end++;
        return tableOptions.substring(valueStart, end);
    }

    // ------------------------------------------------------------------ LCS plumbing

    private static int[][] lcsTable(List<String> a, List<String> b) {
        int[][] t = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                t[i][j] = a.get(i).equalsIgnoreCase(b.get(j))
                        ? t[i + 1][j + 1] + 1
                        : Math.max(t[i + 1][j], t[i][j + 1]);
            }
        }
        return t;
    }

    /** Reads the table back from the corner: the standard LCS walk, using the scores to choose ties. */
    private static List<int[]> walkLcs(int[][] t, List<String> a, List<String> b) {
        List<int[]> pairs = new ArrayList<>();
        int x = 0;
        int y = 0;
        while (x < a.size() && y < b.size()) {
            if (a.get(x).equalsIgnoreCase(b.get(y))) {
                pairs.add(new int[]{x, y});
                x++;
                y++;
            } else if (t[x + 1][y] >= t[x][y + 1]) {
                x++;
            } else {
                y++;
            }
        }
        return pairs;
    }

    private static int pairOf(List<int[]> pairs, int srcIndex) {
        for (int[] p : pairs) {
            if (p[0] == srcIndex) return p[1];
        }
        return -1;
    }

    /** Kinds the differ treats as table-level structure rather than data. */
    public static Set<Kind> structuralKinds() {
        return Set.of(Kind.PRIMARY_KEY, Kind.UNIQUE_KEY, Kind.KEY, Kind.FULLTEXT,
                Kind.SPATIAL, Kind.FOREIGN_KEY, Kind.CHECK);
    }
}
