package com.concentus.secrets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts credentials for storage, and decrypts them for use.
 *
 * <h2>What this protects against, and what it does not</h2>
 * Encrypting at rest defends against a database backup leaking, a database-only compromise, a
 * read-only SQL injection, or anyone with query access reading credentials they should not see.
 * Those are real and common.
 *
 * <p>It does <b>not</b> defend against someone who compromises the application or the host,
 * because the key has to be reachable by this process to be usable. The secret does not disappear
 * — it collapses to <em>one</em> secret, the master key, which is then the thing to protect. Say
 * this plainly rather than letting "it's encrypted" imply more than it delivers.
 *
 * <h2>Design</h2>
 * AES-256-GCM: authenticated, so a tampered ciphertext fails to decrypt rather than silently
 * yielding different plaintext. A fresh random IV per value, stored alongside the ciphertext —
 * reusing an IV under GCM is catastrophic, not merely weak.
 *
 * <p>Stored values carry a version prefix ({@code v1:}) so the format and the key can be rotated
 * later without having to guess how an existing row was written.
 *
 * <p>There is deliberately <b>no fallback to storing plaintext</b> when no key is configured.
 * A silent downgrade would be the worst outcome: the UI would still say "encrypted" while the
 * database held readable tokens.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    private static final String PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    /** 96 bits is the IV size GCM is specified for; other sizes require extra derivation. */
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @throws IllegalStateException when no usable key is configured, which stops the application
     *         from starting. Required rather than optional on purpose: every credential the app
     *         holds depends on it, so a deployment that starts without one would run in a
     *         half-working state — the UI offering credential fields that silently cannot save,
     *         and mail triggers quietly not polling. Failing at startup, once, with the command to
     *         fix it is the kinder failure.
     */
    public SecretCipher(@Value("${app.secret-key:}") String configuredKey) {
        this.key = parseKey(configuredKey);
        if (key == null) {
            throw new IllegalStateException("""

                    ────────────────────────────────────────────────────────────────
                     CONCENTUS_SECRET_KEY is required and is not set (or is not a
                     valid 32-byte base64 key).

                     Generate one:
                       openssl rand -base64 32

                     Then set it in .env (or the container environment) and start
                     again. Keep it safe: changing it makes every stored credential
                     unreadable, and they have to be re-entered.
                    ────────────────────────────────────────────────────────────────
                    """);
        }
    }

    /**
     * Always true once constructed — the application cannot start without a key.
     *
     * <p>Kept so callers read as intent rather than assumption, and so a future change back to an
     * optional key has one place to alter.
     */
    public boolean isAvailable() {
        return key != null;
    }

    /** Raised whenever a credential cannot be sealed or opened. Never carries the value. */
    public static class SecretException extends RuntimeException {
        public SecretException(String message) {
            super(message);
        }
    }

    /**
     * Encrypts a value for storage.
     *
     * @return {@code v1:<base64(iv || ciphertext||tag)>}
     */
    public String seal(String plaintext) {
        requireKey();
        if (plaintext == null) throw new SecretException("Nothing to encrypt.");
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // Never include the exception message: on a bad key it can echo key material length
            // and other detail that is no use to an operator and is worth not printing.
            throw new SecretException("Could not encrypt the credential ("
                    + e.getClass().getSimpleName() + ").");
        }
    }

    /** Decrypts a stored value. Throws if it was tampered with, or written under another key. */
    public String open(String stored) {
        requireKey();
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new SecretException("Stored credential is not in a format this version can read.");
        }
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (raw.length <= IV_BYTES) throw new SecretException("Stored credential is truncated.");

            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(raw, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (SecretException e) {
            throw e;
        } catch (Exception e) {
            // GCM authentication failure lands here, and it means one of two things worth
            // distinguishing for the operator: the key changed, or the row was altered.
            throw new SecretException("Could not decrypt the credential. Either CONCENTUS_SECRET_KEY "
                    + "has changed since it was saved, or the stored value was modified. "
                    + "Re-enter the credential to fix it.");
        }
    }

    private void requireKey() {
        // Unreachable in practice: the constructor refuses to build without a key. Kept so the
        // invariant is stated where it is relied on rather than assumed from a distance.
        if (key == null) throw new SecretException("No encryption key is configured.");
    }

    /**
     * Reads the configured key.
     *
     * <p>Accepts base64 (what {@code openssl rand -base64 32} prints) and requires exactly 32
     * bytes. A short key is rejected rather than padded or hashed into shape: silently accepting
     * "password" as a key would produce something that looks encrypted and is not.
     */
    private static SecretKey parseKey(String configured) {
        if (configured == null || configured.isBlank()) return null;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            log.error("CONCENTUS_SECRET_KEY is not valid base64.");
            return null;
        }
        if (bytes.length != KEY_BYTES) {
            log.error("CONCENTUS_SECRET_KEY decodes to {} bytes; AES-256 needs exactly {}.",
                    bytes.length, KEY_BYTES);
            return null;
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
