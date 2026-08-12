package com.concentus.service;

import com.concentus.support.LocalClaudeSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Reads and updates the local Claude Code plugin list via the {@code claude plugin} CLI.
 *
 * <p>The CLI is the one honest interface: {@code plugin list --json} is a published output format,
 * while the files under {@code ~/.claude/plugins/} are internals that move between CLI versions.
 * Plugins only exist on the Claude backend — a self-hosted model has no CLI to load them.
 */
@Component
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

    /**
     * Plugin ids are {@code name} or {@code name@marketplace}, both slug-like. Validated at the
     * controller boundary and re-checked here; the id is always passed as a discrete argv entry,
     * never through a shell, so this is belt and braces.
     */
    private static final Pattern SAFE_ID =
            Pattern.compile("[A-Za-z0-9._-]{1,64}(@[A-Za-z0-9._-]{1,64})?");

    /** A marketplace source: URL, path, or owner/repo. Anything without whitespace is passable. */
    private static final Pattern SAFE_SOURCE = Pattern.compile("\\S{1,256}");

    public static boolean isSafeId(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    public static boolean isSafeSource(String source) {
        return source != null && SAFE_SOURCE.matcher(source).matches();
    }

    private final LocalClaudeSupport support;
    private final ObjectMapper mapper;

    public PluginRegistry(LocalClaudeSupport support, ObjectMapper mapper) {
        this.support = support;
        this.mapper = mapper;
    }

    public record PluginInfo(String id, String version, String scope, boolean enabled) {
    }

    public record MarketplaceInfo(String name, String source) {
    }

    /** A short cache: runs ask for the installed list on every turn's process launch. */
    private volatile List<PluginInfo> cachedList;
    private volatile long cachedAt;
    private static final long CACHE_TTL_MS = 30_000;

    /** Installed plugins, as `claude plugin list --json` reports them. Empty when no CLI. */
    public List<PluginInfo> list() {
        List<PluginInfo> cached = cachedList;
        if (cached != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) return cached;
        List<PluginInfo> fresh = listUncached();
        cachedList = fresh;
        cachedAt = System.currentTimeMillis();
        return fresh;
    }

    private List<PluginInfo> listUncached() {
        String cmd = support.command().orElse(null);
        if (cmd == null) return List.of();
        ProcResult r = run(List.of(cmd, "plugin", "list", "--json"), 30);
        List<PluginInfo> out = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(r.output == null ? "[]" : r.output.trim());
            if (arr.isArray()) {
                for (JsonNode p : arr) {
                    String id = p.path("id").asText("");
                    if (id.isBlank()) continue;
                    out.add(new PluginInfo(id, p.path("version").asText(""),
                            p.path("scope").asText(""), p.path("enabled").asBoolean(false)));
                }
            }
        } catch (Exception e) {
            log.warn("claude plugin list --json could not be parsed: {}", e.getMessage());
        }
        return out;
    }

    public List<MarketplaceInfo> marketplaces() {
        String cmd = support.command().orElse(null);
        if (cmd == null) return List.of();
        ProcResult r = run(List.of(cmd, "plugin", "marketplace", "list", "--json"), 30);
        List<MarketplaceInfo> out = new ArrayList<>();
        try {
            JsonNode arr = mapper.readTree(r.output == null ? "[]" : r.output.trim());
            if (arr.isArray()) {
                for (JsonNode m : arr) {
                    String name = m.path("name").asText("");
                    if (name.isBlank()) continue;
                    // The source is an object ({source: github, repo: …}) or a plain string,
                    // depending on how it was added — flattened to something readable either way.
                    JsonNode src = m.path("source");
                    String source = src.isObject()
                            ? src.path("repo").asText(src.path("url").asText(src.path("path").asText("")))
                            : src.asText("");
                    out.add(new MarketplaceInfo(name, source));
                }
            }
        } catch (Exception e) {
            log.warn("claude plugin marketplace list --json could not be parsed: {}", e.getMessage());
        }
        return out;
    }

    /**
     * The {@code --settings} JSON that makes a run load exactly {@code selected}: the selection
     * is enabled and every other installed plugin disabled, so a flow behaves the same wherever
     * its plugins are installed. Null when nothing is selected — the CLI's own defaults (the
     * user's globally enabled plugins) then apply, which is the "I didn't ask for anything"
     * contract rather than "strip my plugins".
     */
    public String settingsJsonFor(java.util.Collection<String> selected) {
        if (selected == null || selected.stream().noneMatch(PluginRegistry::isSafeId)) return null;
        var root = mapper.createObjectNode();
        var enabled = root.putObject("enabledPlugins");
        for (PluginInfo p : list()) enabled.put(p.id(), false);
        for (String id : selected) if (isSafeId(id)) enabled.put(id, true);
        return root.toString();
    }

    /** Installs into the user scope (the CLI default), so every flow can select it. */
    public String install(String id) {
        String cmd = support.command().orElse(null);
        if (cmd == null) return "claude CLI not found";
        if (!isSafeId(id)) return "invalid plugin id";
        ProcResult r = run(List.of(cmd, "plugin", "install", id), 120);
        cachedList = null; // after the CLI finished, so a concurrent list() can't re-cache stale state
        return r.exit == 0 ? "installed" : "install failed: " + firstLine(r.output);
    }

    /** One plugin a configured marketplace offers, as the install search shows it. */
    public record AvailablePlugin(String id, String name, String description,
                                  String marketplace, long installCount) {
    }

    private volatile List<AvailablePlugin> cachedAvailable;
    private volatile long availableAt;

    /**
     * Everything the configured marketplaces offer. Cached like the installed list, but the
     * call is a full catalog read (hundreds of entries), so the TTL is what makes typing in
     * the search box tolerable — the UI filters client-side against this one snapshot.
     */
    public List<AvailablePlugin> available() {
        List<AvailablePlugin> cached = cachedAvailable;
        if (cached != null && System.currentTimeMillis() - availableAt < CACHE_TTL_MS) return cached;
        String cmd = support.command().orElse(null);
        if (cmd == null) return List.of();
        ProcResult r = run(List.of(cmd, "plugin", "list", "--available", "--json"), 60);
        List<AvailablePlugin> out = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(r.output == null ? "{}" : r.output.trim());
            for (JsonNode p : root.path("available")) {
                String id = p.path("pluginId").asText("");
                if (id.isBlank()) continue;
                out.add(new AvailablePlugin(id,
                        p.path("name").asText(id),
                        oneLine(p.path("description").asText("")),
                        p.path("marketplaceName").asText(""),
                        p.path("installCount").asLong(0)));
            }
        } catch (Exception e) {
            log.warn("claude plugin list --available --json could not be parsed: {}", e.getMessage());
        }
        cachedAvailable = out;
        availableAt = System.currentTimeMillis();
        return out;
    }

    /** Catalog descriptions run to paragraphs; the search row has room for one line. */
    private static String oneLine(String description) {
        String first = description == null ? "" : description.strip();
        int stop = first.indexOf('\n');
        if (stop > 0) first = first.substring(0, stop);
        return first.length() <= 160 ? first : first.substring(0, 160) + "…";
    }

    /** Flips a plugin on or off without uninstalling it — what the toggle in Resources calls. */
    public String setEnabled(String id, boolean enabled) {
        String cmd = support.command().orElse(null);
        if (cmd == null) return "claude CLI not found";
        if (!isSafeId(id)) return "invalid plugin id";
        ProcResult r = run(List.of(cmd, "plugin", enabled ? "enable" : "disable", id), 60);
        cachedList = null;
        return r.exit == 0 ? (enabled ? "enabled" : "disabled")
                : (enabled ? "enable" : "disable") + " failed: " + firstLine(r.output);
    }

    public String uninstall(String id) {
        String cmd = support.command().orElse(null);
        if (cmd == null) return "claude CLI not found";
        if (!isSafeId(id)) return "invalid plugin id";
        ProcResult r = run(List.of(cmd, "plugin", "uninstall", id), 60);
        cachedList = null;
        return r.exit == 0 ? "uninstalled" : "uninstall failed: " + firstLine(r.output);
    }

    public String marketplaceAdd(String source) {
        String cmd = support.command().orElse(null);
        if (cmd == null) return "claude CLI not found";
        if (!isSafeSource(source)) return "invalid marketplace source";
        // Cloning a marketplace repo can be slow; the timeout reflects a git clone, not an RPC.
        ProcResult r = run(List.of(cmd, "plugin", "marketplace", "add", source), 180);
        return r.exit == 0 ? "added" : "add failed: " + firstLine(r.output);
    }

    public String marketplaceRemove(String name) {
        String cmd = support.command().orElse(null);
        if (cmd == null) return "claude CLI not found";
        if (!isSafeId(name)) return "invalid marketplace name";
        ProcResult r = run(List.of(cmd, "plugin", "marketplace", "remove", name), 60);
        return r.exit == 0 ? "removed" : "remove failed: " + firstLine(r.output);
    }

    // ------------------------------------------------------------- process

    private record ProcResult(int exit, String output) {
    }

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
