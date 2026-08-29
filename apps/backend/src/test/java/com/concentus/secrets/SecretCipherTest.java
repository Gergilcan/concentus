package com.concentus.secrets;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract every store leans on: with a key, a value goes to the table sealed and comes back
 * whole; without one, it goes as typed; and a value nobody here can open is a state, not an
 * exception. That last one is the whole point of the design — the version that first encrypted
 * threw, and the throw surfaced as a flow that ran, did nothing, and reported success.
 */
class SecretCipherTest {

    static String randomKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    /** Seals a value the way the first encrypting version did, so the fixture is the real format. */
    static String sealLegacy(String base64Key, String plaintext) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return SecretCipher.LEGACY_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void with_a_key_a_value_is_sealed_and_opens_to_itself() {
        SecretCipher cipher = new SecretCipher(randomKey());

        String stored = cipher.wrap("re_live_abc123");

        assertThat(stored).startsWith(SecretCipher.PREFIX).doesNotContain("re_live");
        SecretCipher.Reading reading = cipher.open(stored);
        assertThat(reading.state()).isEqualTo(SecretCipher.Reading.State.OPENED);
        assertThat(reading.plaintext()).isEqualTo("re_live_abc123");
    }

    // A fresh IV per value: two seals of the same text must not look alike, or one leaked row
    // would tell an attacker which other rows hold the same password.
    @Test
    void sealing_the_same_value_twice_gives_different_ciphertext() {
        SecretCipher cipher = new SecretCipher(randomKey());

        assertThat(cipher.wrap("same")).isNotEqualTo(cipher.wrap("same"));
    }

    @Test
    void without_a_key_a_value_is_stored_as_typed_and_reads_as_clear() {
        SecretCipher cipher = new SecretCipher("");

        assertThat(cipher.hasKey()).isFalse();
        assertThat(cipher.wrap("as-typed")).isEqualTo("as-typed");
        SecretCipher.Reading reading = cipher.open("as-typed");
        assertThat(reading.state()).isEqualTo(SecretCipher.Reading.State.CLEAR);
        assertThat(reading.plaintext()).isEqualTo("as-typed");
    }

    // The failure this class exists to remove: a sealed row met without the key. Locked, and
    // nothing thrown — the caller shows a state, it does not unwind a run.
    @Test
    void a_sealed_value_without_the_key_is_locked_not_an_exception() {
        String sealed = new SecretCipher(randomKey()).wrap("theirs");

        SecretCipher.Reading reading = new SecretCipher("").open(sealed);

        assertThat(reading.locked()).isTrue();
        assertThat(reading.plaintext()).isNull();
        assertThat(reading.orNull()).isNull();
    }

    @Test
    void a_sealed_value_under_a_different_key_is_locked_too() {
        String sealed = new SecretCipher(randomKey()).wrap("theirs");

        assertThat(new SecretCipher(randomKey()).open(sealed).locked()).isTrue();
    }

    // GCM authenticates: a row somebody edited fails to open rather than opening to garbage.
    @Test
    void a_tampered_value_is_locked_rather_than_wrong() {
        SecretCipher cipher = new SecretCipher(randomKey());
        String sealed = cipher.wrap("original");
        String tampered = sealed.substring(0, sealed.length() - 6)
                + (sealed.endsWith("AAAAAA") ? "BBBBBB" : "AAAAAA");

        assertThat(cipher.open(tampered).locked()).isTrue();
    }

    // The earlier marker, same key: a database sealed before the change needs nothing but the
    // key it was sealed with, and the migration carries it to the current marker from there.
    @Test
    void the_legacy_marker_opens_with_the_same_key() {
        String key = randomKey();
        String legacy = sealLegacy(key, "from-before");

        SecretCipher.Reading reading = new SecretCipher(key).open(legacy);

        assertThat(SecretCipher.isSealed(legacy)).isTrue();
        assertThat(reading.state()).isEqualTo(SecretCipher.Reading.State.OPENED);
        assertThat(reading.plaintext()).isEqualTo("from-before");
    }

    // Refused rather than padded or hashed: "password" is not a key, and accepting it would store
    // something that looks encrypted and is not.
    @Test
    void a_key_of_the_wrong_size_means_no_key() {
        assertThat(new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])).hasKey()).isFalse();
        assertThat(new SecretCipher("not base64!").hasKey()).isFalse();
    }
}
