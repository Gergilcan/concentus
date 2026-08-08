package com.concentus.store;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * The database, for the desktop build: a real PostgreSQL owned by this process.
 *
 * <p>A desktop application cannot ask its user to install and administer a database server, but
 * the alternatives to having one were all worse. Dropping to SQLite or H2 would mean rewriting
 * every {@code jsonb} column, upsert and partial index in the stores, and losing them as options
 * later. Running without persistence — which is what the first desktop build did — quietly took
 * stored credentials and mail triggers with it, because three stores need a database whatever the
 * run history does.
 *
 * <p>So PostgreSQL is embedded rather than replaced: the real server binaries, extracted on first
 * launch and run as a child process against a data directory inside the user's app-data folder.
 * The SQL is untouched, and the user installs nothing.
 *
 * <p><b>Credentials.</b> There are none to set or to ask for. The server listens on a loopback
 * port chosen at startup and accepts local connections without a password, which is the default
 * for an instance that is started, used and stopped by one process. Nothing is gained by adding a
 * password that would have to be stored beside the data it protects, on the same disk, readable by
 * the same user. What guards this data is the file permissions on the data directory and the fact
 * that the port is not reachable from another machine.
 *
 * <p><b>Seeding.</b> Nothing to seed. Each store issues {@code create table if not exists} when it
 * starts, and {@code AccountBootstrap} creates the default organization row that the integration
 * tables are written against. An empty data directory therefore becomes a working database on its
 * own, and the same path runs on every later launch — so first-run and upgrade are not two
 * different cases that can drift apart.
 */
@Configuration
@ConditionalOnProperty(name = "app.desktop", havingValue = "true")
public class EmbeddedPostgresConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedPostgresConfig.class);

    /**
     * Started here rather than lazily, because every store's {@code @PostConstruct} expects a
     * usable connection. destroyMethod stops the server on shutdown — which the desktop shell
     * reaches through {@code POST /actuator/shutdown} when its window closes, so the database is
     * closed properly instead of being killed mid-write.
     */
    /**
     * The major version of the bundled binaries, so a data directory written by a different one is
     * caught here rather than by PostgreSQL failing to start. Kept in step with
     * {@code embedded-postgres-binaries.version} in the pom.
     */
    private final int majorVersion;

    public EmbeddedPostgresConfig(@Value("${app.embedded-postgres.major-version:17}") int majorVersion) {
        this.majorVersion = majorVersion;
    }

    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres(@Value("${app.data-dir}") String dataDir) throws IOException {
        Path pgdata = Path.of(dataDir).toAbsolutePath().resolve("pgdata");
        Files.createDirectories(pgdata);

        long start = System.currentTimeMillis();
        // First launch pays for extracting the binaries and initdb — several seconds. Later
        // launches reuse both, so this is a one-time cost rather than a per-start one.
        boolean firstRun = isEmpty(pgdata);
        if (firstRun) log.info("Preparing the embedded database for first use in {} …", pgdata);

        requireMatchingMajorVersion(pgdata);
        clearStalePidFile(pgdata);

        // Where the server binaries are unpacked. Overridden off the default (the system temp
        // directory) for two reasons: temp is swept by the OS, which would silently discard the
        // pgvector files copied in below, and it is shared with everything else on the machine.
        // Under the app's own data directory they persist and belong to us.
        Path binaries = Path.of(dataDir).toAbsolutePath().resolve("pg-binaries");
        Files.createDirectories(binaries);

        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setOverrideWorkingDirectory(binaries.toFile())
                .setDataDirectory(pgdata)
                // The default is 10s, which is fine for an ordinary start and not fine for the one
                // that matters: after an unclean shutdown PostgreSQL replays its WAL before
                // accepting connections, and on a database with real history that can take longer.
                // Timing out there would fail the whole application over a database that was busy
                // recovering correctly.
                .setPGStartupWait(Duration.ofSeconds(60))
                // Emphatically not a scratch database: this is where the user's flows, runs and
                // credentials live, and wiping it on start would be data loss on every launch.
                .setCleanDataDirectory(false)
                // 0 asks the OS for a free port. Nothing external connects, so the port need not
                // be stable — unlike the application's own port, which is kept fixed for the sake
                // of registered OAuth redirect URIs.
                .setPort(0)
                .start();

        log.info("Embedded PostgreSQL ready on port {} in {} ms (data: {})",
                postgres.getPort(), System.currentTimeMillis() - start, pgdata);

        // After start, not before: the extension's files are read when `create extension` runs,
        // which ToolSearchIndex does later, so there is nothing to gain from delaying the server
        // for them — and locating the unpacked distribution is only possible once it exists.
        unpackedDistribution(binaries).ifPresent(PgVectorInstaller::installInto);
        return postgres;
    }

    /**
     * Replaces Spring Boot's auto-configured DataSource.
     *
     * <p>The auto-configuration backs off when a DataSource bean already exists, so the JDBC URL,
     * user and password in application.properties are simply not consulted in this profile.
     */
    @Bean
    public DataSource dataSource(EmbeddedPostgres postgres) {
        return postgres.getPostgresDatabase();
    }

    /**
     * Refuse to start on a data directory written by a different PostgreSQL major version.
     *
     * <p>A major upgrade changes the on-disk format, so the server would fail anyway — but it fails
     * with a message about the data directory being incompatible, which reads like corruption. This
     * says what actually happened and what to do, and it says it before anything is touched.
     *
     * <p>Deliberately fails rather than recreating the directory. Doing it automatically would mean
     * deleting the user's runs, credentials and flow history to fix a version number, which is not
     * a decision this code gets to make quietly.
     */
    private void requireMatchingMajorVersion(Path pgdata) throws IOException {
        Path versionFile = pgdata.resolve("PG_VERSION");
        if (!Files.isRegularFile(versionFile)) return;   // fresh directory: nothing to disagree with

        String found = Files.readString(versionFile).trim();
        // PG_VERSION holds just the major ("14", "17") from 10 onwards.
        String expected = String.valueOf(majorVersion);
        if (found.equals(expected)) return;

        throw new IllegalStateException(
                "The database in " + pgdata + " was created by PostgreSQL " + found
                        + ", but this version of Concentus embeds PostgreSQL " + expected
                        + ". A major version cannot read another's data directory.\n"
                        + "Runs, credentials and flow history live there; flows, agents and MCP "
                        + "definitions do not — those are files alongside it and are unaffected.\n"
                        + "To start fresh, delete: " + pgdata);
    }

    /**
     * The unpacked PostgreSQL distribution inside the binaries directory.
     *
     * <p>Found by looking rather than computed: zonky names the folder {@code PG-<hash>} after a
     * digest of the archive it unpacked, which is not something to reproduce here — it would become
     * wrong the moment the version changes. Since this directory is ours alone, there is normally
     * exactly one; if a version bump leaves an older one behind, the newest is the live one.
     */
    private static Optional<Path> unpackedDistribution(Path binaries) {
        try (var entries = Files.list(binaries)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("PG-"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()));
        } catch (IOException e) {
            log.warn("Could not inspect {} for the unpacked PostgreSQL: {}", binaries, e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }

    /**
     * Remove {@code postmaster.pid} when the process it names is gone.
     *
     * <p>Without this, one hard kill breaks the application permanently. PostgreSQL refuses to
     * start while that file exists unless it can prove the owning process is dead, and it cannot
     * prove that when the PID has since been reused by something unrelated — which on Windows is
     * routine. The result is an app that worked yesterday, was killed from Task Manager or lost to
     * a power cut, and now fails at launch with a stack trace about a lock file the user has never
     * heard of and no way to act on.
     *
     * <p>Deleting it is safe here in a way it would not be on a shared server: this data directory
     * belongs to this application alone, one instance at a time — the shell takes a single-instance
     * lock before it ever starts a backend. The checks below still refuse to touch the file while
     * anything that looks like a live postmaster owns it, so the one genuinely dangerous case, two
     * servers on one data directory, stays impossible.
     */
    private static void clearStalePidFile(Path pgdata) {
        Path pidFile = pgdata.resolve("postmaster.pid");
        if (!Files.isRegularFile(pidFile)) return;

        long pid;
        try {
            // The first line is the postmaster's PID; the rest is data directory, start time, port
            // and so on, none of which is needed to answer "is it still running".
            String first = Files.readAllLines(pidFile).stream().findFirst().orElse("").trim();
            pid = Long.parseLong(first);
        } catch (Exception e) {
            // Unreadable or malformed: it cannot be identifying a live server, so it is debris.
            log.warn("Removing an unreadable {} — treating it as left over from an unclean stop.", pidFile);
            deleteQuietly(pidFile);
            return;
        }

        Optional<ProcessHandle> owner = ProcessHandle.of(pid);
        if (owner.isEmpty() || !owner.get().isAlive()) {
            log.info("Removing a stale {} (process {} is gone) after an unclean shutdown.", pidFile, pid);
            deleteQuietly(pidFile);
            return;
        }

        // The PID is alive — but that is not the same as "a postmaster is alive". Ask what the
        // process actually is; an unrelated program that inherited the number must not keep the
        // database hostage.
        String command = owner.get().info().command().orElse("");
        if (command.toLowerCase(Locale.ROOT).contains("postgres")) {
            // A real server holds this directory. Leave it alone and let PostgreSQL say so.
            log.warn("A PostgreSQL process ({}) is already using {}. Not touching its lock file.", pid, pgdata);
            return;
        }
        log.info("Removing {}: process {} is alive but is not PostgreSQL ({}), so the PID was reused.",
                pidFile, pid, command.isBlank() ? "unknown command" : command);
        deleteQuietly(pidFile);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("Could not remove {}: {}. The database may refuse to start.", file, e.getMessage());
        }
    }
}
