package com.mydog.core;

import com.mydog.connection.ConnectionProfile;
import com.mydog.connection.SslMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the "restart the app and the saved password still works" promise: the profile must
 * survive a store reload, never appear as plaintext on disk, and decrypt back to the original.
 */
class ConnectionPersistenceTest {

    @TempDir
    Path configDir;

    @Test
    void passwordIsStoredEncryptedAndSurvivesReload() throws IOException {
        AppContext first = AppContext.createIn(configDir);
        ConnectionProfile draft = new ConnectionProfile(null, "shop-src", "127.0.0.1", 3307,
                "root", "", "shop", SslMode.DISABLED, null);
        first.connections().save(draft.withEncryptedPassword(first.cipher().encrypt("root123")));

        String raw = Files.readString(configDir.resolve("connections.json"), StandardCharsets.UTF_8);
        assertThat(raw).contains("aes:").doesNotContain("root123");

        // fresh process = fresh context over the same directory
        AppContext second = AppContext.createIn(configDir);
        List<ConnectionProfile> loaded = second.connections().all();
        assertThat(loaded).hasSize(1);
        assertThat(loaded.getFirst().name()).isEqualTo("shop-src");
        assertThat(loaded.getFirst().port()).isEqualTo(3307);
        assertThat(loaded.getFirst().sslMode()).isEqualTo(SslMode.DISABLED);
        assertThat(second.cipher().decrypt(loaded.getFirst().encryptedPassword())).isEqualTo("root123");
    }

    @Test
    void localeIsRememberedAcrossReload() {
        // AppContext applies the stored locale to the I18n facade, so restore it afterwards
        String previous = com.mydog.i18n.I18n.localeTag();
        try {
            AppContext.createIn(configDir).setLocale("zh_CN");
            assertThat(AppContext.createIn(configDir).settings().get("locale", "en")).isEqualTo("zh_CN");
        } finally {
            com.mydog.i18n.I18n.setLocale(previous);
        }
    }
}
