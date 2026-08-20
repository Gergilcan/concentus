package com.concentus.auth;

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
 * Whether this installation asks people to sign in — necessarily <em>outside</em> the database.
 *
 * <p>The same ordering problem the storage setting has: it decides how the security filter chain
 * is built, which happens before anything has a connection. A plain file, read by the shell when
 * it starts the backend and written here when somebody changes it.
 *
 * <p>Off is still the right default for a fresh desktop install: one person, a socket bound to
 * loopback, and a password to reach a port only they can open buys nothing. It stops being right
 * the moment the data is on a company database — then who may change a flow is a real question,
 * and this is the switch that makes it one.
 */
@Component
public class SignInSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(SignInSettingsStore.class);
    private static final String FILE = "sign-in.json";

    private final Path file;
    private final ObjectMapper mapper;

    public SignInSettingsStore(@Value("${app.data-dir:}") String dataDir, ObjectMapper mapper) {
        this.mapper = mapper;
        // Blank on a server build, which has no data directory and no shell to read the file:
        // there, the setting comes from configuration and this store is inert.
        this.file = dataDir == null || dataDir.isBlank()
                ? null : Path.of(dataDir).toAbsolutePath().resolve(FILE);
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What the file says the next start should do. */
    public record SignInSettings(boolean required) {
    }

    public boolean isSupported() {
        return file != null;
    }

    /** The saved choice, or off when nothing has been chosen. */
    public SignInSettings load() {
        if (file == null || !Files.isRegularFile(file)) return new SignInSettings(false);
        try {
            return mapper.readValue(Files.readString(file), SignInSettings.class);
        } catch (Exception e) {
            // The only interface for fixing this is inside the application, so failing here would
            // lock somebody out of the one place they could correct it — the same reasoning the
            // storage setting follows.
            log.error("Could not read {} ({}); leaving sign-in as it is.", file, e.getMessage());
            return new SignInSettings(false);
        }
    }

    /** Saves the choice. It takes effect when the application is next started. */
    public void save(boolean required) {
        if (file == null) {
            throw new IllegalStateException("This deployment's sign-in is set by its configuration, "
                    + "not from the application.");
        }
        try {
            Files.writeString(file, mapper.writeValueAsString(new SignInSettings(required)));
            log.info("Sign-in will be {} on the next start.", required ? "required" : "switched off");
        } catch (Exception e) {
            throw new IllegalStateException("Could not save the sign-in setting: " + e.getMessage(), e);
        }
    }
}
