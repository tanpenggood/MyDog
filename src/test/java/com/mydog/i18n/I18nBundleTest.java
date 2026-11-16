package com.mydog.i18n;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Enforces that both locale bundles carry the same key set (no missing translations). */
class I18nBundleTest {

    @Test
    void enAndZhKeysMatch() {
        Set<String> en = keys(Locale.ENGLISH);
        Set<String> zh = keys(Locale.SIMPLIFIED_CHINESE);
        assertThat(en).isNotEmpty();
        assertThat(zh).isNotEmpty();
        assertThat(diffs(en, zh)).isEmpty();
        assertThat(diffs(zh, en)).isEmpty();
    }

    @Test
    void missingKeyIsMarkedNotThrown() {
        I18n.setLocale("en");
        try {
            assertThat(I18n.tr("no.such.key")).isEqualTo("!no.such.key!");
        } finally {
            I18n.setLocale("en");
        }
    }

    private static Set<String> keys(Locale locale) {
        ResourceBundle b = ResourceBundle.getBundle("com.mydog.i18n.Messages", locale);
        assertThat(b).isInstanceOf(PropertyResourceBundle.class);
        Set<String> s = new HashSet<>();
        b.keySet().forEach(s::add);
        return s;
    }

    private static Set<String> diffs(Set<String> a, Set<String> b) {
        Set<String> only = new HashSet<>(a);
        only.removeAll(b);
        return only;
    }
}
