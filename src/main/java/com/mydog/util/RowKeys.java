package com.mydog.util;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * Ordering and equality for row key/value lists.
 *
 * <p>Keyset paging compares composite keys element-wise, so this must agree with MySQL's own
 * ORDER BY semantics for the types JDBC hands back (Long, BigInteger, String, byte[]).
 */
public final class RowKeys {

    private RowKeys() {
    }

    public static int compare(List<Object> a, List<Object> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            int c = compareValue(a.get(i), b.get(i));
            if (c != 0) return c;
        }
        return Integer.compare(a.size(), b.size());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compareValue(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;   // keys must not be null in practice; keep ordering total
        if (b == null) return 1;
        if (a instanceof Number na && b instanceof Number nb) {
            return toDecimal(na).compareTo(toDecimal(nb));
        }
        if (a instanceof byte[] ba && b instanceof byte[] bb) {
            return compareUnsignedBytes(ba, bb);
        }
        if (a instanceof Boolean ea && b instanceof Boolean eb) {
            return Boolean.compare(ea, eb);
        }
        if (a.getClass() == b.getClass() && a instanceof Comparable ca) {
            return ca.compareTo(b);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    /** Value equality with SQL semantics: numbers by mathematical value, arrays by content. */
    public static boolean valueEquals(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number na && b instanceof Number nb) {
            return toDecimal(na).compareTo(toDecimal(nb)) == 0;
        }
        if (a instanceof byte[] ba && b instanceof byte[] bb) {
            return Arrays.equals(ba, bb);
        }
        if (a instanceof Boolean ea && b instanceof Boolean eb) {
            return ea.equals(eb);
        }
        return a.equals(b);
    }

    /** True when every column of both rows matches, ignoring column order in the row. */
    public static boolean rowsEqual(com.mydog.sync.Row a, com.mydog.sync.Row b) {
        for (String column : a.columns()) {
            if (!valueEquals(a.value(column), b.value(column))) return false;
        }
        return true;
    }

    private static BigDecimal toDecimal(Number n) {
        if (n instanceof BigDecimal bd) return bd;
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) {
            return BigDecimal.valueOf(n.longValue());
        }
        return new BigDecimal(n.toString());
    }

    private static int compareUnsignedBytes(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int c = Integer.compare(a[i] & 0xFF, b[i] & 0xFF);
            if (c != 0) return c;
        }
        return Integer.compare(a.length, b.length);
    }
}
