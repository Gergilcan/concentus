package com.concentus.secrets;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Encryption of stored credentials.
 *
 * <p>The properties worth pinning are the ones whose absence would be invisible: a value that
 * round-trips looks fine whether or not the IV is reused, whether or not tampering is detected,
 * and whether or not a too-short key was quietly stretched into shape. Each of those is a way to
 * ship something that says "encrypted" and is not.
 */
class SecretCipherTest {

    private static String key() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) i;
        return Base64.getEncoder().encodeToString(raw);
    }

    private static SecretCipher cipher() {
        return new SecretCipher(key());
    }

    @Test
    void aValueRoundTrips() {
        SecretCipher cipher = cipher();

        assertThat(cipher.open(cipher.seal("hunter2-correct-horse"))).isEqualTo("hunter2-correct-horse");
    }

    @Test
    void unicodeAndLongValuesSurvive() {
        SecretCipher cipher = cipher();
        String value = "contraseña-ñÁ€-" + "x".repeat(4000);

        assertThat(cipher.open(cipher.seal(value))).isEqualTo(value);
    }

    @Test
    void theCiphertextDoesNotContainThePlaintext() {
        assertThat(cipher().seal("super-secret-value")).doesNotContain("super-secret-value");
    }

    @Test
    void encryptingTheSameValueTwiceProducesDifferentCiphertext() {
        // A fresh random IV per value. Without it, identical passwords would be visibly identical
        // in the table, and under GCM an IV reuse is catastrophic rather than merely untidy.
        SecretCipher cipher = cipher();

        assertThat(cipher.seal("same-value")).isNotEqualTo(cipher.seal("same-value"));
    }

    @Test
    void aTamperedCiphertextIsRejectedRatherThanDecryptedToSomethingElse() {
        SecretCipher cipher = cipher();
        String sealed = cipher.seal("original-value");
        // Flip a character in the base64 body. GCM is authenticated, so this must fail loudly.
        // The replacement is chosen against the character actually being replaced — deciding it
        // from a different position leaves the string untouched whenever the two happen to agree,
        // and a test that silently hands `open` the original ciphertext fails about one run in
        // sixty. Mid-body, so the flipped bits always reach a decoded byte rather than landing in
        // padding a shorter value might have.
        int at = sealed.length() / 2;
        char flipped = sealed.charAt(at) == 'A' ? 'B' : 'A';
        String tampered = sealed.substring(0, at) + flipped + sealed.substring(at + 1);

        assertThatThrownBy(() -> cipher.open(tampered))
                .isInstanceOf(SecretCipher.SecretException.class);
    }

    @Test
    void aValueSealedUnderAnotherKeyCannotBeOpened() {
        byte[] other = new byte[32];
        java.util.Arrays.fill(other, (byte) 9);
        String sealed = cipher().seal("value");

        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(other)).open(sealed))
                .isInstanceOf(SecretCipher.SecretException.class)
                // The message has to name the likely cause, because "decryption failed" sends an
                // operator looking in the wrong place.
                .hasMessageContaining("CONCENTUS_SECRET_KEY has changed");
    }

    @Test
    void storedValuesCarryAVersionPrefixSoTheFormatCanChangeLater() {
        assertThat(cipher().seal("value")).startsWith("v1:");
    }

    @Test
    void aValueNotInTheStoredFormatIsRejected() {
        assertThatThrownBy(() -> cipher().open("just-a-plain-string"))
                .isInstanceOf(SecretCipher.SecretException.class);
    }

    // ---- key handling ----

    @Test
    void withNoKeyConfiguredTheApplicationRefusesToStart() {
        // Required, not optional. Starting without it would leave a half-working deployment:
        // credential fields that silently cannot save, and mail triggers quietly not polling.
        assertThatThrownBy(() -> new SecretCipher(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONCENTUS_SECRET_KEY is required")
                .hasMessageContaining("openssl rand -base64 32");
    }

    @Test
    void aShortKeyIsRefusedRatherThanStretchedIntoShape() {
        // Accepting "password" as a key would produce something that looks encrypted and is not.
        assertThatThrownBy(() ->
                new SecretCipher(Base64.getEncoder().encodeToString("password".getBytes())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aKeyThatIsNotBase64IsRefused() {
        assertThatThrownBy(() -> new SecretCipher("not base64 at all !!"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCorrectlySizedKeyIsAccepted() {
        assertThat(cipher().isAvailable()).isTrue();
    }
}
