package com.mydog.sync;

import com.mydog.util.SqlNames;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Rewrites schema-qualified names in {@code SHOW CREATE} output from the source schema to the
 * target one.
 *
 * <p>MySQL expands view definitions with fully qualified column references
 * ({@code select `shop`.`users`.`id` ... from `shop`.`users`}) and qualifies foreign keys and
 * trigger tables the same way, so DDL copied verbatim into another schema would still read from
 * the original one — silently, in the case of a view. Syncing a schema under a different name
 * therefore has to rewrite the qualifier.
 *
 * <p>Only a name immediately followed by {@code .} is replaced, in either of the two forms the
 * server writes ({@code `shop`.} and {@code shop.}), so a column named {@code shop} used as
 * {@code `t`.`shop`} is left alone. String literals and comments are copied through untouched.
 *
 * <p>Known limitation: a table alias spelled exactly like the source schema and then used as a
 * qualifier ({@code FROM orders AS shop WHERE shop.id = 1}) is rewritten too. That rename is
 * self-consistent — alias declaration and reference both move — so the result is still valid SQL.
 */
public final class DdlRewriter {

    private DdlRewriter() {
    }

    /**
     * Replaces every code-context {@code sourceSchema.} qualifier with {@code targetSchema.}.
     * Returns {@code ddl} unchanged when either schema is null/empty or the names are equal.
     */
    public static String retargetSchema(String ddl, String sourceSchema, String targetSchema) {
        if (ddl == null || ddl.isEmpty()) return ddl;
        if (sourceSchema == null || sourceSchema.isEmpty()) return ddl;
        if (targetSchema == null || targetSchema.isEmpty()) return ddl;
        if (sourceSchema.equalsIgnoreCase(targetSchema)) return ddl;

        String replacement = SqlNames.quote(targetSchema) + ".";
        StringBuilder out = new StringBuilder(ddl.length() + 32);
        int i = 0;
        while (i < ddl.length()) {
            char c = ddl.charAt(i);

            if (c == '`') {
                int end = endOfBackquoted(ddl, i);
                String ident = ddl.substring(i + 1, Math.max(i + 1, end - 1));
                if (ident.equalsIgnoreCase(sourceSchema) && followsDot(ddl, end)) {
                    out.append(replacement);
                    i = skipDot(ddl, end);
                } else {
                    out.append(ddl, i, end);
                    i = end;
                }
                continue;
            }

            int afterNoise = skipNoise(ddl, i);
            if (afterNoise != i) {
                out.append(ddl, i, afterNoise);
                i = afterNoise;
                continue;
            }

            if (isWordStart(ddl, i)) {
                int end = i;
                while (end < ddl.length() && isWordChar(ddl.charAt(end))) end++;
                if (end - i == sourceSchema.length()
                        && ddl.regionMatches(true, i, sourceSchema, 0, sourceSchema.length())
                        && followsDot(ddl, end)) {
                    out.append(replacement);
                    i = skipDot(ddl, end);
                } else {
                    out.append(ddl, i, end);
                    i = end;
                }
                continue;
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * The tables this SQL reads: the names that follow {@code FROM} and {@code JOIN}.
     *
     * <p>Only names present in {@code candidates} come back, and a {@code qualifier.name} pair is
     * accepted only when the qualifier is {@code localSchema} — a view that reads a table of some
     * other schema is that schema's business, not this sync's. Literals, comments and backticks are
     * handled exactly as in {@link #retargetSchema}, so a string that merely contains {@code from x}
     * does not invent a dependency.
     *
     * <p>A derived table ({@code FROM (SELECT ...) d}) and a table alias resolve to nothing here,
     * which is the point: the result is a set of real base tables, safe to act on rather than a hint.
     */
    public static Set<String> readsFrom(String sql, Collection<String> candidates, String localSchema) {
        Set<String> found = new LinkedHashSet<>();
        if (sql == null || sql.isEmpty() || candidates.isEmpty()) return found;
        int i = 0;
        while (i < sql.length()) {
            int afterNoise = skipNoise(sql, i);
            if (afterNoise != i) {
                i = afterNoise;
                continue;
            }
            if (!isWordStart(sql, i)) {
                i++;
                continue;
            }
            int end = wordEnd(sql, i);
            if (isWord(sql, i, end, "from") || isWord(sql, i, end, "join")) {
                NameRef ref = nameAt(sql, end);
                if (ref != null && (ref.qualifier() == null || ref.qualifier().equalsIgnoreCase(localSchema))) {
                    for (String candidate : candidates) {
                        if (candidate.equalsIgnoreCase(ref.name())) found.add(candidate);
                    }
                }
            }
            i = end;
        }
        return found;
    }

    /** A table reference: {@code [qualifier.]name}, either part possibly backquoted. */
    private record NameRef(String qualifier, String name) {
    }

    private static NameRef nameAt(String s, int at) {
        int p = at;
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        String first = identifierAt(s, p);
        if (first == null) return null;
        p = endOfIdentifier(s, p);
        if (p < s.length() && s.charAt(p) == '.') {
            p = skipWhitespace(s, p + 1);
            String second = identifierAt(s, p);
            if (second == null) return new NameRef(null, first);
            return new NameRef(first, second);
        }
        return new NameRef(null, first);
    }

    private static int skipWhitespace(String s, int at) {
        int p = at;
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        return p;
    }

    private static String identifierAt(String s, int at) {
        if (at >= s.length()) return null;
        if (s.charAt(at) == '`') {
            int end = endOfBackquoted(s, at);
            return s.substring(at + 1, Math.max(at + 1, end - 1)).replace("``", "`");
        }
        if (!isWordStart(s, at)) return null;
        return s.substring(at, wordEnd(s, at));
    }

    private static int endOfIdentifier(String s, int at) {
        if (at < s.length() && s.charAt(at) == '`') return endOfBackquoted(s, at);
        return wordEnd(s, at);
    }

    private static int wordEnd(String s, int from) {
        int end = from;
        while (end < s.length() && isWordChar(s.charAt(end))) end++;
        return end;
    }

    private static boolean isWord(String s, int from, int to, String word) {
        return to - from == word.length() && s.regionMatches(true, from, word, 0, word.length());
    }

    // ------------------------------------------------------------------ scanning

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** True at the first character of an unquoted word (so {@code xshop.} inside {@code axshop.} never matches). */
    private static boolean isWordStart(String s, int i) {
        if (!isWordChar(s.charAt(i))) return false;
        return i == 0 || !isWordChar(s.charAt(i - 1));
    }

    /** Index just past a string literal or comment starting at {@code i}; {@code i} when nothing starts here. */
    private static int skipNoise(String s, int i) {
        char c = s.charAt(i);
        if (c == '\'' || c == '"') return endOfQuoted(s, i, c);
        if (c == '/' && s.startsWith("/*", i)) {
            // /*!50001 ... */ is executable MySQL syntax — treat its contents as code, not noise,
            // so a schema qualifier inside it is still rewritten. Everything else is a comment.
            if (s.startsWith("/*!", i)) return i;
            int e = s.indexOf("*/", i + 2);
            return e < 0 ? s.length() : e + 2;
        }
        if (c == '#' || (c == '-' && s.startsWith("--", i) && followedBySpace(s, i + 2))) {
            int e = s.indexOf('\n', i);
            return e < 0 ? s.length() : e;
        }
        return i;
    }

    private static boolean followedBySpace(String s, int at) {
        return at >= s.length() || Character.isWhitespace(s.charAt(at));
    }

    private static int endOfQuoted(String s, int i, char quote) {
        int p = i + 1;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == '\\') {
                p += 2;
                continue;
            }
            if (c == quote) {
                if (p + 1 < s.length() && s.charAt(p + 1) == quote) {
                    p += 2;
                    continue;
                }
                return p + 1;
            }
            p++;
        }
        return s.length();
    }

    /** Index just past the closing backtick, honouring MySQL's doubled-backtick escape. */
    private static int endOfBackquoted(String s, int i) {
        int p = i + 1;
        while (p < s.length()) {
            if (s.charAt(p) == '`') {
                if (p + 1 < s.length() && s.charAt(p + 1) == '`') {
                    p += 2;
                    continue;
                }
                return p + 1;
            }
            p++;
        }
        return s.length();
    }

    private static boolean followsDot(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i < s.length() && s.charAt(i) == '.';
    }

    private static int skipDot(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i < s.length() && s.charAt(i) == '.' ? i + 1 : i;
    }
}
