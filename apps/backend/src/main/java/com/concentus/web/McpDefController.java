package com.concentus.web;

import com.concentus.model.McpDef;
import com.concentus.store.McpDefStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** CRUD for reusable MCP server definitions (distinct from the live Claude Code list at /api/mcp). */
@RestController
@RequestMapping("/api/mcp-defs")
public class McpDefController {

    /** The keys a server object in the JSON view may carry; anything else is warned about. */
    private static final Set<String> KNOWN_KEYS =
            Set.of("url", "credentialId", "authHeader", "command", "args", "env", "type", "headers");

    private final McpDefStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpDefController(McpDefStore store) {
        this.store = store;
    }

    @GetMapping
    public List<McpDef> list() {
        return store.list();
    }

    @PostMapping
    public McpDef save(@RequestBody McpDef def) {
        boolean hasUrl = def != null && def.url() != null && !def.url().isBlank();
        if (def == null || def.name() == null || def.name().isBlank() || (!hasUrl && !def.isStdio())) {
            throw new IllegalArgumentException("name plus a url or a command are required.");
        }
        return store.save(def);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        store.delete(id);
    }

    /**
     * The whole registry as one editable document, in mcp.json's own shape.
     *
     * <p>This is the view that makes external servers installable by paste: an MCP README says
     * "add this to your mcp.json", and this document accepts exactly that snippet — including the
     * stdio form with command/args/env. What it never contains is a secret: HTTP servers carry a
     * {@code credentialId} reference, and a stdio env value can be {@code credential:<id>}, both
     * resolved only when a run's config is written.
     */
    @GetMapping("/json")
    public JsonNode registryJson() {
        ObjectNode servers = mapper.createObjectNode();
        for (McpDef def : store.list()) {
            ObjectNode server = mapper.createObjectNode();
            if (def.isStdio()) {
                server.put("command", def.command());
                var args = server.putArray("args");
                if (def.args() != null) def.args().forEach(args::add);
                if (def.env() != null && !def.env().isEmpty()) {
                    ObjectNode env = server.putObject("env");
                    def.env().forEach(env::put);
                }
            } else {
                server.put("url", def.url() == null ? "" : def.url());
                if (def.credentialId() != null && !def.credentialId().isBlank()) {
                    server.put("credentialId", def.credentialId());
                }
                if (def.authHeader() != null && !def.authHeader().isBlank()) {
                    server.put("authHeader", def.authHeader());
                }
            }
            servers.set(def.name(), server);
        }
        ObjectNode root = mapper.createObjectNode();
        root.set("mcpServers", servers);
        return root;
    }

    /**
     * Replaces the registry with what the document says: servers are matched to existing
     * definitions by name (case-insensitive), new names are created, absent names are deleted —
     * the same contract editing a real mcp.json has. Unknown keys are ignored but named in the
     * response, and {@code headers} gets a pointed warning: a pasted literal token belongs in
     * Resources → Credentials, not in this document.
     */
    @PutMapping("/json")
    public Map<String, Object> saveRegistryJson(@RequestBody JsonNode body) {
        JsonNode serversNode = body != null && body.has("mcpServers") ? body.get("mcpServers") : body;
        if (serversNode == null || !serversNode.isObject()) {
            throw new IllegalArgumentException(
                    "Expected {\"mcpServers\": { \"name\": { … } }} — the mcp.json shape.");
        }

        List<String> warnings = new ArrayList<>();
        Map<String, McpDef> existing = new HashMap<>();
        for (McpDef def : store.list()) existing.put(def.name().toLowerCase(Locale.ROOT), def);

        int saved = 0;
        Set<String> seen = new java.util.HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = serversNode.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String name = entry.getKey().trim();
            JsonNode server = entry.getValue();
            if (name.isBlank() || !server.isObject()) continue;

            for (String key : (Iterable<String>) () -> server.fieldNames()) {
                if (!KNOWN_KEYS.contains(key)) {
                    warnings.add("'" + name + "': unknown key '" + key + "' ignored.");
                }
            }
            if (server.has("headers")) {
                warnings.add("'" + name + "': 'headers' is ignored — store the token under "
                        + "Resources → Credentials and reference it as credentialId "
                        + "(or env value credential:<id> for stdio).");
            }

            String command = server.path("command").asText("");
            String url = server.path("url").asText("");
            if (command.isBlank() && url.isBlank()) {
                throw new IllegalArgumentException("'" + name + "' needs a url or a command.");
            }
            List<String> args = new ArrayList<>();
            server.path("args").forEach(a -> args.add(a.asText()));
            Map<String, String> env = new LinkedHashMap<>();
            if (server.path("env").isObject()) {
                server.path("env").properties()
                        .forEach(e -> env.put(e.getKey(), e.getValue().asText("")));
            }

            McpDef previous = existing.get(name.toLowerCase(Locale.ROOT));
            store.save(new McpDef(previous == null ? null : previous.id(), name,
                    command.isBlank() ? url : null,
                    blankToNull(server.path("credentialId").asText("")),
                    blankToNull(server.path("authHeader").asText("")),
                    blankToNull(command), args, env));
            seen.add(name.toLowerCase(Locale.ROOT));
            saved++;
        }

        int deleted = 0;
        for (Map.Entry<String, McpDef> e : existing.entrySet()) {
            if (!seen.contains(e.getKey())) {
                store.delete(e.getValue().id());
                deleted++;
            }
        }
        return Map.of("saved", saved, "deleted", deleted, "warnings", warnings);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
