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
    @Bean(destroyMethod = "close")
    public EmbeddedPostgres embeddedPostgres(@Value("${app.data-dir}") String dataDir) throws IOException {
        Path pgdata = Path.of(dataDir).toAbsolutePath().resolve("pgdata");
        Files.createDirectories(pgdata);

        long start = System.currentTimeMillis();
        // First launch pays for extracting the binaries and initdb — several seconds. Later
        // launches reuse both, so this is a one-time cost rather than a per-start one.
        boolean firstRun = isEmpty(pgdata);
        if (firstRun) log.info("Preparing the embedded database for first use in {} …", pgdata);

        EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                .setDataDirectory(pgdata)
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

    private static boolean isEmpty(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }
}
