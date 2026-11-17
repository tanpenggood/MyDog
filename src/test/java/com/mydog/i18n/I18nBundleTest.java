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

    /**
     * Choosing English has to mean English even on a machine whose operating system speaks Chinese.
     *
     * <p>ResourceBundle puts the OS locale into the candidate chain <em>before</em> the base bundle,
     * so a request for "en" with no Messages_en.properties silently loaded the Chinese one and the
     * menu's language switch did nothing. The empty Messages_en.properties exists for this test.
     */
    @Test
    void englishWinsOverTheOperatingSystemLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            I18n.setLocale("en");
            assertThat(I18n.tr("sync.next")).isEqualTo("Next");
            assertThat(I18n.tr("app.name")).isEqualTo("MyDog");

            I18n.setLocale("zh_CN");
            assertThat(I18n.tr("sync.next")).isEqualTo("下一步");
        } finally {
            Locale.setDefault(saved);
            I18n.setLocale("en");     // leave the static facade in a known state for later tests
        }
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
