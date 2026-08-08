package com.concentus.web;

import com.concentus.model.StorageSettings;
import com.concentus.store.StorageSettingsStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Choosing where Concentus keeps its own data.
 *
 * <p>Not {@code /api/databases} — that is already the databases an <em>agent</em> queries for RAG
 * context. This is the application's own storage, which is a different thing with a confusingly
 * similar name, so it gets a distinct path rather than a sub-resource nobody would find.
 *
 * <p><b>The change applies on the next start.</b> The DataSource is built once, during startup,
 * and every store opened its tables against it — swapping it underneath a running application
 * would leave each of them holding a connection to the old database. Saying so plainly is better
 * than a reconnect that half works.
 */
@RestController
@RequestMapping("/api/storage")
public class StorageController {

    /** Only PostgreSQL. The schema uses jsonb and partial indexes; another engine would not run it. */
    private static final String REQUIRED_PREFIX = "jdbc:postgresql:";

    private final StorageSettingsStore store;
    /**
     * What this process started on. Optional because it is published by the desktop configuration;
     * a backend run by hand takes its datasource from properties and has no such choice to report.
     */
    private final ObjectProvider<StorageSettings> active;

    public StorageController(StorageSettingsStore store, ObjectProvider<StorageSettings> active) {
        this.store = store;
        this.active = active;
    }

    /**
     * @param password the new password, or null to keep the stored one. Absent from every response.
     */
    public record StorageRequest(String mode, String url, String username, String password) {
        StorageSettings toSettings() {
            return new StorageSettings(mode, url, username, null);
        }
    }

    /**
     * The saved configuration.
     *
     * <p>{@code hasPassword} rather than the password: it is what the UI needs to show a filled
     * field without the value ever being readable back out of the API.
     */
    @GetMapping
    public Map<String, Object> get() {
        StorageSettings settings = store.load();
        return Map.of(
                "mode", settings.mode(),
                "url", settings.url(),
                "username", settings.username(),
                "hasPassword", settings.password() != null && !settings.password().isBlank(),
                // Whether the running process is actually on it yet, which is how the UI knows to
                // ask for a restart.
                "activeMode", activeMode());
    }

    /** Saves the choice. Takes effect on the next start. */
    @PutMapping
    public Map<String, Object> save(@RequestBody StorageRequest body) {
        StorageSettings incoming = body.toSettings();
        if (incoming.isExternal()) {
            requireUsableUrl(body.url());
        }
        StorageSettings saved = store.save(incoming, body.password());
        return Map.of(
                "mode", saved.mode(),
                "url", saved.url(),
                "username", saved.username(),
                "hasPassword", saved.password() != null && !saved.password().isBlank(),
                "activeMode", activeMode(),
                "restartRequired", !saved.mode().equalsIgnoreCase(activeMode()));
    }

    /**
     * Tries the connection before it is saved.
     *
     * <p>Worth its own endpoint because the alternative is finding out at the next launch, when the
     * app opens against a database it cannot reach and the only clue is a log line. A typo in a
     * host name should cost one click, not one restart.
     */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody StorageRequest body) {
        requireUsableUrl(body.url());
        // A blank password means "use the saved one", so a connection can be tested without the
        // client having to know it.
        String password = body.password() == null
                ? store.plaintextPassword(store.load())
                : body.password();

        Properties props = new Properties();
        if (body.username() != null && !body.username().isBlank()) props.put("user", body.username());
        if (password != null && !password.isBlank()) props.put("password", password);
        // Short: this is someone waiting in front of a dialog, not a background retry.
        props.put("connectTimeout", "8");
        props.put("socketTimeout", "8");

        try (Connection c = DriverManager.getConnection(body.url().trim(), props)) {
            String version = c.getMetaData().getDatabaseProductVersion();
            return Map.of("ok", true, "detail", "Connected to PostgreSQL " + version + ".");
        } catch (SQLException e) {
            // Reported as a body rather than an error status: a failed test is a normal answer to
            // "does this work", and the UI shows the reason next to the field.
            return Map.of("ok", false, "detail", e.getMessage());
        }
    }

    /** The mode this process is running on, which may differ from the one saved for next time. */
    private String activeMode() {
        StorageSettings started = active.getIfAvailable();
        return started == null ? store.load().mode() : started.mode();
    }

    private static void requireUsableUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("A JDBC URL is required for an external database.");
        }
        if (!url.trim().toLowerCase(Locale.ROOT).startsWith(REQUIRED_PREFIX)) {
            throw new IllegalArgumentException(
                    "Only PostgreSQL is supported (the URL must start with " + REQUIRED_PREFIX + "). "
                            + "The schema relies on jsonb and partial indexes.");
        }
    }
}
