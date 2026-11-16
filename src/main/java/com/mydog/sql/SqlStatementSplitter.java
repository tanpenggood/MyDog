package com.mydog.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a MySQL script into executable statements.
 * Honors client-side {@code DELIMITER} directives, never cuts inside string literals, quoted
 * identifiers or comments, and reports offsets into the original text so callers can map an
 * execution result (or error) back to the editor buffer. Single pass over the characters:
 * no regex, no per-line allocation.
 */
public final class SqlStatementSplitter {

    /** One executable statement with its offsets into the original script text. */
    public record Statement(String text, int start, int end) {}

    private static final String DEFAULT_DELIMITER = ";";
    private static final String DELIMITER_KEYWORD = "DELIMITER";

    private SqlStatementSplitter() {
    }

    /** Split a whole script into executable statements, honoring DELIMITER directives. */
    public static List<Statement> split(String script) {
        if (script == null || script.isEmpty()) return List.of();
        return new Scanner(script).run();
    }

    /** Convenience: just the statement texts. */
    public static List<String> splitTexts(String script) {
        List<Statement> statements = split(script);
        List<String> texts = new ArrayList<>(statements.size());
        for (Statement st : statements) {
            texts.add(st.text());
        }
        return texts;
    }

    /** Character state machine; one instance per script, not thread safe, not reusable. */
    private static final class Scanner {

        private enum State {
            CODE, VERSION_COMMENT, SINGLE_QUOTED, DOUBLE_QUOTED, BACKTICK, LINE_COMMENT, BLOCK_COMMENT
        }

        private final String src;
        private final int len;
        private final List<Statement> out = new ArrayList<>();

        private State state = State.CODE;
        /** State a finished string/comment context returns to. */
        private State resume = State.CODE;
        private String delimiter = DEFAULT_DELIMITER;
        private char quote = '\'';
        /** Content span of the statement being built; {@code start < 0} = only whitespace/comments so far. */
        private int start = -1;
        private int end = -1;

        Scanner(String src) {
            this.src = src;
            this.len = src.length();
        }

        List<Statement> run() {
            int i = 0;
            while (i < len) {
                i = switch (state) {
                    case CODE -> code(i, false);
                    case VERSION_COMMENT -> code(i, true);
                    case SINGLE_QUOTED, DOUBLE_QUOTED, BACKTICK -> quoted(i);
                    case LINE_COMMENT -> lineComment(i);
                    case BLOCK_COMMENT -> blockComment(i);
                };
            }
            flush();
            return List.copyOf(out);
        }

        /** Ordinary SQL: statement boundaries, context entry, plain characters. */
        private int code(int i, boolean inVersionComment) {
            char c = src.charAt(i);
            // Probing only on a token start keeps the directive test O(1): a run of indentation
            // would otherwise rescan its own whitespace at every character.
            if (start < 0 && !isWhitespace(c)) {
                int directive = delimiterDirective(i);
                if (directive >= 0) return directive;
            }
            if (src.regionMatches(i, delimiter, 0, delimiter.length())) {
                flush();
                state = State.CODE;
                return i + delimiter.length();
            }
            return switch (c) {
                case '\'' -> quotedStart(i, State.SINGLE_QUOTED, c);
                case '"' -> quotedStart(i, State.DOUBLE_QUOTED, c);
                case '`' -> quotedStart(i, State.BACKTICK, c);
                case '#' -> lineCommentStart(i, 1);
                case '-' -> isDashComment(i) ? lineCommentStart(i, 2) : plain(i);
                case '/' -> slash(i);
                case '*' -> inVersionComment && src.regionMatches(i + 1, "/", 0, 1) ? versionCommentEnd(i) : plain(i);
                default -> plain(i);
            };
        }

        private int slash(int i) {
            if (!src.regionMatches(i, "/*", 0, 2)) return plain(i);
            if (src.regionMatches(i, "/*!", 0, 3)) {
                // Executable comment: MySQL runs the body as SQL, so boundaries inside it count.
                state = State.VERSION_COMMENT;
                return content(i, i + 2);
            }
            resume = state;
            state = State.BLOCK_COMMENT;
            return markers(i, 2);
        }

        private int quotedStart(int i, State quoted, char open) {
            resume = state;
            state = quoted;
            quote = open;
            return content(i, i + 1);
        }

        private int lineCommentStart(int i, int markerLength) {
            resume = state;
            state = State.LINE_COMMENT;
            return markers(i, markerLength);
        }

        private int quoted(int i) {
            char c = src.charAt(i);
            boolean escaped = state != State.BACKTICK && c == '\\' && i + 1 < len;
            if (escaped) return content(i, Math.min(i + 2, len));
            if (c == quote) {
                if (i + 1 < len && src.charAt(i + 1) == quote) return content(i, i + 2);
                state = resume;
                return content(i, i + 1);
            }
            return content(i, i + 1);
        }

        private int lineComment(int i) {
            char c = src.charAt(i);
            if (c == '\n' || c == '\r') {
                state = resume;
                return i;
            }
            if (start >= 0 && !isWhitespace(c)) content(i, i + 1);
            return i + 1;
        }

        private int blockComment(int i) {
            char c = src.charAt(i);
            if (c == '*' && src.regionMatches(i + 1, "/", 0, 1)) {
                if (start >= 0) content(i, i + 2);
                state = resume;
                return i + 2;
            }
            if (start >= 0 && !isWhitespace(c)) content(i, i + 1);
            return i + 1;
        }

        private int versionCommentEnd(int i) {
            state = State.CODE;
            return content(i, i + 2);
        }

        /**
         * Reads a {@code DELIMITER x} directive line, or returns -1 when the text there is ordinary
         * SQL. Recognized only where a statement would start; the rest of the line (trimmed) becomes
         * the delimiter, an empty argument is a no-op and the directive line is never emitted.
         */
        private int delimiterDirective(int i) {
            if (!src.regionMatches(true, i, DELIMITER_KEYWORD, 0, DELIMITER_KEYWORD.length())) return -1;
            int arg = i + DELIMITER_KEYWORD.length();
            if (arg < len && isWordChar(src.charAt(arg))) return -1;
            int lineEnd = arg;
            while (lineEnd < len && src.charAt(lineEnd) != '\n') {
                lineEnd++;
            }
            String value = src.substring(arg, lineEnd).trim();
            if (!value.isEmpty()) delimiter = value;
            return lineEnd;
        }

        /** Records characters as part of the statement body; also the reason offsets stay exact. */
        private int content(int from, int to) {
            if (start < 0) start = from;
            end = to;
            return to;
        }

        /** Any other character: whitespace only separates, everything else belongs to the statement. */
        private int plain(int i) {
            char c = src.charAt(i);
            if (!isWhitespace(c)) content(i, i + 1);
            return i + 1;
        }

        /** Comment markers count as content only once real SQL has started, else they are dropped. */
        private int markers(int i, int markerLength) {
            return start >= 0 ? content(i, i + markerLength) : i + markerLength;
        }

        private void flush() {
            if (start >= 0 && end > start) {
                out.add(new Statement(src.substring(start, end), start, end));
            }
            start = -1;
            end = -1;
        }

        private boolean isDashComment(int i) {
            if (!src.regionMatches(i, "--", 0, 2)) return false;
            int after = i + 2;
            return after >= len || isWhitespace(src.charAt(after));
        }

        private static boolean isWhitespace(char c) {
            return c <= ' ';
        }

        /** {@code DELIMITER$$} must parse, so {@code $} is deliberately not a word character here. */
        private static boolean isWordChar(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
        }
    }
}
