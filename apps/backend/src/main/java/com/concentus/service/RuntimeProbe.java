package com.concentus.service;

import com.concentus.integration.content.TesseractLocator;
import com.concentus.model.RuntimeCheck;
import com.concentus.model.RuntimeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Which runtimes stdio MCP servers need, and whether this machine has them.
 *
 * <p>An MCP server configured as {@code uvx some-server} does not fail when it is added — it fails
 * on the first run, from inside the CLI, as a process that could not start. This probe moves that
 * discovery to the moment the server is configured, where it can still be acted on.
 *
 * <p>One entry is not a launcher: Tesseract, the OCR program the knowledge ingest runs for
 * scanned PDFs and images. It sits in the same list because the question is the same — is this
 * installed, and if not, what installs it — and the panel that answers it already exists.
 *
 * <p>Detection only. Installing is {@link RuntimeInstaller}'s job, and only on a desktop: the
 * backend running on a server must not let an authenticated request change what is on the host.
 */
@Service
public class RuntimeProbe {

    private static final Logger log = LoggerFactory.getLogger(RuntimeProbe.class);

    /** How long a probe result is reused. Long enough for a panel's renders, short enough that an install shows up. */
    private static final long CACHE_MS = 30_000;
    /** A version probe that has not answered by now is not going to; the UI is waiting on it. */
    private static final long PROBE_TIMEOUT_SECONDS = 6;

    /** The runtimes this app knows about, in the order the UI lists them. */
    private static final List<Runtime> RUNTIMES = List.of(
            new Runtime("node", "Node.js", () -> "node", "MCP servers launched with node",
                    "https://nodejs.org/en/download"),
            new Runtime("npm", "npm", () -> "npm", "MCP servers launched with npx or npm",
                    "https://nodejs.org/en/download"),
            new Runtime("pnpm", "pnpm", () -> "pnpm", "MCP servers launched with pnpm or pnpm dlx",
                    "https://pnpm.io/installation"),
            new Runtime("python", "Python", () -> "python", "MCP servers launched with python",
                    "https://www.python.org/downloads/"),
            new Runtime("pipx", "pipx", () -> "pipx", "MCP servers launched with pipx",
                    "https://pipx.pypa.io/stable/installation/"),
            new Runtime("uv", "uv", () -> "uv", "MCP servers launched with uvx or uv",
                    "https://docs.astral.sh/uv/getting-started/installation/"),
            // Looked up each time rather than named: the Windows installer does not put it on
            // the PATH, so the probe has to run the file where it was installed.
            new Runtime("tesseract", "Tesseract OCR", TesseractLocator::probeCommand,
                    "Reading scanned PDFs and image attachments into knowledge bases (OCR)",
                    "https://tesseract-ocr.github.io/tessdoc/Installation.html"));

    /**
     * First word of a command -> the runtime that provides it.
     *
     * <p>A heuristic, and an honest one: it reads the launcher, not the server. {@code npx} needs
     * npm, {@code uvx} ships with uv, {@code python3} is Python. Anything not in this table is
     * reported as unmanaged rather than missing — see {@link RuntimeCheck}.
     */
    private static final Map<String, String> LAUNCHER_TO_RUNTIME = Map.ofEntries(
            Map.entry("node", "node"),
            Map.entry("npx", "npm"),
            Map.entry("npm", "npm"),
            Map.entry("pnpm", "pnpm"),
            Map.entry("pnpx", "pnpm"),
            Map.entry("python", "python"),
            Map.entry("python3", "python"),
            Map.entry("py", "python"),
            Map.entry("pipx", "pipx"),
            Map.entry("uv", "uv"),
            Map.entry("uvx", "uv"));

    /** Runs {@code <command> --version} and returns its first line, or null when it did not run. */
    private final Function<String, String> versionOf;
    private final Map<String, Cached> cache = new LinkedHashMap<>();

    // @Autowired on the bean's constructor because a second (test) constructor exists: with two
    // candidates and no annotation Spring refuses the bean outright ("No default constructor
    // found"). This has bitten three other services in this codebase.
    @org.springframework.beans.factory.annotation.Autowired
    public RuntimeProbe() {
        this(RuntimeProbe::probeVersion);
    }

    /** Test seam: the probe is the only part that touches the host. */
    RuntimeProbe(Function<String, String> versionOf) {
        this.versionOf = versionOf;
    }

    /** Every known runtime and whether it is installed. */
    public List<RuntimeStatus> all(boolean refresh) {
        List<RuntimeStatus> out = new ArrayList<>(RUNTIMES.size());
        for (Runtime r : RUNTIMES) out.add(status(r, refresh));
        return out;
    }

    /**
     * What a configured MCP command needs. An empty command needs nothing — a remote MCP server
     * launches no process at all, and reporting a missing runtime for one would be nonsense.
     */
    public RuntimeCheck check(String command, boolean refresh) {
        String cmd = command == null ? "" : command.trim();
        if (cmd.isEmpty()) return RuntimeCheck.unmanaged(cmd);
        String runtimeId = LAUNCHER_TO_RUNTIME.get(launcherOf(cmd));
        if (runtimeId == null) return RuntimeCheck.unmanaged(cmd);
        Runtime runtime = RUNTIMES.stream().filter(r -> r.id().equals(runtimeId)).findFirst().orElse(null);
        if (runtime == null) return RuntimeCheck.unmanaged(cmd);
        RuntimeStatus status = status(runtime, refresh);
        return new RuntimeCheck(cmd, status, status.found());
    }

    /**
     * The launcher a command line starts with, normalised: the last path segment, without a
     * Windows executable suffix and lowercased. {@code C:\Program Files\nodejs\npx.cmd} and
     * {@code npx} are the same launcher, and a user who pasted a full path from a README deserves
     * the same answer as one who typed the bare name.
     */
    static String launcherOf(String command) {
        String first = firstToken(command.trim());
        first = first.replace('\\', '/');
        int slash = first.lastIndexOf('/');
        if (slash >= 0) first = first.substring(slash + 1);
        first = first.toLowerCase(Locale.ROOT);
        for (String suffix : new String[] {".exe", ".cmd", ".bat", ".ps1"}) {
            if (first.endsWith(suffix)) return first.substring(0, first.length() - suffix.length());
        }
        return first;
    }

    /**
     * The command's first token, honouring quotes.
     *
     * <p>{@code "C:\Program Files\nodejs\npx.cmd"} is one token, which is how a path with spaces
     * is written in an mcp.json snippet. An UNQUOTED path with spaces is genuinely ambiguous — no
     * parser can tell it from a command with arguments — and this returns its first word, which
     * then reads as an unknown launcher: nothing claimed, nothing broken.
     */
    private static String firstToken(String command) {
        if (command.isEmpty()) return "";
        char quote = command.charAt(0);
        if (quote == '"' || quote == '\'') {
            int close = command.indexOf(quote, 1);
            if (close > 0) return command.substring(1, close);
        }
        return command.split("\\s+")[0];
    }

    private RuntimeStatus status(Runtime runtime, boolean refresh) {
        long now = System.currentTimeMillis();
        synchronized (cache) {
            Cached hit = cache.get(runtime.id());
            if (!refresh && hit != null && now - hit.at() < CACHE_MS) return hit.status();
        }
        String version = versionOf.apply(runtime.probeCommand().get());
        boolean found = version != null && !version.isBlank();
        RuntimeStatus status = new RuntimeStatus(runtime.id(), runtime.label(), found,
                found ? version.trim() : "", runtime.neededFor(), runtime.docsUrl());
        synchronized (cache) {
            cache.put(runtime.id(), new Cached(status, now));
        }
        return status;
    }

    /**
     * Asks a command for its version through the platform shell.
     *
     * <p>Through a shell on purpose: on Windows the interesting launchers are {@code .cmd} shims
     * (npm, pnpm, npx) that {@code ProcessBuilder} cannot execute directly, and on Unix this is
     * also what resolves a PATH the backend inherited from its launcher. Nothing user-supplied
     * reaches here — the argument is one of the fixed names in {@link #RUNTIMES}.
     */
    private static String probeVersion(String command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> argv = windows
                ? List.of("cmd.exe", "/c", command + " --version")
                : List.of("/bin/sh", "-c", command + " --version");
        Process process = null;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            String first;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                first = reader.readLine();
            }
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            // Exit code decides, not the output: a missing command still prints a line ("'npm' is
            // not recognized"), and treating that as a version would report every absent runtime
            // as installed.
            return process.exitValue() == 0 ? first : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("Runtime probe for {} failed: {}", command, e.getMessage());
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    /** @param probeCommand what to ask for {@code --version}; supplied per probe, see the OCR entry */
    private record Runtime(String id, String label, Supplier<String> probeCommand, String neededFor,
                           String docsUrl) {
    }

    private record Cached(RuntimeStatus status, long at) {
    }
}
