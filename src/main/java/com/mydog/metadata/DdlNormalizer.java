package com.mydog.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Canonicalizes MySQL {@code SHOW CREATE} output so that what survives is only real differences.
 *
 * <p>Two databases can be structurally identical while their DDL text is not: the server writes
 * the next auto-increment watermark into every table, records which account created an object,
 * prints display widths 8.0 stopped using, and repeats the collation that the charset already
 * implies. Comparing raw text therefore reports changes that no {@code ALTER} would fix, so both
 * sides of a diff go through here first and only what is left is worth showing the user.
 *
 * <p>Backticks are deliberately preserved. The server quotes identifiers consistently, so they
 * add no noise, and stripping them would corrupt the identifiers being compared: a column named
 * {@code `order`} is a name, not the keyword.
 *
 * <p>Every rewrite fires only in code context. String literals ({@code '...'}, {@code "..."}) and
 * quoted identifiers are copied through untouched by the scanner below, so a
 * {@code COMMENT='has AUTO_INCREMENT=5 inside'} can never be mistaken for a table option. The
 * scanner assumes the server's default sql_mode: backslash escapes and doubled delimiters.
 *
 * <p>The output is a canonical form for comparison, not for display: line indentation is trimmed
 * away, so render the raw DDL in the UI and keep this text for the diff.
 *
 * <p>This class also owns the literal-aware scanning primitives ({@link #nameAt}, {@link #endOfParen})
 * that {@link TableDdlParser} uses, so parsing and normalizing can never disagree about what a
 * literal is.
 */
public final class DdlNormalizer {

    /**
     * Integer types whose {@code (N)} is a display width rather than part of the type:
     * {@code int(11)} and {@code int} store exactly the same values, and 8.0 stopped printing the
     * width, so an old dump would otherwise diff every id column. {@code bit(1)} is NOT in this
     * list - there the N is the column width - and {@code tinyint(1)} is kept because clients read
     * it as a boolean.
     */
    private static final String[] WIDTH_TYPES =
            {"tinyint", "smallint", "mediumint", "int", "integer", "bigint"};

    private static final String CURRENT_TIMESTAMP = "CURRENT_TIMESTAMP";

    /** charset -> default collation, both lower-cased; empty until a reader configures it. */
    private static volatile Map<String, String> defaultCollations = Map.of();

    private DdlNormalizer() {
    }

    // ------------------------------------------------------------------ configuration

    /**
     * Teaches the normalizer which collation each charset defaults to, so a {@code COLLATE} clause
     * that merely repeats the default can be dropped. Populate it at startup from
     * {@code information_schema.COLLATION_CHARACTER_SET_APPLICABILITY} (or {@code CHARACTER_SETS.DEFAULT_COLLATION_NAME}).
     *
     * <p>Until this is called every {@code COLLATE} clause is left alone: deciding the default from
     * the local client's idea of MySQL would silently hide a genuine difference against a server
     * configured with another one.
     */
    public static void configureDefaultCollations(Map<String, String> charsetToDefaultCollation) {
        if (charsetToDefaultCollation == null || charsetToDefaultCollation.isEmpty()) {
            defaultCollations = Map.of();
            return;
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : charsetToDefaultCollation.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                copy.put(lower(e.getKey()), lower(e.getValue()));
            }
        }
        defaultCollations = copy.isEmpty() ? Map.of() : Map.copyOf(copy);
    }

    // ------------------------------------------------------------------ entry points

    /**
     * Canonical form of {@code SHOW CREATE TABLE}: no auto-increment watermark, no DEFINER, no
     * default row format, no redundant collation, no integer display widths except
     * {@code tinyint(1)}, {@code CURRENT_TIMESTAMP} spelled one way, one trimmed line per item,
     * no trailing {@code ;}. {@code ENGINE} is kept - engines are a real difference.
     *
     * @return the canonical text; null and empty input both yield the empty string
     */
    public static String normalizeTableDdl(String ddl) {
        return normalize(ddl, true);
    }

    /**
     * Canonical form of {@code SHOW CREATE VIEW / ROUTINE / TRIGGER}: the DEFINER and the
     * {@code /*!50001 ... *&#47;} version comments the server and mysqldump wrap around the
     * definition are dropped and whitespace is canonicalized, but the body text is otherwise kept
     * exactly as written.
     *
     * <p>The table rules deliberately do not apply here: a procedure body is SQL source of its own
     * (it can contain {@code ALTER TABLE t AUTO_INCREMENT=1}), so treating it as a table
     * description would rewrite statements the diff is supposed to compare.
     *
     * @return the canonical text; null and empty input both yield the empty string
     */
    public static String normalizeObjectDdl(String ddl) {
        return normalize(ddl, false);
    }

    /** True when two table definitions describe the same structure after normalization. */
    public static boolean equivalent(String ddlA, String ddlB) {
        return normalizeTableDdl(ddlA).equals(normalizeTableDdl(ddlB));
    }

    private static String normalize(String ddl, boolean tableRules) {
        if (ddl == null || ddl.isEmpty()) {
            return "";
        }
        String rewritten = new Rewriter(ddl, tableRules).run();
        // stripTrailing: removing a clause or the statement terminator can leave a space behind
        return dropTrailingSemicolon(canonicalLines(rewritten)).stripTrailing();
    }

    // ------------------------------------------------------------------ literal-aware scanning

    /** Opening delimiter of a string literal or a quoted identifier. */
    static boolean isLiteralStart(char c) {
        return c == '\'' || c == '"' || c == '`';
    }

    /**
     * Index just past the literal that starts at {@code start}, honouring doubled delimiters and
     * backslash escapes. An unterminated literal runs to the end of the input.
     */
    static int endOfLiteral(String s, int start) {
        char quote = s.charAt(start);
        int i = start + 1;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < n && s.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    /** Index of the {@code )} closing the {@code (} at {@code open}, or -1 if unbalanced. */
    static int endOfParen(String s, int open) {
        int depth = 0;
        int i = open;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (isLiteralStart(c)) {
                i = endOfLiteral(s, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** Case-insensitive word test with an identifier boundary, so KEY never matches KEYS. */
    static boolean atWord(String s, int p, String word) {
        if (!s.regionMatches(true, p, word, 0, word.length())) {
            return false;
        }
        int after = p + word.length();
        return after >= s.length() || !isIdentifierPart(s.charAt(after));
    }

    static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Index of the first non-whitespace character at or after {@code p}. */
    static int skipWhitespace(String s, int p) {
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) {
            p++;
        }
        return p;
    }

    /** A bare or quoted name. {@code text} is unquoted, {@code end} is the index after it. */
    record Name(String text, int end, boolean quoted) {
    }

    /** The name at {@code p}, or null when {@code p} is not the start of one. */
    static Name nameAt(String s, int p) {
        if (p >= s.length()) {
            return null;
        }
        char c = s.charAt(p);
        if (isLiteralStart(c)) {
            int end = endOfLiteral(s, p);
            String body = s.substring(p + 1, Math.max(p + 1, end - 1));
            // MySQL escapes a delimiter inside an identifier by doubling it
            return new Name(c == '`' ? body.replace("``", "`") : body, end, true);
        }
        int q = p;
        while (q < s.length() && isIdentifierPart(s.charAt(q))) {
            q++;
        }
        return q > p ? new Name(s.substring(p, q), q, false) : null;
    }

    // ------------------------------------------------------------------ whitespace canonical form

    /** One trimmed line per input line, blank lines removed, joined with \n. */
    private static String canonicalLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : splitLines(text)) {
            String collapsed = collapseInline(line);
            if (!collapsed.isEmpty()) {
                lines.add(collapsed);
            }
        }
        return String.join("\n", lines);
    }

    /** Splits on newlines that are code: a comment literal may legitimately contain one. */
    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isLiteralStart(c)) {
                i = endOfLiteral(text, i);
                continue;
            }
            if (c == '\n') {
                lines.add(text.substring(start, i));
                start = ++i;
            } else {
                i++;
            }
        }
        lines.add(text.substring(start));
        return lines;
    }

    /**
     * Collapses every run of code whitespace to one space and trims. Whitespace between tokens is
     * not semantic, and clause removals would otherwise leave double spaces that differ between an
     * old dump and a fresh SHOW CREATE of the same table. Literal content is copied verbatim.
     */
    static String collapseInline(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        boolean pendingSpace = false;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isLiteralStart(c)) {
                int end = endOfLiteral(text, i);
                if (pendingSpace && out.length() > 0) {
                    out.append(' ');
                }
                out.append(text, i, end);
                pendingSpace = false;
                i = end;
                continue;
            }
            if (Character.isWhitespace(c)) {
                pendingSpace = true;
                i++;
                continue;
            }
            if (pendingSpace && out.length() > 0) {
                out.append(' ');
            }
            out.append(c);
            pendingSpace = false;
            i++;
        }
        return out.toString();
    }

    /** The statement terminator mysqldump appends; a literal always ends with its delimiter. */
    private static String dropTrailingSemicolon(String text) {
        return text.endsWith(";") ? text.substring(0, text.length() - 1) : text;
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ the rewrite pass

    /** One pass over the DDL, applying the noise rules to code text only. */
    private static final class Rewriter {

        private final String s;
        private final int n;
        private final boolean tableRules;
        private final StringBuilder out;
        private int i;

        Rewriter(String ddl, boolean tableRules) {
            this.s = ddl;
            this.n = ddl.length();
            this.tableRules = tableRules;
            this.out = new StringBuilder(ddl.length());
        }

        String run() {
            while (i < n) {
                char c = s.charAt(i);
                if (isLiteralStart(c)) {
                    int end = endOfLiteral(s, i);
                    out.append(s, i, end);
                    i = end;
                    continue;
                }
                if (tableRules) {
                    if (tryAutoIncrementCounter()) {
                        continue;
                    }
                    if (tryRowFormat()) {
                        continue;
                    }
                    if (tryCurrentTimestamp()) {
                        continue;
                    }
                    if (tryDisplayWidth()) {
                        continue;
                    }
                    if (tryCharsetCollation()) {
                        continue;
                    }
                }
                if (tryDefiner()) {
                    continue;
                }
                if (tryVersionComment()) {
                    continue;
                }
                out.append(c);
                i++;
            }
            return out.toString();
        }

        /** AUTO_INCREMENT=1006 is the next-value watermark, not structure; the bare column
         *  attribute AUTO_INCREMENT (no =) is real and stays. */
        private boolean tryAutoIncrementCounter() {
            if (!atWord(s, i, "AUTO_INCREMENT")) {
                return false;
            }
            int p = skipWhitespace(s, i + "AUTO_INCREMENT".length());
            if (p >= n || s.charAt(p) != '=') {
                return false;
            }
            p = skipWhitespace(s, p + 1);
            int digits = p;
            while (p < n && Character.isDigit(s.charAt(p))) {
                p++;
            }
            if (p == digits) {
                return false;
            }
            i = p;
            return true;
        }

        /** DYNAMIC is the 8.0 default row format; anything else is a deliberate choice. */
        private boolean tryRowFormat() {
            if (!atWord(s, i, "ROW_FORMAT")) {
                return false;
            }
            int p = skipWhitespace(s, i + "ROW_FORMAT".length());
            if (p >= n || s.charAt(p) != '=') {
                return false;
            }
            p = skipWhitespace(s, p + 1);
            if (!atWord(s, p, "DYNAMIC")) {
                return false;
            }
            i = p + "DYNAMIC".length();
            return true;
        }

        /** CURRENT_TIMESTAMP() and current_timestamp are the same default; the fractional-seconds
         *  form CURRENT_TIMESTAMP(6) is a real difference and keeps its precision. */
        private boolean tryCurrentTimestamp() {
            if (!atWord(s, i, "CURRENT_TIMESTAMP")) {
                return false;
            }
            int wordEnd = i + CURRENT_TIMESTAMP.length();
            int p = skipWhitespace(s, wordEnd);
            if (p < n && s.charAt(p) == '(') {
                int close = endOfParen(s, p);
                if (close >= 0 && s.substring(p + 1, close).isBlank()) {
                    out.append(CURRENT_TIMESTAMP);
                    i = close + 1;
                    return true;
                }
            }
            out.append(CURRENT_TIMESTAMP);
            i = wordEnd;
            return true;
        }

        /** int(11) -> int, but tinyint(1) stays (it is the boolean a client sees). */
        private boolean tryDisplayWidth() {
            for (String type : WIDTH_TYPES) {
                if (!atWord(s, i, type)) {
                    continue;
                }
                int wordEnd = i + type.length();
                // the width is written against the type name; a parenthesis that belongs to
                // something else (an expression default, a key list) is not a display width
                if (wordEnd >= n || s.charAt(wordEnd) != '(') {
                    return false;
                }
                int open = wordEnd;
                int close = endOfParen(s, open);
                if (close < 0 || !isDigits(s, open + 1, close)) {
                    return false;
                }
                String width = s.substring(open + 1, close);
                if (type.equals("tinyint") && width.equals("1")) {
                    out.append(s, i, close + 1);
                    i = close + 1;
                    return true;
                }
                // keep the server's own spelling of the type name, drop only the width
                out.append(s, i, wordEnd);
                i = close + 1;
                return true;
            }
            return false;
        }

        /**
         * Drops the COLLATE clause when it only restates the charset default from
         * {@link #configureDefaultCollations}; a charset that was never named, or a collation that
         * is not the default, is left exactly as it is.
         */
        private boolean tryCharsetCollation() {
            int start = i;
            int p;
            if (atWord(s, i, "CHARSET")) {
                p = skipWhitespace(s, i + "CHARSET".length());
            } else if (atWord(s, i, "CHARACTER")) {
                int set = skipWhitespace(s, i + "CHARACTER".length());
                if (!atWord(s, set, "SET")) {
                    return false;
                }
                p = skipWhitespace(s, set + "SET".length());
            } else {
                return false;
            }
            p = skipEquals(s, p);
            Name charset = nameAt(s, p);
            if (charset == null) {
                i = start;
                return false;
            }
            out.append(s, start, charset.end());
            i = charset.end();

            int collate = skipWhitespace(s, i);
            if (!atWord(s, collate, "COLLATE")) {
                return true;
            }
            p = skipEquals(s, collate + "COLLATE".length());
            Name collation = nameAt(s, p);
            if (collation == null) {
                return true;
            }
            if (!isCharsetDefault(charset.text(), collation.text())) {
                out.append(' ').append(s, collate, collation.end());
            }
            i = collation.end();
            return true;
        }

        /** Who created the object says nothing about its structure. */
        private boolean tryDefiner() {
            if (!atWord(s, i, "DEFINER")) {
                return false;
            }
            int p = skipWhitespace(s, i + "DEFINER".length());
            if (p >= n || s.charAt(p) != '=') {
                return false;
            }
            i = skipAccount(s, skipWhitespace(s, p + 1));
            return true;
        }

        /** /*!50001 ... *&#47;: versioned SQL that the server (and mysqldump) wrap in a comment. */
        private boolean tryVersionComment() {
            if (i + 1 < n && s.charAt(i) == '/' && s.charAt(i + 1) == '*') {
                int p = i + 2;
                if (p < n && s.charAt(p) == '!') {
                    p++;
                    int digits = p;
                    while (p < n && Character.isDigit(s.charAt(p))) {
                        p++;
                    }
                    if (p > digits) {
                        while (p < n && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) {
                            p++;
                        }
                        i = p;
                        out.append(' ');
                        return true;
                    }
                }
                return false;
            }
            if (i + 1 < n && s.charAt(i) == '*' && s.charAt(i + 1) == '/') {
                i += 2;
                return true;
            }
            return false;
        }

        private boolean isCharsetDefault(String charset, String collation) {
            String def = defaultCollations.get(lower(charset));
            return def != null && def.equals(lower(collation));
        }

        /** Optional {@code =} between an option name and its value (table options use it, column
         *  attributes do not). */
        private int skipEquals(String s, int p) {
            int q = skipWhitespace(s, p);
            return q < n && s.charAt(q) == '=' ? skipWhitespace(s, q + 1) : q;
        }

        private static boolean isDigits(String s, int from, int to) {
            if (from >= to) {
                return false;
            }
            for (int i = from; i < to; i++) {
                if (!Character.isDigit(s.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        /** {@code `user`@`host`}, {@code user@host} or {@code CURRENT_USER}. */
        private static int skipAccount(String s, int p) {
            if (atWord(s, p, "CURRENT_USER")) {
                p = skipWhitespace(s, p + "CURRENT_USER".length());
                if (p < s.length() && s.charAt(p) == '(') {
                    int close = endOfParen(s, p);
                    if (close >= 0) {
                        p = close + 1;
                    }
                }
                return p;
            }
            p = skipAccountPart(s, p);
            int at = skipWhitespace(s, p);
            if (at < s.length() && s.charAt(at) == '@') {
                p = skipAccountPart(s, at + 1);
            }
            return p;
        }

        private static int skipAccountPart(String s, int p) {
            Name name = nameAt(s, p);
            return name != null ? name.end() : p;
        }
    }
}
