package com.mydog.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordCipherTest {

    @TempDir
    Path tmp;

    @Test
    void roundTrip() throws IOException {
        PasswordCipher c = new PasswordCipher(tmp.resolve(".secret.key"));
        String enc = c.encrypt("p@ss w0rd 中文!");
        assertThat(enc).startsWith("aes:").isNotEqualTo("p@ss w0rd 中文!");
        assertThat(c.decrypt(enc)).isEqualTo("p@ss w0rd 中文!");
    }

    @Test
    void randomIvProducesDifferentCiphertext() throws IOException {
        PasswordCipher c = new PasswordCipher(tmp.resolve(".secret.key"));
        assertThat(c.encrypt("same")).isNotEqualTo(c.encrypt("same"));
    }

    @Test
    void emptyAndPasswordHandling() throws IOException {
        PasswordCipher c = new PasswordCipher(tmp.resolve(".secret.key"));
        assertThat(c.encrypt("")).isEmpty();
        assertThat(c.decrypt("")).isEmpty();
        assertThat(c.decrypt(null)).isEmpty();
        // legacy plaintext value passes through
        assertThat(c.decrypt("plainlegacy")).isEqualTo("plainlegacy");
    }

    @Test
    void tamperedCiphertextYieldsEmptyNotException() throws IOException {
        PasswordCipher c = new PasswordCipher(tmp.resolve(".secret.key"));
        String enc = c.encrypt("secret");
        String tampered = enc.substring(0, enc.length() - 4) + "AAAA";
        assertThat(c.decrypt(tampered)).isEmpty();
    }

    @Test
    void keyFileIsReusedAcrossInstances() throws IOException {
        Path key = tmp.resolve(".secret.key");
        PasswordCipher first = new PasswordCipher(key);
        String enc = first.encrypt("stable");
        PasswordCipher second = new PasswordCipher(key);
        assertThat(second.decrypt(enc)).isEqualTo("stable");
    }
}
