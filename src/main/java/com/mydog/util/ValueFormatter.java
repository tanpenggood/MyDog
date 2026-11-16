package com.mydog.util;

import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Base64;

/** Turns JDBC values into display text and into SQL literals. */
public final class ValueFormatter {

    /** Shown instead of NULL in the grid; distinguishes it from an empty string. */
    public static final String NULL_TEXT = "NULL";

    private ValueFormatter() {
    }

    public static String display(Object v) {
        if (v == null) return NULL_TEXT;
        if (v instanceof byte[] bytes) {
            return bytes.length <= 16 ? hex(bytes) : "BINARY(" + bytes.length + "B) " + hexPrefix(bytes);
        }
        if (v instanceof Timestamp ts) return ts.toLocalDateTime().toString().replace('T', ' ');
        if (v instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (v instanceof Time t) return t.toLocalTime().toString();
        if (v instanceof BigDecimal bd) return bd.toPlainString();
        return String.valueOf(v);
    }

    /** A value quoted for embedding in generated SQL. */
    public static String sqlLiteral(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number || v instanceof BigDecimal) return String.valueOf(v);
        if (v instanceof Boolean b) return b ? "1" : "0";
        if (v instanceof byte[] bytes) return hex(bytes);
        // a Timestamp is an instant, and every MyDog session runs at +00:00 (forced by the
        // connection factory), so generated SQL has to print it in UTC — toLocalDateTime() would
        // print the JVM's zone and move the value by the offset
        if (v instanceof Timestamp ts) return quoted(utcText(ts));
        if (v instanceof LocalDateTime ldt) return quoted(ldt.toString().replace('T', ' '));
        if (v instanceof LocalDate ld) return quoted(ld.toString());
        if (v instanceof LocalTime lt) return quoted(lt.toString());
        if (v instanceof java.sql.Date d) return quoted(d.toString());
        if (v instanceof Time t) return quoted(t.toString());
        return quoted(String.valueOf(v));
    }

    /** {@code 2026-11-16 18:22:34.5}, fractional seconds only when present. */
    private static String utcText(Timestamp ts) {
        LocalDateTime utc = ts.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDateTime();
        StringBuilder text = new StringBuilder(utc.format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        int nanos = utc.getNano();
        if (nanos != 0) {
            text.append('.').append(String.format("%09d", nanos).replaceAll("0+$", ""));
        }
        return text.toString();
    }

    private static String quoted(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'";
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(2 + bytes.length * 2);
        sb.append("0x");
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                .append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    private static String hexPrefix(byte[] bytes) {
        byte[] head = new byte[Math.min(8, bytes.length)];
        System.arraycopy(bytes, 0, head, 0, head.length);
        return hex(head) + "…";
    }

    /** Base64 for copy/paste of binary cells (not used in generated SQL). */
    public static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
