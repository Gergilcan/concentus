package com.concentus.service;

import com.concentus.model.McpServerInfo;
import com.concentus.support.LocalClaudeSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads and updates the local Claude Code MCP server list via the {@code claude mcp} CLI. */
@Component
public class McpRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpRegistry.class);
    // "Rovo: https://mcp.atlassian.com/v1/mcp - ! Needs authentication"
    // "Linear: https://mcp.linear.app/mcp (HTTP) - ✔ Connected"   (note the "(HTTP)" transport marker)
    private static final Pattern LINE =
            Pattern.compile("^(.+?): (https?://\\S+)(?:\\s+\\([^)]*\\))?(?:\\s+-\\s+(.*))?$");

    private final LocalClaudeSupport support;

    public McpRegistry(LocalClaudeSupport support) {
        this.support = support;
    }

    public List<McpServerInfo> list() {
        String cmd = support.command().orElse(null);
        if (cmd == null) return List.of();
        ProcResult r = run(List.of(cmd, "mcp", "list"), 30);
        List<McpServerInfo> out = new ArrayList<>();
        for (String line : r.output.split("\\R")) {
            Matcher m = LINE.matcher(line.trim());
            if (m.matches()) {
                out.add(new McpServerInfo(m.group(1).trim(), m.group(2).trim(),
                        m.group(3) == null ? "" : m.group(3).trim()));
            }
        }
        return out;
    }

    /** Adds an HTTP MCP server to the user scope. Returns a short human-readable status. */
    public String add(String name, String url, String token) {
        String cmd = support.command().orElse(null);
        if (cmd == null) return "claude CLI not found";
        if (name == null || name.isBlank() || url == null || url.isBlank()) return "missing name/url";

        List<String> args = new ArrayList<>(List.of(cmd, "mcp", "add", "--transport", "http", name, url, "-s", "user"));
        boolean hasToken = token != null && !token.isBlank();
        if (hasToken) {
            args.add("-H");
            args.add("Authorization: Bearer " + token);
        }
        ProcResult r = run(args, 30);
        String out = r.output == null ? "" : r.output;
        if (out.toLowerCase().contains("already exists")) {
            return "already configured";
        }
        if (r.exit == 0) {
            return hasToken ? "added" : "added — run `claude mcp login \"" + name + "\"` to authorize";
        }
        log.warn("claude mcp add failed for {}: {}", name, out);
        return "add failed: " + firstLine(out);
    }

    /**
     * Starts the interactive OAuth login for a server. {@code claude mcp login} needs a real
     * terminal (TTY) to receive the pasted redirect URL — a piped subprocess can't satisfy that
     * — so instead of running it inline we launch a visible terminal window that runs the login
     * with a proper console. The user completes the browser sign-in there, then re-checks status.
     * Returns immediately.
     */
    public String login(String name) {
        String cmd = support.command().orElse(null);
        if (cmd == null) return "claude CLI not found";
        if (name == null || name.isBlank()) return "missing name";

        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                Path script = Files.createTempFile("mcp-login-", ".cmd");
                String body = "@echo off\r\n"
                        + "title Authorize " + name + " - MCP sign-in\r\n"
                        + "echo Signing in to \"" + name + "\". A browser will open; approve access,\r\n"
                        + "echo then paste the redirect URL back here if prompted.\r\n"
                        + "echo.\r\n"
                        + "\"" + cmd + "\" mcp login \"" + name + "\"\r\n"
                        + "echo.\r\n"
                        + "echo Done - return to Concentus and click \"Recheck\".\r\n"
                        + "pause\r\n";
                Files.writeString(script, body);
                new ProcessBuilder("cmd.exe", "/c", "start", "", "cmd.exe", "/c", script.toString()).start();
                return "A terminal window opened — finish the sign-in there, then click Recheck.";
            }
            if (os.contains("mac")) {
                String inner = cmd + " mcp login \\\"" + name + "\\\"";
                new ProcessBuilder("osascript", "-e",
                        "tell application \"Terminal\" to do script \"" + inner + "\"").start();
                return "A Terminal window opened — finish the sign-in there, then click Recheck.";
            }
            // Linux / other: no reliable terminal to spawn — hand back the command.
            return "Run `" + cmd + " mcp login \"" + name + "\"` in a terminal, then click Recheck.";
        } catch (Exception e) {
            log.warn("could not launch login terminal for {}: {}", name, e.toString());
            return "Couldn't open a terminal — run `claude mcp login \"" + name + "\"` manually, then Recheck.";
        }
    }

    /** Removes a server from the user's Claude Code list. Returns a short status. */
    public String remove(String name) {
        String cmd = support.command().orElse(null);
        if (cmd == null) return "claude CLI not found";
        if (name == null || name.isBlank()) return "missing name";
        ProcResult r = run(List.of(cmd, "mcp", "remove", name, "-s", "user"), 30);
        if (r.exit == 0) return "removed";
        return "remove failed: " + firstLine(r.output);
    }

    // ------------------------------------------------------------- process

    private record ProcResult(int exit, String output) {}

    private ProcResult run(List<String> args, int timeoutSec) {
        try {
            Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
            p.getOutputStream().close();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return "";
                }
            });
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return new ProcResult(-1, "timed out");
            }
            return new ProcResult(p.exitValue(), out.get(3, TimeUnit.SECONDS));
        } catch (Exception e) {
            return new ProcResult(-1, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static String firstLine(String s) {
        if (s == null || s.isBlank()) return "unknown error";
        String[] lines = s.strip().split("\\R");
        return lines[lines.length - 1];
    }
}
