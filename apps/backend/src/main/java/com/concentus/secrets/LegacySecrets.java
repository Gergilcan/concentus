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
import java.util.Base64;
import java.util.Optional;

/**
 * Reads secrets written by the versions that encrypted them. Writes nothing.
 *
 * <h2>What happened to the encryption</h2>
 * Credentials used to be sealed with a master key that lived outside the database — the OS keyring
 * on a desktop install, an environment variable on a server. That defends against one thing, and
 * it is a real thing: a leaked backup, or anyone with query access, reading tokens they should not
 * see. It was removed deliberately, and what it cost is worth stating plainly rather than leaving
 * to be discovered — <b>every credential in this database is now readable by anyone who can read
 * this database</b>, including a backup of it and whoever hosts it.
 *
 * <p>What it bought is the reason it went. The key belonged to a machine and the data belonged to
 * the database, so the moment a second installation connected to a shared database every row was
 * present and none could be opened. That failure had no good symptom: an integration reporting
 * itself "not configured" while its row sat plainly in the table, and a flow that ran, did
 * nothing, and reported success. A key that must be carried between machines by hand is a key that
 * will be lost, and the thing it protects is unrecoverable when it is.
 *
 * <h2>What this class still does</h2>
 * Opens {@code v1:} values written before the change, using whatever key this installation still
 * has, so nothing already stored is stranded by the switch. It cannot encrypt — there is no seal
 * method and no key is ever generated. Once {@link SecretsMigration} has rewritten what it can,
 * this exists only for the rows it could not, and for a database restored from an older backup.
 */
@Component
public class LegacySecrets {

    private static final Logger log = LoggerFactory.getLogger(LegacySecrets.class);

    /** The marker the old format wrote. A value without it was never encrypted. */
    public static final String PREFIX = "v1:";

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    /** Null when this installation has no old key — which is the normal state from now on. */
    private final SecretKey key;

    @org.springframework.beans.factory.annotation.Autowired
    public LegacySecrets(@Value("${app.secret-key:}") String configuredKey,
                         @Value("${app.data-dir:./data}") String dataDir) {
        SecretKey found = parseKey(configuredKey);
        if (found == null) found = fromDataDir(dataDir);
        this.key = found;
        if (found != null) {
            log.info("An old master key is present; values still encrypted will be read with it "
                    + "and rewritten in the clear. Nothing new is ever encrypted.");
        }
    }

    /** For tests, and for the migration's own fixtures. */
    public LegacySecrets(String configuredKey) {
        this.key = parseKey(configuredKey);
    }

    /** Whether a stored value was written by a version that encrypted. */
    public static boolean sealed(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** True when this installation could open an old value at all. */
    public boolean canOpen() {
        return key != null;
    }

    /**
     * The usable value behind whatever is stored.
     *
     * <p>A plain value comes back as itself, which is the whole point of the change. An old
     * encrypted one is opened when this installation has the key that sealed it, and otherwise
     * reads as absent — the same answer every caller already gives for a credential that is not
     * configured, rather than an exception thrown from wherever it happened to be needed.
     */
    public Optional<String> read(String stored) {
        if (stored == null || stored.isBlank()) return Optional.empty();
        if (!sealed(stored)) return Optional.of(stored);
        return open(stored);
    }

    /** Decrypts an old value. Empty when there is no key, or it is not the one that sealed it. */
    public Optional<String> open(String stored) {
        if (key == null || !sealed(stored)) return Optional.empty();
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (raw.length <= IV_BYTES) return Optional.empty();
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(raw, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return Optional.of(new String(cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES),
                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            // A wrong key and a tampered value fail identically under GCM, and neither is worth an
            // exception here: the caller wants the value or nothing.
            return Optional.empty();
        }
    }

    /**
     * The old key file, read but never created.
     *
     * <p>Left where it is rather than deleted once everything is converted: it is the only thing
     * that can still open an older backup of this database, and deleting somebody's last copy of
     * a decryption key is not a side effect an upgrade should have.
     */
    private static SecretKey fromDataDir(String dataDir) {
        Path file = Path.of(dataDir).toAbsolutePath().resolve("secret.key");
        if (!Files.isRegularFile(file)) return null;
        try {
            return parseKey(Files.readString(file).trim());
        } catch (Exception e) {
            log.warn("The old key at {} could not be read: {}", file, e.getMessage());
            return null;
        }
    }

    private static SecretKey parseKey(String configured) {
        if (configured == null || configured.isBlank()) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(configured.trim());
            return bytes.length == KEY_BYTES ? new SecretKeySpec(bytes, "AES") : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
