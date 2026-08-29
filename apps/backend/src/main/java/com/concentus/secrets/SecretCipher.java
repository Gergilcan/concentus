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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Seals credentials for storage and opens them for use — when this installation has a key.
 *
 * <h2>What the key is, and what happens without one</h2>
 * AES-256-GCM under a data key taken from {@code CONCENTUS_SECRET_KEY}: the desktop shell generates
 * one on first launch and keeps it in the OS keyring; a server sets it as any other secret. Every
 * value written while a key is present goes to the database as {@code enc:v1:<base64>} and is
 * readable only by an installation holding that key — which is what protects a leaked backup, a
 * replica, or anyone with query access. It does <b>not</b> protect against whoever controls this
 * process, because the key has to be reachable here to be usable. The secret does not disappear;
 * it collapses to one key, which is then the thing to guard.
 *
 * <p>Without a key, values are written exactly as typed. That is deliberate and it is the whole
 * upgrade story: a deployment that never set the variable keeps behaving as it does today, and
 * nothing it stored becomes unreadable because a version changed underneath it.
 *
 * <h2>Why a stored value says what it is</h2>
 * Encryption was in this codebase once and was taken out again, and the reason is worth keeping
 * in front of whoever touches this next. The key belonged to a machine and the data belonged to
 * the database, so a second installation connecting to a shared database found every row present
 * and could open none of them — and that surfaced as an integration reporting itself "not
 * configured", an ERROR line in a log nobody reads, and a flow that ran, did nothing, and
 * reported success. A key that must be carried between machines by hand is a key that will be
 * lost, and back then what it protected was unrecoverable when it was.
 *
 * <p>So this version never turns a missing key into a wall. Every stored value is self-describing
 * — the prefix says whether it is sealed, so clear and sealed rows coexist in one table — and a
 * sealed row this installation cannot open reads as {@link Reading.State#LOCKED}: the store keeps
 * working, the credential stays listed under its name, the flow doctor names it, and the fix is to
 * enter the value again. Nothing is thrown at startup and nothing is thrown at run time; the worst
 * case is a re-entry, never a database nobody can use.
 *
 * <h2>Formats</h2>
 * {@code enc:v1:} is what this class writes: a fresh 96-bit IV, the GCM ciphertext and its tag,
 * base64. {@code v1:} is the identical layout under the marker the earlier version used; it is
 * still opened with the same key so a database sealed before the change needs nothing but the key
 * it was sealed with. Reusing an IV under GCM is catastrophic rather than merely weak, which is
 * why one is drawn from the CSPRNG for every value.
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    /** What this version writes. */
    public static final String PREFIX = "enc:v1:";
    /** What the version that first encrypted wrote. Same layout, same key, read but never written. */
    public static final String LEGACY_PREFIX = "v1:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    /** Null when this installation stores in the clear. */
    private final SecretKey key;
    /** Where the key came from, for the one log line that says so. Never the key. */
    private final String keySource;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public SecretCipher(@Value("${app.secret-key:}") String configuredKey,
                        @Value("${app.data-dir:./data}") String dataDir) {
        SecretKey found = parseKey(configuredKey, "CONCENTUS_SECRET_KEY");
        String source = "CONCENTUS_SECRET_KEY";
        if (found == null) {
            found = fromDataDir(dataDir);
            source = "secret.key beside the data";
        }
        this.key = found;
        this.keySource = found == null ? null : source;
        if (found != null) {
            log.info("Stored credentials are encrypted at rest (AES-256-GCM); the key comes from {}.",
                    source);
        } else {
            log.info("No CONCENTUS_SECRET_KEY: stored credentials are written as typed. Anyone who "
                    + "can read the database can read them.");
        }
    }

    /** For tests, and for anything that supplies its own key. Blank means "no key". */
    public SecretCipher(String configuredKey) {
        this.key = parseKey(configuredKey, "the supplied key");
        this.keySource = key == null ? null : "the supplied key";
    }

    /** True when values written from here on are sealed. */
    public boolean hasKey() {
        return key != null;
    }

    /** A sentence for a status endpoint or a log line. Never carries the key. */
    public String keySource() {
        return keySource;
    }

    /** Whether a stored value was written sealed, by this version or the earlier one. */
    public static boolean isSealed(String stored) {
        return stored != null && (stored.startsWith(PREFIX) || stored.startsWith(LEGACY_PREFIX));
    }

    /**
     * What a stored value turned out to be when opened.
     *
     * <p>Three answers rather than an Optional, because two of the empties mean different things
     * to a caller: a row written in the clear is one to seal at the next opportunity when a key
     * has since appeared, and a row this installation cannot open is one to report as locked
     * rather than as absent.
     */
    public record Reading(String plaintext, State state) {
        public enum State {
            /** Not sealed; the value is the stored text. */
            CLEAR,
            /** Sealed, and opened with this installation's key. */
            OPENED,
            /** Sealed under a key this installation does not have. Re-entering is the only fix. */
            LOCKED
        }

        public boolean locked() {
            return state == State.LOCKED;
        }

        /** The usable value, or null when locked — never ciphertext. */
        public String orNull() {
            return locked() ? null : plaintext;
        }
    }

    /**
     * What to write to the database for a value: the ciphertext under the key, or, when there is
     * none, the value itself.
     *
     * <p>The identity case is not a silent downgrade: {@link #hasKey()} says which of the two
     * happens, the startup log says it, and the credentials screen says it.
     */
    public String wrap(String plaintext) {
        if (plaintext == null) throw new IllegalArgumentException("Nothing to store.");
        if (key == null) return plaintext;
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
            // The exception's own message can describe key material; the class name is enough.
            throw new IllegalStateException("Could not encrypt the value ("
                    + e.getClass().getSimpleName() + ").");
        }
    }

    /**
     * Opens whatever is stored. Never throws for a value it cannot open.
     *
     * <p>A wrong key and a tampered row fail identically under GCM, and both answer LOCKED: the
     * caller wants the value or a state it can show, not a stack from wherever the value happened
     * to be needed.
     */
    public Reading open(String stored) {
        if (stored == null) return new Reading(null, Reading.State.CLEAR);
        if (!isSealed(stored)) return new Reading(stored, Reading.State.CLEAR);
        if (key == null) return new Reading(null, Reading.State.LOCKED);
        String body = stored.startsWith(PREFIX)
                ? stored.substring(PREFIX.length()) : stored.substring(LEGACY_PREFIX.length());
        try {
            byte[] raw = Base64.getDecoder().decode(body);
            if (raw.length <= IV_BYTES) return new Reading(null, Reading.State.LOCKED);
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(raw, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new Reading(new String(plain, StandardCharsets.UTF_8), Reading.State.OPENED);
        } catch (Exception e) {
            return new Reading(null, Reading.State.LOCKED);
        }
    }

    /**
     * A key an older server install generated for itself, read but never created.
     *
     * <p>Earlier versions wrote one here when no variable was set, and the rows they sealed are
     * still sealed with it — so it is the right key for that machine. It is not deleted and not
     * rewritten: it may be the only thing that can still open an older backup of that database.
     */
    private static SecretKey fromDataDir(String dataDir) {
        Path file = Path.of(dataDir).toAbsolutePath().resolve("secret.key");
        if (!Files.isRegularFile(file)) return null;
        try {
            // The desktop shell keeps its keyring-wrapped key under the same name; that is not
            // base64 of 32 bytes and quietly parses to nothing, which is right: the shell hands
            // the real key over on the environment.
            return parseKey(Files.readString(file).trim(), null);
        } catch (Exception e) {
            log.warn("The key file at {} could not be read: {}", file, e.getMessage());
            return null;
        }
    }

    /**
     * Accepts base64 of exactly 32 bytes (what {@code openssl rand -base64 32} prints). A short key
     * is refused rather than padded or hashed into shape: silently accepting "password" as a key
     * would produce something that looks encrypted and is not.
     *
     * @param named what to call the key in a complaint, or null to stay quiet about a value that
     *              was never meant to be one
     */
    private static SecretKey parseKey(String configured, String named) {
        if (configured == null || configured.isBlank()) return null;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(configured.trim());
        } catch (IllegalArgumentException e) {
            if (named != null) log.error("{} is not valid base64; storing credentials in the clear.", named);
            return null;
        }
        if (bytes.length != KEY_BYTES) {
            if (named != null) {
                log.error("{} decodes to {} bytes; AES-256 needs exactly {}. Storing credentials "
                        + "in the clear.", named, bytes.length, KEY_BYTES);
            }
            return null;
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
