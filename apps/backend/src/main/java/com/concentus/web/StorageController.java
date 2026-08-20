package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.model.StorageSettings;
import com.concentus.store.EmbeddedPostgresConfig;
import com.concentus.store.StorageMigrator;
import com.concentus.store.StorageSettingsStore;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

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

    private static final Logger log = LoggerFactory.getLogger(StorageController.class);

    /** Only PostgreSQL. The schema uses jsonb and partial indexes; another engine would not run it. */
    private static final String REQUIRED_PREFIX = "jdbc:postgresql:";

    /** Read the data directory this installation keeps its embedded database in, not the live one. */
    private static final String FROM_EMBEDDED = "embedded";

    private final StorageSettingsStore store;
    /**
     * What this process started on. Optional because it is published by the desktop configuration;
     * a backend run by hand takes its datasource from properties and has no such choice to report.
     */
    private final ObjectProvider<StorageSettings> active;
    /** The database this process is actually running on — the source of any migration. */
    private final DataSource current;
    private final OrgContext orgContext;
    /** Where the embedded database lives. Blank on a server build, which has no embedded database. */
    private final String dataDir;
    private final int majorVersion;

    public StorageController(StorageSettingsStore store, ObjectProvider<StorageSettings> active,
                             DataSource current, OrgContext orgContext,
                             @Value("${app.data-dir:}") String dataDir,
                             @Value("${app.embedded-postgres.major-version:17}") int majorVersion) {
        this.store = store;
        this.active = active;
        this.current = current;
        this.orgContext = orgContext;
        this.dataDir = dataDir;
        this.majorVersion = majorVersion;
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

    /**
     * What this installation holds, table by table, biggest first.
     *
     * <p>Shown before the button that copies it: the two questions somebody asks are "how long will
     * this take" and "what exactly travels", and both are answered by a list of counts. Admin only,
     * because the table names alone describe the shape of everything stored here.
     */
    @GetMapping("/contents")
    public Map<String, Object> contents(@RequestParam(defaultValue = "active") String from) {
        orgContext.requireAdmin();
        try (Source source = sourceFor(from)) {
            List<StorageMigrator.TableCount> tables = StorageMigrator.survey(source.dataSource());
            return Map.of(
                    "tables", tables.stream()
                            .map(t -> Map.of("table", t.table(), "rows", t.rows())).toList(),
                    "totalRows", tables.stream().mapToLong(StorageMigrator.TableCount::rows).sum());
        }
    }

    /**
     * @param skip tables to leave behind — for a move that wants the configuration on the company
     *             database without a year of run history crossing a VPN first
     */
    public record MigrateRequest(String from, String url, String username, String password,
                                 List<String> skip) {
    }

    /**
     * Copies everything into another PostgreSQL, and does not switch to it.
     *
     * <p>Two decisions, deliberately kept apart: the copy adds rows and changes nothing here, so it
     * can be repeated and can be done while still working on the embedded database; switching costs
     * a restart. Someone who copies first can test the company database at leisure and move when
     * they are ready.
     *
     * <p>Never deletes — see {@link StorageMigrator}. Admin only: this both reads everything stored
     * here and writes it somewhere else.
     */
    @PostMapping("/migrate")
    public Map<String, Object> migrate(@RequestBody MigrateRequest body) {
        orgContext.requireAdmin();
        try (Source source = sourceFor(body.from())) {
            DataSource target = FROM_EMBEDDED.equalsIgnoreCase(body.from()) ? current : externalFrom(body);
            StorageMigrator.Report report = StorageMigrator.copy(source.dataSource(), target,
                    body.skip() == null ? Set.of() : Set.copyOf(body.skip()));
            return Map.of(
                    "ok", true,
                    "copied", report.copied().stream()
                            .map(t -> Map.of("table", t.table(), "rows", t.rows())).toList(),
                    "warnings", report.warnings(),
                    "totalRows", report.totalRows());
        }
    }

    /** The database named in the request — the far side of a copy that leaves this installation. */
    private DataSource externalFrom(MigrateRequest body) {
        requireUsableUrl(body.url());

        Properties props = new Properties();
        if (body.username() != null && !body.username().isBlank()) props.put("user", body.username());
        // A blank password means the one already saved for the external database, so a migration can
        // follow a saved configuration without the browser ever having held the value.
        String password = body.password() == null || body.password().isBlank()
                ? store.plaintextPassword(store.load())
                : body.password();
        if (password != null && !password.isBlank()) props.put("password", password);
        // Long, unlike the connection test: this one is copying, and a slow link is not a failure.
        props.put("connectTimeout", "15");
        return new SimpleUrlDataSource(body.url().trim(), props);
    }

    /**
     * Which database a migration reads.
     *
     * <p>Two directions, because both happen. Somebody still on the embedded database copies it to
     * a company server; somebody who already switched, restarted, and found an empty screen needs
     * the opposite — and telling them to switch back, restart, migrate, switch again and restart
     * once more is a worse answer than opening the idle data directory here.
     *
     * <p>Asking for the embedded side while the application is <em>running</em> on it is not a
     * second server on one data directory: it is the same database, so the live connection is
     * returned instead.
     */
    private Source sourceFor(String from) {
        if (!FROM_EMBEDDED.equalsIgnoreCase(from) || FROM_EMBEDDED.equalsIgnoreCase(activeMode())) {
            return new Source(current, null);
        }
        if (dataDir == null || dataDir.isBlank()) {
            throw new IllegalStateException("This deployment has no embedded database — it has "
                    + "always run on the PostgreSQL it was configured with.");
        }
        try {
            EmbeddedPostgres postgres = EmbeddedPostgresConfig.open(dataDir, majorVersion);
            log.info("Opened the embedded database at {} to read it for a migration.", dataDir);
            return new Source(postgres.getPostgresDatabase(), postgres);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("The embedded database could not be opened: "
                    + e.getMessage() + " Is another copy of Concentus running?", e);
        }
    }

    /**
     * A database to read, and the server to stop afterwards if this opened one.
     *
     * <p>{@code close()} does not throw: it runs on the way out of a request that has already
     * succeeded or already failed for its own reasons, and a server that will not stop cleanly is
     * worth a log line, not a different answer to the caller.
     */
    private record Source(DataSource dataSource, EmbeddedPostgres started) implements AutoCloseable {
        @Override
        public void close() {
            if (started == null) return;
            try {
                started.close();
            } catch (IOException e) {
                log.warn("The embedded database opened for a migration did not stop cleanly: {}",
                        e.getMessage());
            }
        }
    }

    /**
     * A DataSource over one URL and its properties.
     *
     * <p>Not a pool: this is used once, by one migration, and a pool would hold connections open to
     * a database this process is not otherwise running on. Spring's DriverManagerDataSource would
     * do, but it takes user and password as separate arguments and drops everything else — the
     * connect timeout among them.
     */
    private record SimpleUrlDataSource(String url, Properties props) implements DataSource {
        @Override public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, props);
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            Properties p = new Properties();
            p.putAll(props);
            p.put("user", username);
            p.put("password", password);
            return DriverManager.getConnection(url, p);
        }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }
        @Override public <T> T unwrap(Class<T> iface) { return iface.cast(this); }
        @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
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
