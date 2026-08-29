package com.concentus.service;

import com.concentus.model.RuntimeInstallPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * Installing the runtimes stdio MCP servers need — Node/npm, pnpm, Python, pipx, uv.
 *
 * <p>The rules that make this acceptable, and they are the same ones the app already applies to
 * installing Claude Code:
 *
 * <ul>
 *   <li>Every command is HARDCODED below — the official one each project publishes. Nothing a
 *       user, a setting or an MCP definition can influence is interpolated into it; the request
 *       carries a runtime ID and nothing else.</li>
 *   <li>Nothing runs by itself. The UI shows the exact command next to the button that runs it,
 *       and the output comes back verbatim rather than as "ok" or "failed".</li>
 *   <li>No administrator rights. Where the only real path needs them (Linux system package
 *       managers), there is deliberately NO command — the UI links the instructions instead of
 *       running sudo behind someone's back or guessing their distribution.</li>
 *   <li><b>Desktop installs only.</b> This is the one endpoint that changes the machine rather
 *       than the app's own data, so it is refused wherever accounts are switched on — a server's
 *       authenticated user must not be able to install software on the host.</li>
 * </ul>
 */
@Service
public class RuntimeInstaller {

    private static final Logger log = LoggerFactory.getLogger(RuntimeInstaller.class);

    /** Long enough for winget to fetch and run an installer on a slow connection. */
    private static final int TIMEOUT_SECONDS = 600;
    /** How much of the installer's output comes back. Its last words are the useful part. */
    private static final int MAX_OUTPUT_CHARS = 4000;

    /**
     * The official commands, per platform: {@code windows | mac | linux}.
     *
     * <p>npm has no row of its own because it ships with Node — asking to install npm installs
     * Node, which is also the honest answer for someone whose {@code npx} is missing.
     */
    private static final Map<String, Map<String, String>> COMMANDS = Map.of(
            // winget is present on Windows 11 and current Windows 10; brew is the de-facto macOS
            // route. Linux Node is a distribution package (or nvm) and both want a decision this
            // app should not make for someone.
            "node", Map.of(
                    "windows", "winget install -e --id OpenJS.NodeJS.LTS",
                    "mac", "brew install node"),
            "npm", Map.of(
                    "windows", "winget install -e --id OpenJS.NodeJS.LTS",
                    "mac", "brew install node"),
            // pnpm publishes a standalone installer for all three platforms; no Node, no admin.
            "pnpm", Map.of(
                    "windows", "iwr https://get.pnpm.io/install.ps1 -useb | iex",
                    "mac", "curl -fsSL https://get.pnpm.io/install.sh | sh -",
                    "linux", "curl -fsSL https://get.pnpm.io/install.sh | sh -"),
            "python", Map.of(
                    "windows", "winget install -e --id Python.Python.3.12",
                    "mac", "brew install python"),
            // pipx installs into the user site with pip, which is why it works everywhere: it
            // needs Python, which the UI checks first.
            "pipx", Map.of(
                    "windows", "py -m pip install --user pipx",
                    "mac", "python3 -m pip install --user pipx",
                    "linux", "python3 -m pip install --user pipx"),
            // uv's official installers are user-local on every platform, and uvx comes with it.
            "uv", Map.of(
                    "windows", "powershell -ExecutionPolicy ByPass -c \"irm https://astral.sh/uv/install.ps1 | iex\"",
                    "mac", "curl -LsSf https://astral.sh/uv/install.sh | sh",
                    "linux", "curl -LsSf https://astral.sh/uv/install.sh | sh"),
            // Tesseract, for OCR. UB Mannheim's build is the one the Tesseract project itself
            // points Windows users at; it installs under Program Files without touching the
            // PATH, which is why the probe looks there. Homebrew's formula carries English only —
            // `tesseract-lang` adds the rest, and the ingest log says so when a language is missing.
            "tesseract", Map.of(
                    "windows", "winget install -e --id UB-Mannheim.TesseractOCR",
                    "mac", "brew install tesseract"));

    private static final Map<String, String> NO_COMMAND_REASON = Map.of(
            "node", "On Linux, Node comes from your distribution's package manager (or nvm), which "
                    + "needs a choice this app should not make for you.",
            "npm", "npm ships with Node. On Linux, Node comes from your distribution's package "
                    + "manager (or nvm).",
            "python", "On Linux, Python is part of the system and is managed by your "
                    + "distribution's package manager.",
            "tesseract", "On Linux, Tesseract comes from your distribution's package manager: "
                    + "apt install tesseract-ocr on Debian and Ubuntu, plus tesseract-ocr-spa and "
                    + "tesseract-ocr-cat for Spanish and Catalan.");

    /** Test seam: (argv, timeout) -> exit code and merged output. Nothing else touches the host. */
    private final BiFunction<List<String>, Integer, CliProcess.Result> runner;

    // @Autowired because a second (test) constructor exists — without it Spring refuses the bean.
    @Autowired
    public RuntimeInstaller() {
        this(RuntimeInstaller::run);
    }

    RuntimeInstaller(BiFunction<List<String>, Integer, CliProcess.Result> runner) {
        this.runner = runner;
    }

    /** What installing this runtime would run here, so the UI can show it before anything happens. */
    public RuntimeInstallPlan plan(String runtimeId) {
        String command = COMMANDS.getOrDefault(runtimeId, Map.of()).get(platform());
        if (command != null) return new RuntimeInstallPlan(runtimeId, command, null);
        if (!COMMANDS.containsKey(runtimeId)) {
            return new RuntimeInstallPlan(runtimeId, null, "This app does not manage that runtime.");
        }
        return new RuntimeInstallPlan(runtimeId, null, NO_COMMAND_REASON.getOrDefault(runtimeId,
                "There is no unattended installer for this on your platform."));
    }

    /** Runs the installer. The output comes back whole — its last words are what a person needs. */
    public RuntimeInstallResult install(String runtimeId) {
        RuntimeInstallPlan plan = plan(runtimeId);
        if (plan.command() == null) {
            return new RuntimeInstallResult(false, plan.command(), plan.reason());
        }
        log.info("Installing {}: {}", runtimeId, plan.command());
        CliProcess.Result result = runner.apply(shellArgs(plan.command()), TIMEOUT_SECONDS);
        String output = result.output() == null ? "" : result.output();
        if (output.length() > MAX_OUTPUT_CHARS) {
            output = "…\n" + output.substring(output.length() - MAX_OUTPUT_CHARS);
        }
        if (result.exit() != 0) {
            log.warn("Installer for {} exited with {}.", runtimeId, result.exit());
        }
        return new RuntimeInstallResult(result.exit() == 0, plan.command(), output);
    }

    /** What an install attempt did. {@code output} is the installer's own words, not a summary. */
    public record RuntimeInstallResult(boolean ok, String command, String output) {
    }

    /**
     * The command, through the platform's shell.
     *
     * <p>A shell because these are the published one-liners — pipes and all — and rewriting them
     * into argv arrays would mean maintaining a different command from the one shown to the user.
     * {@code -NoProfile} so a user's PowerShell profile cannot change what runs; a login shell on
     * Unix so the installer sees the PATH it expects and lands where the user's own shell looks.
     */
    private static List<String> shellArgs(String command) {
        return "windows".equals(platform())
                ? List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command)
                : List.of("/bin/bash", "-lc", command);
    }

    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "mac";
        return "linux";
    }

    /** Real execution, kept here so the seam above is the only thing tests replace. */
    private static CliProcess.Result run(List<String> argv, int timeoutSeconds) {
        Process process = null;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            process.getOutputStream().close();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) out.append(line).append('\n');
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new CliProcess.Result(-1, out + "\nTimed out after " + timeoutSeconds + "s.");
            }
            return new CliProcess.Result(process.exitValue(), out.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CliProcess.Result(-1, "Interrupted.");
        } catch (Exception e) {
            return new CliProcess.Result(-1, "Could not run the installer: " + e.getMessage());
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
