package com.concentus.store;

import com.concentus.model.StorageSettings;
import com.concentus.secrets.SecretCipher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the choice of database is kept — necessarily <em>outside</em> any database.
 *
 * <p>A plain file, because of an ordering problem there is no way around: the setting decides which
 * DataSource to build, so it has to be readable before any connection exists. Storing it in the
 * database it configures would be circular.
 *
 * <p>The password is sealed with the same key that protects stored credentials, so this file is no
 * more sensitive than the rest of the app-data folder. On the desktop that key lives in the OS
 * keyring, which means copying the folder to another machine does not carry the password with it.
 */
@Component
public class StorageSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(StorageSettingsStore.class);
    private static final String FILE = "storage.json";

    private final Path file;
    private final ObjectMapper mapper;
    private final SecretCipher cipher;

    public StorageSettingsStore(@Value("${app.data-dir}") String dataDir, ObjectMapper mapper,
                                SecretCipher cipher) {
        this.file = Path.of(dataDir).toAbsolutePath().resolve(FILE);
        this.mapper = mapper;
        this.cipher = cipher;
        try {
            Files.createDirectories(this.file.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The configured storage, or embedded when nothing has been chosen. */
    public StorageSettings load() {
        if (!Files.isRegularFile(file)) return StorageSettings.embedded();
        try {
            return mapper.readValue(Files.readString(file), StorageSettings.class).normalized();
        } catch (Exception e) {
            // Same reasoning as normalized(): the only UI for fixing this is inside the app, so
            // failing to start would lock the user out of the one place they could correct it.
            log.error("Could not read {} ({}), falling back to the embedded database.",
                    file, e.getMessage());
            return StorageSettings.embedded();
        }
    }

    /**
     * The password in plaintext, for building the connection. Never leaves the backend.
     *
     * <p>An unreadable value yields blank rather than throwing: that happens when the master key
     * has changed, and the useful outcome is a connection error the user can act on, not a startup
     * crash.
     */
    public String plaintextPassword(StorageSettings settings) {
        if (settings.password() == null || settings.password().isBlank()) return "";
        try {
            return cipher.open(settings.password());
        } catch (RuntimeException e) {
            log.error("The stored database password could not be decrypted ({}). "
                    + "Re-enter it in Settings.", e.getMessage());
            return "";
        }
    }

    /**
     * Saves the settings, sealing the password.
     *
     * @param plaintextPassword the new password, or null to keep the one already stored — which is
     *                          what lets the UI edit a connection without ever being sent the
     *                          password it is not allowed to read back
     */
    public StorageSettings save(StorageSettings incoming, String plaintextPassword) {
        StorageSettings current = load();
        String sealed;
        if (plaintextPassword == null) {
            sealed = current.password() == null ? "" : current.password();
        } else if (plaintextPassword.isBlank()) {
            sealed = "";
        } else {
            sealed = cipher.seal(plaintextPassword);
        }

        StorageSettings toWrite = new StorageSettings(
                incoming.isExternal() ? StorageSettings.EXTERNAL : StorageSettings.EMBEDDED,
                incoming.url() == null ? "" : incoming.url().trim(),
                incoming.username() == null ? "" : incoming.username().trim(),
                sealed);
        try {
            Files.writeString(file, mapper.writeValueAsString(toWrite));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        log.info("Storage set to {}{}. It takes effect on the next start.",
                toWrite.mode(), toWrite.isExternal() ? " (" + toWrite.url() + ")" : "");
        return toWrite;
    }
}
