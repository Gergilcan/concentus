package com.concentus.service;

import com.concentus.model.McpServerInfo;
import com.concentus.support.LocalClaudeSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads and updates the local Claude Code MCP server list via the {@code claude mcp} CLI. */
@Component
public class McpRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpRegistry.class);

    /**
     * Server names accepted by the sign-in flow: letters, digits, space, dot, dash, underscore
     * (1–64 chars). Spaces are allowed because real MCP servers use them ("claude.ai Google Drive"),
     * but every shell/batch metacharacter ({@code " & | ; ` % $ < > ( ) newline}) is excluded, so a
     * name can never break out of quoting in a spawned terminal. Validated at the controller
     * boundary and re-checked here.
     */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9 ._-]{1,64}");

    public static boolean isSafeName(String name) {
        return name != null && SAFE_NAME.matcher(name).matches();
    }
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
        CliProcess.Result r = CliProcess.run(List.of(cmd, "mcp", "list"), 30);
        List<McpServerInfo> out = new ArrayList<>();
        for (String line : r.output().split("\\R")) {
            Matcher m = LINE.matcher(line.trim());
            if (m.matches()) {
                out.add(new McpServerInfo(m.group(1).trim(), m.group(2).trim(),
                        m.group(3) == null ? "" : m.group(3).trim()));
            }
        }
        return out;
    }

    /**
     * The header a server expects its credential in when none is configured.
     *
     * <p>{@code Authorization: Bearer …} is what most MCP servers take, but not all: GitLab's API
     * canonically reads its project, group and personal access tokens from {@code PRIVATE-TOKEN},
     * with no {@code Bearer} prefix. Hard-coding the header is therefore what stops a GitLab
     * server working at all, which is why it is configurable per node.
     */
    public static final String DEFAULT_AUTH_HEADER = "Authorization";

    /** Adds an HTTP MCP server to the user scope. Returns a short human-readable status. */
    public String add(String name, String url, String token) {
        return add(name, url, token, DEFAULT_AUTH_HEADER);
    }

    /**
     * @param authHeader header to send the credential in; blank falls back to
     *                   {@link #DEFAULT_AUTH_HEADER}. The {@code Bearer } prefix is added only for
     *                   {@code Authorization} — it is specific to that header, and prepending it to
     *                   a {@code PRIVATE-TOKEN} value would simply make the token wrong.
     */
    public String add(String name, String url, String token, String authHeader) {
        String cmd = support.command().orElse(null);
        if (cmd == null) return "claude CLI not found";
        if (name == null || name.isBlank() || url == null || url.isBlank()) return "missing name/url";

        List<String> args = new ArrayList<>(List.of(cmd, "mcp", "add", "--transport", "http", name, url, "-s", "user"));
        boolean hasToken = token != null && !token.isBlank();
        if (hasToken) {
            String header = authHeader == null || authHeader.isBlank() ? DEFAULT_AUTH_HEADER : authHeader.trim();
            String prefix = DEFAULT_AUTH_HEADER.equalsIgnoreCase(header) ? "Bearer " : "";
            args.add("-H");
            args.add(header + ": " + prefix + token);
        }
        CliProcess.Result r = CliProcess.run(args, 30);
        String out = r.output() == null ? "" : r.output();
        if (out.toLowerCase().contains("already exists")) {
            return "already configured";
        }
        if (r.exit() == 0) {
            return hasToken ? "added" : "added — run `claude mcp login \"" + name + "\"` to authorize";
        }
        log.warn("claude mcp add failed for {}: {}", name, out);
        return "add failed: " + CliProcess.lastLine(out);
    }

    /**
     * Whether an interactive OAuth sign-in can actually be completed on this host.
     *
     * <p>False in a container, and that is the common case: the login needs a terminal window and
     * a browser, and a headless Linux deployment has neither. Reported so the UI can offer a token
     * instead of a button that cannot work — the alternative is a sign-in that appears to start
     * and silently never finishes.
     *
     * <p>Both GitHub and GitLab issue long-lived tokens that need no interactive step, so this
     * being false is a routing decision rather than a limitation.
     */
    public boolean supportsInteractiveLogin() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") || os.contains("mac");
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
        // Defence in depth: the controller rejects unsafe names, but never build a terminal
        // command around one that slipped through another caller.
        if (!isSafeName(name)) return "invalid server name";

        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                // The script text carries no untrusted data — it refers to the login name only
                // through the batch parameter %~1 (~ strips the quotes ProcessBuilder adds around
                // an argument containing spaces). The name is handed over as a discrete argv entry.
                Path script = Files.createTempFile("mcp-login-", ".cmd");
                String body = "@echo off\r\n"
                        + "title Authorize %~1 - MCP sign-in\r\n"
                        + "echo Signing in to \"%~1\". A browser will open; approve access,\r\n"
                        + "echo then paste the redirect URL back here if prompted.\r\n"
                        + "echo.\r\n"
                        + "\"" + cmd + "\" mcp login \"%~1\"\r\n"
                        + "echo.\r\n"
                        + "echo Done - return to Concentus and click \"Recheck\".\r\n"
                        + "pause\r\n"
                        + "del \"%~f0\"\r\n";
                Files.writeString(script, body);
                script.toFile().deleteOnExit();
                new ProcessBuilder(
                        "cmd.exe", "/c", "start", "", "cmd.exe", "/c", script.toString(), name)
                        .start();
                return "A terminal window opened — finish the sign-in there, then click Recheck.";
            }
            if (os.contains("mac")) {
                // Same rule as Windows: the name never appears in script text. It is written to a
                // sibling data file the script reads at run time, and Terminal is launched with
                // `open` (argv) rather than an interpolated AppleScript string.
                Path nameFile = Files.createTempFile("mcp-login-", ".name");
                Files.writeString(nameFile, name);
                Path script = Files.createTempFile("mcp-login-", ".command");
                String body = "#!/bin/sh\n"
                        + "NAME=$(cat " + shellQuote(nameFile.toString()) + ")\n"
                        + "printf 'Signing in to %s. A browser will open; approve access,\\n' \"$NAME\"\n"
                        + "printf 'then paste the redirect URL back here if prompted.\\n\\n'\n"
                        + shellQuote(cmd) + " mcp login \"$NAME\"\n"
                        + "printf '\\nDone - return to Concentus and click \"Recheck\".\\n'\n"
                        + "rm -f " + shellQuote(nameFile.toString()) + " \"$0\"\n";
                Files.writeString(script, body);
                makeExecutable(script);
                nameFile.toFile().deleteOnExit();
                script.toFile().deleteOnExit();
                new ProcessBuilder("open", "-a", "Terminal", script.toString()).start();
                return "A Terminal window opened — finish the sign-in there, then click Recheck.";
            }
            // Linux / other: no reliable terminal to spawn — hand back the command as text.
            return "Run `" + cmd + " mcp login \"" + name + "\"` in a terminal, then click Recheck.";
        } catch (Exception e) {
            log.warn("could not launch login terminal for {}: {}", name, e.toString());
            return "Couldn't open a terminal — run `claude mcp login \"" + name + "\"` manually, then Recheck.";
        }
    }

    /** Single-quotes a value for POSIX sh, escaping any embedded single quotes. */
    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void makeExecutable(Path script) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(script, perms);
        } catch (Exception e) {
            log.debug("could not chmod {}: {}", script, e.getMessage());
        }
    }

    /** Removes a server from the user's Claude Code list. Returns a short status. */
    public String remove(String name) {
        String cmd = support.command().orElse(null);
        if (cmd == null) return "claude CLI not found";
        if (name == null || name.isBlank()) return "missing name";
        CliProcess.Result r = CliProcess.run(List.of(cmd, "mcp", "remove", name, "-s", "user"), 30);
        if (r.exit() == 0) return "removed";
        return "remove failed: " + CliProcess.lastLine(r.output());
    }

}
