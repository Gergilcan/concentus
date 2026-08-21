package com.concentus.store;

import com.concentus.model.StorageSettings;
import com.concentus.secrets.LegacySecrets;
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
    private final LegacySecrets legacy;

    public StorageSettingsStore(@Value("${app.data-dir}") String dataDir, ObjectMapper mapper,
                                LegacySecrets legacy) {
        this.file = Path.of(dataDir).toAbsolutePath().resolve(FILE);
        this.mapper = mapper;
        this.legacy = legacy;
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
     * The password, for building the connection. Never leaves the backend.
     *
     * <p>Held in the clear in this file, like everything else this installation stores. That is a
     * real exposure and it is the same one the folder already carried: this file sits beside the
     * embedded database it configures, so anyone who can read one can read the other.
     *
     * <p>A value still carrying the old encryption is opened when the key that sealed it is still
     * around, and yields blank when it is not — the useful outcome there is a connection error
     * somebody can act on, not a startup crash.
     */
    public String plaintextPassword(StorageSettings settings) {
        if (settings.password() == null || settings.password().isBlank()) return "";
        String password = legacy.read(settings.password()).orElse(null);
        if (password == null) {
            log.error("The stored database password is still encrypted and this installation "
                    + "cannot open it. Re-enter it in Settings.");
            return "";
        }
        return password;
    }

    /**
     * Saves the settings.
     *
     * @param plaintextPassword the new password, or null to keep the one already stored — which is
     *                          what lets the UI edit a connection without ever being sent the
     *                          password it is not allowed to read back
     */
    public StorageSettings save(StorageSettings incoming, String plaintextPassword) {
        StorageSettings current = load();
        String password;
        if (plaintextPassword == null) {
            // Unchanged: whatever is on disk stays exactly as it is, encryption and all. Rewriting
            // it here would mean decrypting it first, which is not always possible.
            password = current.password() == null ? "" : current.password();
        } else if (plaintextPassword.isBlank()) {
            password = "";
        } else {
            password = plaintextPassword;
        }

        StorageSettings toWrite = new StorageSettings(
                incoming.isExternal() ? StorageSettings.EXTERNAL : StorageSettings.EMBEDDED,
                incoming.url() == null ? "" : incoming.url().trim(),
                incoming.username() == null ? "" : incoming.username().trim(),
                password);
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
