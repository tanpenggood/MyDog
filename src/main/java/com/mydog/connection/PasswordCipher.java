package com.mydog.connection;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Obfuscation-grade local password protection: AES-256-GCM with a locally stored random key.
 * Protects against plaintext leaking via backups/screenshots/other local accounts reading files
 * directly. Does NOT protect against an attacker who can read both the key file and the
 * connections file (inherent limitation of keyless local storage). OS keychain / master
 * password are a v2 option.
 */
public final class PasswordCipher {

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final String PREFIX = "aes:";

    private final SecretKey key;

    public PasswordCipher(Path keyFile) throws IOException {
        this.key = new SecretKeySpec(loadOrCreateKey(keyFile), "AES");
    }

    private static byte[] loadOrCreateKey(Path keyFile) throws IOException {
        if (Files.exists(keyFile)) {
            return Files.readAllBytes(keyFile);
        }
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Files.createDirectories(keyFile.getParent());
        Files.write(keyFile, key);
        restrictPermissions(keyFile);
        return key;
    }

    private static void restrictPermissions(Path p) {
        try {
            Files.setPosixFilePermissions(p, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows: relies on user-profile ACLs
        } catch (IOException e) {
            // Non-fatal: best effort
        }
    }

    /** Encrypt to "aes:base64(iv||ciphertext||tag)". Empty input returns empty string. */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("password encryption failed", e);
        }
    }

    /** Decrypt; non-cipher values (plain legacy) are returned as-is. Never returns null. */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (!stored.startsWith(PREFIX)) return stored;
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
