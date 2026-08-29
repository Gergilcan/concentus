package com.concentus.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * Puts the pgvector extension where the embedded PostgreSQL can find it.
 *
 * <p><b>Why this is a file copy and not a SQL statement.</b> {@code CREATE EXTENSION vector} does
 * not install anything — it registers an extension whose files must already be on disk: a control
 * file, its SQL definitions, and a shared library of compiled C. That library is built for one
 * operating system, one CPU architecture and one PostgreSQL major version, so it cannot be produced
 * at runtime; it has to be compiled ahead of time and carried along. See the release workflow,
 * which builds it per platform.
 *
 * <p>What can happen at runtime is putting those files where the server looks. zonky unpacks its
 * PostgreSQL into a temporary directory, so there is no permanent installation to add an extension
 * to — the files are copied in on each start, before the server is launched. That also makes this
 * self-healing: if the temporary directory is cleaned up by the OS, the next start simply lays them
 * down again.
 *
 * <p><b>Absence is not failure.</b> A build without the binaries — a local `mvn package`, or a
 * platform nobody has compiled for — logs and moves on. {@code ToolSearchIndex} already treats a
 * missing extension as "rank lexically instead", so the only consequence is worse ordering of MCP
 * tool search results, which it reports in the UI.
 *
 * <p><b>What happened here is remembered</b>, in {@link #outcome()}, because the UI's "ranking by
 * word overlap" notice used to guess at the reason — and guessed wrong, telling every desktop
 * user the embedded PostgreSQL could not carry the extension when the installers have carried it
 * since the release workflow started compiling it. The four ways this can end (nothing built for
 * the platform, a jar without the files, copied, copy failed) are distinct facts about the
 * installation, and only this class knows which one occurred.
 */
public final class PgVectorInstaller {

    private static final Logger log = LoggerFactory.getLogger(PgVectorInstaller.class);

    /** How the last attempt to lay the extension down ended. */
    public enum State {
        /** Never tried: the application runs on an external PostgreSQL, or has not started one yet. */
        NOT_ATTEMPTED,
        /** No release builds pgvector for this OS and CPU architecture. */
        NO_BUILD_FOR_PLATFORM,
        /** This jar was packaged without the binaries — a local {@code mvn package}, not an installer. */
        NOT_IN_JAR,
        /** The files are in place; PostgreSQL can {@code CREATE EXTENSION vector}. */
        INSTALLED,
        /** The files could not be copied; {@link Outcome#detail()} carries the error. */
        FAILED
    }

    /**
     * @param platform the build directory this machine maps to, or null when none does
     * @param detail   one sentence for a person, saying what happened and what would change it
     */
    public record Outcome(State state, String platform, String detail) {
        public boolean installed() {
            return state == State.INSTALLED;
        }
    }

    private static volatile Outcome outcome = new Outcome(State.NOT_ATTEMPTED, null,
            "pgvector is not installed by this application on an external PostgreSQL.");

    /** What the last {@link #installInto} did, for anything that needs to explain a missing extension. */
    public static Outcome outcome() {
        return outcome;
    }

    /**
     * Where the binaries live in the jar: {@code /pgvector/<os>-<arch>/}. Populated by the build;
     * empty in a plain local build, which is why every path here tolerates them being missing.
     */
    private static final String RESOURCE_ROOT = "/pgvector/";

    /** The shared library, and the metadata files PostgreSQL reads to know the extension exists. */
    private static final String CONTROL = "vector.control";

    private PgVectorInstaller() {
    }

    /**
     * Copies the extension into an unpacked PostgreSQL distribution.
     *
     * @param pgHome the directory zonky unpacked into — the one containing {@code bin}, {@code lib}
     *               and {@code share}
     */
    static void installInto(Path pgHome) {
        String platform = platform();
        if (platform == null) {
            String detail = "No release builds pgvector for " + System.getProperty("os.name") + " on "
                    + System.getProperty("os.arch") + "; the installers cover Windows, Linux and "
                    + "macOS on x86-64, and macOS on Apple Silicon.";
            outcome = new Outcome(State.NO_BUILD_FOR_PLATFORM, null, detail);
            log.debug("No pgvector build for this platform; MCP tool search will rank lexically.");
            return;
        }

        String library = libraryName();
        // The control file is the one PostgreSQL looks for to decide the extension exists at all,
        // so its presence in the jar is the test for "was this build given pgvector".
        if (PgVectorInstaller.class.getResource(RESOURCE_ROOT + platform + "/" + CONTROL) == null) {
            outcome = new Outcome(State.NOT_IN_JAR, platform, "This jar was packaged without "
                    + "pgvector for " + platform + ". The installers from the release workflow "
                    + "carry it; a jar built locally with `mvn package` does not, unless the "
                    + "extension was staged under src/main/resources/pgvector/" + platform
                    + " first.");
            log.info("This build carries no pgvector for {}, so MCP tool search will rank by word "
                    + "overlap rather than meaning.", platform);
            return;
        }

        try {
            // lib/ holds the shared library; share/extension/ the control and SQL files. Both exist
            // already in any PostgreSQL distribution, but create them rather than assume.
            Path lib = Files.createDirectories(pgHome.resolve("lib"));
            Path extension = Files.createDirectories(pgHome.resolve("share").resolve("extension"));

            copy(platform, library, lib.resolve(library));
            copy(platform, CONTROL, extension.resolve(CONTROL));
            for (String sql : sqlFiles(platform)) {
                copy(platform, sql, extension.resolve(sql));
            }
            outcome = new Outcome(State.INSTALLED, platform, "pgvector for " + platform
                    + " is installed in " + pgHome + ".");
            log.info("Installed pgvector into {} — MCP tool search can rank semantically.", pgHome);
        } catch (IOException | RuntimeException e) {
            // Never fatal: the database is far more important than the ranking quality of one
            // feature, and that feature already has a working fallback.
            outcome = new Outcome(State.FAILED, platform, "pgvector for " + platform
                    + " is in this build but could not be copied into " + pgHome + ": "
                    + e.getMessage());
            log.warn("Could not install pgvector ({}); MCP tool search will rank lexically.",
                    e.getMessage());
        }
    }

    /**
     * The SQL files shipped alongside the control file.
     *
     * <p>Listed by the build rather than discovered, because a jar is not a directory that can be
     * walked portably. The manifest is written next to the binaries by the packaging step.
     */
    private static List<String> sqlFiles(String platform) throws IOException {
        try (InputStream in = PgVectorInstaller.class
                .getResourceAsStream(RESOURCE_ROOT + platform + "/sql-files.txt")) {
            if (in == null) return List.of();
            return new String(in.readAllBytes())
                    .lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
    }

    private static void copy(String platform, String name, Path target) throws IOException {
        try (InputStream in = PgVectorInstaller.class
                .getResourceAsStream(RESOURCE_ROOT + platform + "/" + name)) {
            if (in == null) throw new IOException("missing " + name + " for " + platform);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Matches the directory names the build produces, or null where nothing is built. */
    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        // macOS is the one platform built for two architectures — the release runs an Apple
        // Silicon runner and an Intel one. Everything else is x86-64 only, matching the
        // installers; an arch nobody builds for gets lexical ranking.
        if (os.contains("mac") || os.contains("darwin")) {
            if (arch.equals("aarch64") || arch.equals("arm64")) return "darwin-arm64";
            return x64 ? "darwin-amd64" : null;
        }
        if (!x64) return null;
        if (os.contains("win")) return "windows-amd64";
        if (os.contains("linux")) return "linux-amd64";
        return null;
    }

    private static String libraryName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "vector.dll";
        // PostgreSQL 16+ names loadable modules .dylib on macOS, and pgvector's Makefile follows.
        if (os.contains("mac") || os.contains("darwin")) return "vector.dylib";
        return "vector.so";
    }
}
