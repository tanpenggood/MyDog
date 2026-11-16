package com.mydog.sql.highlight;

import java.util.Set;
import java.util.TreeSet;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * Classifies MySQL script text into style spans for the editor.
 *
 * <p>Hand-written scanner rather than regex: the editor restyles on every debounce tick, and a
 * regex with alternation over keywords re-scans the whole document on each keystroke group.
 */
public final class SqlSyntaxStyler {

    public static final String KEYWORD = "sql-keyword";
    public static final String STRING = "sql-string";
    public static final String COMMENT = "sql-comment";
    public static final String NUMBER = "sql-number";
    public static final String IDENTIFIER_QUOTED = "sql-quoted";

    private static final ObservableList<String> NONE = FXCollections.observableArrayList();

    /** Reserved words that matter for highlighting; MySQL's full list minus the noise. */
    private static final Set<String> KEYWORDS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        KEYWORDS.addAll(java.util.List.of(
                "select", "insert", "update", "delete", "replace", "merge", "from", "where",
                "group", "by", "order", "having", "limit", "offset", "distinct", "as", "on",
                "join", "inner", "left", "right", "full", "outer", "cross", "natural", "using",
                "union", "all", "and", "or", "not", "xor", "is", "null", "true", "false",
                "in", "like", "regexp", "rlike", "between", "exists", "case", "when", "then",
                "else", "end", "if", "create", "alter", "drop", "truncate", "rename", "add",
                "table", "database", "schema", "view", "index", "unique", "primary", "key",
                "foreign", "references", "constraint", "check", "default", "auto_increment",
                "engine", "charset", "collate", "character", "set", "column", "modify",
                "change", "after", "first", "values", "into", "procedure", "function", "trigger",
                "before", "for", "each", "row", "begin", "declare", "begin", "return", "returns",
                "call", "delimiter", "show", "describe", "explain", "desc", "asc", "with",
                "recursive", "temporary", "unsigned", "zerofill", "enum", "grant", "revoke",
                "commit", "rollback", "start", "transaction", "savepoint", "release", "lock",
                "unlock", "read", "write", "ignore", "duplicate", "count", "sum", "avg", "min",
                "max", "cast", "convert", "interval", "now", "date", "datetime", "timestamp",
                "int", "integer", "bigint", "smallint", "tinyint", "decimal", "numeric",
                "varchar", "char", "text", "blob", "json", "current_timestamp", "current_date"));
    }

    private SqlSyntaxStyler() {
    }

    /**
     * Style spans for one paragraph. RichTextFX styles per paragraph, which keeps the cost
     * proportional to what changed; block comments spanning several paragraphs are only
     * styled from their opening line (acceptable for editor use, never used for execution).
     */
    public static StyleSpans<ObservableList<String>> spansFor(String text) {
        StyleSpansBuilder<ObservableList<String>> builder = new StyleSpansBuilder<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            int to;
            String style;
            if (c == '\'' || c == '"') {
                to = endOfString(text, i, c);
                style = STRING;
            } else if (c == '`') {
                to = endOfBacktick(text, i);
                style = IDENTIFIER_QUOTED;
            } else if ((c == '-' && i + 1 < n && text.charAt(i + 1) == '-') || c == '#') {
                to = n;                       // rest of the line is a comment
                style = COMMENT;
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int close = text.indexOf("*/", i + 2);
                to = close < 0 ? n : close + 2;
                style = COMMENT;
            } else if (Character.isDigit(c)) {
                to = scanWord(text, i);
                style = NUMBER;
            } else if (Character.isLetter(c) || c == '_') {
                to = scanWord(text, i);
                style = KEYWORDS.contains(text.substring(i, to)) ? KEYWORD : null;
            } else {
                to = i + 1;
                style = null;
            }
            builder.add(style == null ? NONE : styles(style), to - i);
            i = to;
        }
        return builder.create();
    }

    private static int scanWord(String text, int start) {
        int i = start;
        while (i < text.length()
                && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_' || text.charAt(i) == '.')) {
            i++;
        }
        return i;
    }

    private static ObservableList<String> styles(String... classes) {
        return FXCollections.observableArrayList(classes);
    }

    private static int endOfString(String text, int start, char quote) {
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == quote) {
                if (i + 1 < text.length() && text.charAt(i + 1) == quote) { i += 2; continue; }
                return i + 1;
            }
            i++;
        }
        return text.length();
    }

    private static int endOfBacktick(String text, int start) {
        int i = start + 1;
        while (i < text.length()) {
            if (text.charAt(i) == '`') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '`') { i += 2; continue; }
                return i + 1;
            }
            i++;
        }
        return text.length();
    }
}
