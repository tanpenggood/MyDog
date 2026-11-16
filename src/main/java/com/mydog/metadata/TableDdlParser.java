package com.mydog.metadata;

import com.mydog.metadata.DdlNormalizer.Name;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Splits {@code SHOW CREATE TABLE} output into the parts a structure diff needs: the table name,
 * its columns in ordinal order, its constraints, and the table options tail.
 *
 * <p>The DDL text, not information_schema, is the input on purpose: it is the one thing both
 * servers agree on and it carries the details the columns table does not (index method and prefix
 * length, check expressions, the exact type spelling). What the parser returns is therefore the
 * clause text itself, whitespace-collapsed but otherwise verbatim, so two tables differ only where
 * their definitions really differ.
 *
 * <p>Splitting is bracket-depth aware and literal aware: {@code decimal(12,2)},
 * {@code enum('a','b')} and a {@code DEFAULT 'a,b(c)'} are all one item. Item order is preserved
 * because column order is semantic.
 *
 * <p>Whether an item is a column or a constraint is decided by its first token, and a quoted token
 * is never a keyword: a column named {@code `key`} is a column. The scan primitives come from
 * {@link DdlNormalizer} so parsing and normalization share one idea of what a literal is.
 */
public final class TableDdlParser {

    /** A column clause, name first: {@code `amount` decimal(12,2) NOT NULL}. */
    public record Column(String name, String definition) {
    }

    /** A key / foreign key / check clause and the name the server gave it. */
    public record Constraint(Kind kind, String name, String text) {
    }

    public enum Kind {PRIMARY_KEY, UNIQUE_KEY, KEY, FULLTEXT, SPATIAL, FOREIGN_KEY, CHECK}

    /** Words that introduce a clause rather than name a column; CONSTRAINT is followed by one of them. */
    private static final Set<String> CONSTRAINT_WORDS =
            Set.of("PRIMARY", "UNIQUE", "FOREIGN", "CHECK", "KEY", "INDEX", "FULLTEXT", "SPATIAL");

    /** The leading words of the four index clause forms. */
    private static final Set<String> INDEX_WORDS = Set.of("KEY", "INDEX", "UNIQUE", "FULLTEXT", "SPATIAL");

    public record ParsedTable(String tableName, List<Column> columns,
                              List<Constraint> constraints, String tableOptions) {
    }

    private TableDdlParser() {
    }

    /**
     * Parse one {@code CREATE TABLE} definition.
     *
     * @throws IllegalArgumentException if the text is not a CREATE TABLE statement, or its column
     *                                  list is unbalanced (truncated input, an unterminated
     *                                  literal) - garbage must not reach the diff as an empty table
     */
    public static ParsedTable parse(String ddl) {
        if (ddl == null || ddl.isBlank()) {
            throw new IllegalArgumentException("empty DDL");
        }
        int p = skipWs(ddl, 0);
        p = afterWord(ddl, p, "CREATE");
        p = afterWord(ddl, skipWs(ddl, p), "TABLE");
        p = skipWs(ddl, p);
        if (atWord(ddl, p, "IF")) { // tolerated: dumps and hand-written DDL carry it, SHOW CREATE never does
            p = afterWord(ddl, p, "IF");
            p = afterWord(ddl, skipWs(ddl, p), "NOT");
            p = afterWord(ddl, skipWs(ddl, p), "EXISTS");
            p = skipWs(ddl, p);
        }
        String tableName = "";
        while (true) {
            Name name = nameOrThrow(ddl, p, "table name");
            tableName = name.text();
            p = skipWs(ddl, name.end());
            if (p < ddl.length() && ddl.charAt(p) == '.') { // schema qualifier is not part of the name
                p = skipWs(ddl, p + 1);
                continue;
            }
            break;
        }
        if (p >= ddl.length() || ddl.charAt(p) != '(') {
            throw new IllegalArgumentException("no column list in: " + preview(ddl));
        }
        int close = DdlNormalizer.endOfParen(ddl, p);
        if (close < 0) {
            throw new IllegalArgumentException("unbalanced parentheses in: " + preview(ddl));
        }

        List<Column> columns = new ArrayList<>();
        List<Constraint> constraints = new ArrayList<>();
        List<String> items = splitItems(ddl, p + 1, close);
        for (int ordinal = 1; ordinal <= items.size(); ordinal++) {
            // a clause may span several lines (check constraints, long enums): one stable line
            String item = DdlNormalizer.collapseInline(items.get(ordinal - 1));
            if (!item.isEmpty()) {
                parseItem(item, ordinal, tokens(item), columns, constraints);
            }
        }
        return new ParsedTable(tableName, List.copyOf(columns), List.copyOf(constraints),
                ddl.substring(close + 1).trim());
    }

    // ------------------------------------------------------------------ items

    /** New items start only at depth 0, so key lists and enum values stay inside their own item. */
    private static List<String> splitItems(String s, int from, int to) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        int start = from;
        int i = from;
        while (i < to) {
            char c = s.charAt(i);
            if (DdlNormalizer.isLiteralStart(c)) {
                i = Math.min(DdlNormalizer.endOfLiteral(s, i), to);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                items.add(s.substring(start, i));
                start = ++i;
                continue;
            }
            i++;
        }
        items.add(s.substring(start, to));
        return items;
    }

    private static void parseItem(String item, int ordinal, List<Token> ts,
                                  List<Column> columns, List<Constraint> constraints) {
        Token first = ts.get(0);
        if (!first.name() || first.quoted()) {
            // a quoted first token is an identifier: `key`, `check` and friends stay columns
            columns.add(new Column(first.text(), item));
            return;
        }
        String word = first.text().toUpperCase(Locale.ROOT);
        if (word.equals("PRIMARY")) {
            constraints.add(new Constraint(Kind.PRIMARY_KEY, "PRIMARY", item));
            return;
        }
        String explicitName = null;
        int k = 0;
        if (word.equals("CONSTRAINT")) {
            k = 1;
            if (ts.size() > 1 && ts.get(1).name() && !isConstraintWord(ts.get(1).text())) {
                explicitName = ts.get(1).text();
                k = 2;
            }
            if (k >= ts.size()) {
                columns.add(new Column(first.text(), item));
                return;
            }
            word = ts.get(k).text().toUpperCase(Locale.ROOT);
        }
        switch (word) {
            case "UNIQUE" -> constraints.add(index(Kind.UNIQUE_KEY, ts, k, item, explicitName, ordinal));
            case "KEY", "INDEX" -> constraints.add(index(Kind.KEY, ts, k, item, explicitName, ordinal));
            case "FULLTEXT" -> constraints.add(index(Kind.FULLTEXT, ts, k, item, explicitName, ordinal));
            case "SPATIAL" -> constraints.add(index(Kind.SPATIAL, ts, k, item, explicitName, ordinal));
            case "FOREIGN" -> constraints.add(new Constraint(Kind.FOREIGN_KEY,
                    explicitName != null ? explicitName : generated(Kind.FOREIGN_KEY, ordinal), item));
            case "CHECK" -> constraints.add(new Constraint(Kind.CHECK,
                    explicitName != null ? explicitName : generated(Kind.CHECK, ordinal), item));
            default -> columns.add(new Column(first.text(), item));
        }
    }

    /** {@code UNIQUE KEY `uk_order_no` (`order_no`)}: the name follows the keyword run. */
    private static Constraint index(Kind kind, List<Token> ts, int from, String item,
                                    String explicitName, int ordinal) {
        String name = explicitName;
        if (name == null) {
            int p = from;
            while (p < ts.size() && ts.get(p).name() && isIndexWord(ts.get(p).text())) {
                p++;
            }
            if (p < ts.size() && ts.get(p).name()) {
                name = ts.get(p).text();
            }
        }
        return new Constraint(kind, name != null ? name : generated(kind, ordinal), item);
    }

    /**
     * Stand-in identity for a constraint the DDL left unnamed - MySQL names those itself, but only
     * when the table is created. The ordinal keeps the fallback stable across runs.
     */
    private static String generated(Kind kind, int ordinal) {
        return kind.name().toLowerCase(Locale.ROOT) + "_" + ordinal;
    }

    private static boolean isConstraintWord(String text) {
        return CONSTRAINT_WORDS.contains(text.toUpperCase(Locale.ROOT));
    }

    private static boolean isIndexWord(String text) {
        return INDEX_WORDS.contains(text.toUpperCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ tokens

    /** Name, keyword or punctuation - enough to read the leading words of a clause. */
    private record Token(String text, boolean name, boolean quoted) {
    }

    private static List<Token> tokens(String item) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        while (i < item.length()) {
            char c = item.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            Name name = DdlNormalizer.nameAt(item, i);
            if (name != null) {
                out.add(new Token(name.text(), true, name.quoted()));
                i = name.end();
            } else {
                out.add(new Token(String.valueOf(c), false, false));
                i++;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ scanning

    private static int skipWs(String s, int p) {
        return DdlNormalizer.skipWhitespace(s, p);
    }

    private static boolean atWord(String s, int p, String word) {
        return DdlNormalizer.atWord(s, p, word);
    }

    private static int afterWord(String s, int p, String word) {
        if (!atWord(s, p, word)) {
            throw new IllegalArgumentException("not a CREATE TABLE statement: " + preview(s));
        }
        return p + word.length();
    }

    private static Name nameOrThrow(String s, int p, String what) {
        Name name = DdlNormalizer.nameAt(s, p);
        if (name == null) {
            throw new IllegalArgumentException("missing " + what + " in: " + preview(s));
        }
        return name;
    }

    /** Short one-line form of the input for an error message. */
    private static String preview(String s) {
        String flat = DdlNormalizer.collapseInline(s.strip());
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "...";
    }
}
