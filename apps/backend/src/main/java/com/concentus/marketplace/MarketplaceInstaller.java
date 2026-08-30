package com.concentus.marketplace;

import com.concentus.auth.ConcentusUserDetails;
import com.concentus.auth.OrgContext;
import com.concentus.model.FacadeProfile;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.model.LibraryAgent;
import com.concentus.model.McpDef;
import com.concentus.model.SkillDef;
import com.concentus.service.PluginRegistry;
import com.concentus.service.SkillService;
import com.concentus.store.AgentLibraryStore;
import com.concentus.store.FacadeProfileStore;
import com.concentus.store.FlowStore;
import com.concentus.store.FlowVersionStore;
import com.concentus.store.McpDefStore;
import com.concentus.store.SkillStore;
import com.concentus.web.AgentLibraryController;
import com.concentus.web.FacadeProfileController;
import com.concentus.web.McpDefController;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.concentus.marketplace.MarketplaceItem.KIND_AGENT;
import static com.concentus.marketplace.MarketplaceItem.KIND_API;
import static com.concentus.marketplace.MarketplaceItem.KIND_FACADE;
import static com.concentus.marketplace.MarketplaceItem.KIND_FLOW;
import static com.concentus.marketplace.MarketplaceItem.KIND_MCP;
import static com.concentus.marketplace.MarketplaceItem.KIND_PLUGIN;
import static com.concentus.marketplace.MarketplaceItem.KIND_SKILL;

/**
 * The bridge between a marketplace payload and the resource it stands for, in both directions.
 *
 * <p>Three questions, one per kind, answered here and nowhere else:
 * <ul>
 *   <li><b>What does a payload look like?</b> The resource's own JSON minus its id and minus every
 *       credential — {@link #fromResource} builds one from a record in the caller's organization
 *       and says what it dropped.</li>
 *   <li><b>Is it valid?</b> {@link #validate} runs the payload through the same check the
 *       resource's controller runs on save — {@link McpDefController#validated},
 *       {@link AgentLibraryController#validated}, {@link SkillService#fromFiles} — so the
 *       marketplace never grows a second opinion about what a good MCP definition is.</li>
 *   <li><b>What does installing create?</b> {@link #install} writes the resource into the
 *       caller's current organization through the store the resource's panel uses, and
 *       {@link #uninstall} removes it again.</li>
 * </ul>
 *
 * <p>Payload shapes (§3 of the design): {@code mcp} — {name, url | command+args, env, authHeader,
 * auth}; {@code agent} — {name, model, effort, maxTokens, systemPrompt, description};
 * {@code facade} — {name, description, tools, readOnly, dryRun, readAlso}; {@code skill} —
 * {name, description, files[{path, contentBase64}]}; {@code plugin} — {marketplace, pluginId};
 * {@code api} — {name, baseUrl, specUrl?, spec?, description}; {@code flow} — a FlowGraph
 * without ids or secrets.
 */
@Component
public class MarketplaceInstaller {

    /** Env keys whose values are almost certainly secrets, blanked when a definition is published. */
    private static final Pattern SECRET_KEY =
            Pattern.compile("(?i)(TOKEN|SECRET|KEY|PASSWORD|PASSWD|CREDENTIAL|AUTH)");

    /** What publishing from a resource produced: the payload plus the words for the item, and what was dropped. */
    public record Built(String name, String summary, String description, List<String> tags,
                        JsonNode payload, List<String> stripped) {
    }

    private final McpDefStore mcps;
    private final AgentLibraryStore agents;
    private final FacadeProfileStore facades;
    private final SkillStore skills;
    private final SkillService skillService;
    private final PluginRegistry plugins;
    private final FlowStore flows;
    private final FlowVersionStore versions;
    private final OrgContext orgContext;
    private final ObjectMapper mapper;
    /** For payloads people paste: a flow with a key this build does not know is still a flow. */
    private final ObjectMapper lenient;

    public MarketplaceInstaller(McpDefStore mcps, AgentLibraryStore agents, FacadeProfileStore facades,
                                SkillStore skills, SkillService skillService, PluginRegistry plugins,
                                FlowStore flows, FlowVersionStore versions, OrgContext orgContext,
                                ObjectMapper mapper) {
        this.mcps = mcps;
        this.agents = agents;
        this.facades = facades;
        this.skills = skills;
        this.skillService = skillService;
        this.plugins = plugins;
        this.flows = flows;
        this.versions = versions;
        this.orgContext = orgContext;
        this.mapper = mapper;
        this.lenient = mapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ------------------------------------------------------------------ validation

    /**
     * Refuses a payload the resource's own panel would refuse.
     *
     * @throws IllegalArgumentException with the panel's own sentence
     */
    public void validate(String kind, JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("The payload must be a JSON object — the definition of the "
                    + kind + ".");
        }
        switch (kind) {
            case KIND_MCP -> McpDefController.validated(mcpOf(payload, null));
            case KIND_AGENT -> AgentLibraryController.validated(agentOf(payload, null));
            case KIND_FACADE -> FacadeProfileController.validated(facadeOf(payload, null));
            case KIND_SKILL -> skillService.fromFiles(skillFiles(payload));
            case KIND_PLUGIN -> pluginIdOf(payload);
            case KIND_API -> {
                if (text(payload, "name").isBlank() || text(payload, "baseUrl").isBlank()) {
                    throw new IllegalArgumentException("An API item needs a name and a baseUrl.");
                }
            }
            case KIND_FLOW -> {
                FlowGraph flow = flowOf(payload);
                if (flow.name() == null || flow.name().isBlank()) {
                    throw new IllegalArgumentException("A flow needs a name.");
                }
            }
            default -> throw new IllegalArgumentException("Unknown kind '" + kind + "'. One of: "
                    + String.join(", ", MarketplaceItem.KINDS) + ".");
        }
    }

    // ------------------------------------------------------------------ publish from a resource

    /**
     * The payload for a resource of the caller's organization, credentials removed.
     *
     * @throws ResponseStatusException 404 when the organization has no such resource
     * @throws IllegalArgumentException for a kind that has no server-side resource to publish from
     */
    public Built fromResource(String kind, String resourceId) {
        List<String> stripped = new ArrayList<>();
        switch (kind) {
            case KIND_MCP -> {
                McpDef def = mcps.get(resourceId).orElseThrow(() -> notFound("MCP server"));
                ObjectNode payload = mapper.createObjectNode();
                payload.put("name", def.name());
                if (def.isStdio()) {
                    payload.put("command", def.command());
                    payload.set("args", mapper.valueToTree(def.args() == null ? List.of() : def.args()));
                    payload.set("env", mapper.valueToTree(blankedEnv(def.env(), stripped)));
                    payload.put("auth", "stdio");
                } else {
                    payload.put("url", def.url());
                    if (def.authHeader() != null && !def.authHeader().isBlank()) {
                        payload.put("authHeader", def.authHeader());
                    }
                    boolean hasCredential = def.credentialId() != null && !def.credentialId().isBlank();
                    if (hasCredential) stripped.add("credentialId");
                    // A remote server with a stored token or a named header takes a token; one
                    // without is, in this ecosystem, almost always OAuth signed in from the node.
                    payload.put("auth", hasCredential || payload.has("authHeader") ? "token" : "oauth");
                }
                return new Built(def.name(), "", null, List.of(), payload, stripped);
            }
            case KIND_AGENT -> {
                LibraryAgent agent = agents.get(resourceId).orElseThrow(() -> notFound("library agent"));
                ObjectNode payload = mapper.createObjectNode();
                payload.put("name", agent.name());
                payload.put("model", agent.model());
                payload.put("effort", agent.effort());
                payload.put("maxTokens", agent.maxTokens());
                payload.put("systemPrompt", agent.systemPrompt());
                payload.put("description", agent.description());
                return new Built(agent.name(), agent.description(), null, List.of(), payload, stripped);
            }
            case KIND_FACADE -> {
                FacadeProfile profile = facades.get(resourceId).orElseThrow(() -> notFound("facade profile"));
                ObjectNode payload = mapper.createObjectNode();
                payload.put("name", profile.name());
                payload.put("description", profile.description() == null ? "" : profile.description());
                payload.set("tools", mapper.valueToTree(profile.toolsOrEmpty()));
                payload.put("readOnly", profile.readOnly());
                payload.put("dryRun", profile.dryRunEnabled());
                payload.set("readAlso", mapper.valueToTree(profile.readAlsoOrEmpty()));
                return new Built(profile.name(), profile.description() == null ? "" : profile.description(),
                        null, List.of(), payload, stripped);
            }
            case KIND_SKILL -> {
                SkillDef skill = skills.get(resourceId).orElseThrow(() -> notFound("skill"));
                ObjectNode payload = mapper.createObjectNode();
                payload.put("name", skill.name());
                payload.put("description", skill.description() == null ? "" : skill.description());
                payload.set("files", mapper.valueToTree(skill.files() == null ? List.of() : skill.files()));
                return new Built(skill.name(), skill.description() == null ? "" : skill.description(),
                        null, List.of(), payload, stripped);
            }
            case KIND_FLOW -> {
                FlowGraph flow = flows.get(resourceId).orElseThrow(() -> notFound("flow"));
                FlowGraph clean = stripFlow(flow, stripped);
                return new Built(flow.name(), flowSummary(flow), null, flow.tagsOrEmpty(),
                        mapper.valueToTree(clean), stripped);
            }
            case KIND_PLUGIN -> {
                // The "resource" is the installed plugin's id — name or name@marketplace.
                if (!PluginRegistry.isSafeId(resourceId)
                        || plugins.list().stream().noneMatch(p -> p.id().equals(resourceId))) {
                    throw notFound("installed plugin");
                }
                int at = resourceId.indexOf('@');
                ObjectNode payload = mapper.createObjectNode();
                payload.put("pluginId", at < 0 ? resourceId : resourceId.substring(0, at));
                payload.put("marketplace", at < 0 ? "" : resourceId.substring(at + 1));
                return new Built(payload.get("pluginId").asText(), "", null, List.of(), payload, stripped);
            }
            case KIND_API -> throw new IllegalArgumentException(
                    "An API endpoint is published from the API node's inspector, not from a resource.");
            default -> throw new IllegalArgumentException("Unknown kind '" + kind + "'.");
        }
    }

    // ------------------------------------------------------------------ install and uninstall

    /**
     * Creates the resource in the caller's current organization.
     *
     * @param existingResourceId the resource an earlier install of this item became, when it is
     *                           still there — so an update rewrites it rather than adding a twin
     * @return the id of what was created, or null for a kind that creates nothing ({@code api})
     * @throws IllegalStateException when a plugin install fails, with the CLI's reason
     */
    public String install(MarketplaceItem item, String existingResourceId) {
        JsonNode payload = item.payload();
        switch (item.kind()) {
            case KIND_MCP -> {
                return mcps.save(McpDefController.validated(mcpOf(payload, existingResourceId))).id();
            }
            case KIND_AGENT -> {
                return agents.save(AgentLibraryController.validated(agentOf(payload, existingResourceId))).id();
            }
            case KIND_FACADE -> {
                return facades.save(FacadeProfileController.validated(facadeOf(payload, existingResourceId))).id();
            }
            case KIND_SKILL -> {
                // fromFiles is the validation AND the normalisation an uploaded zip gets: the name
                // comes from SKILL.md's frontmatter, a wrapping folder is stripped.
                SkillDef parsed = skillService.fromFiles(skillFiles(payload));
                return skills.saveReplacingByName(parsed).id();
            }
            case KIND_PLUGIN -> {
                String id = pluginIdOf(payload);
                String status = plugins.install(id);
                if (!"installed".equals(status)) {
                    throw new IllegalStateException("The plugin could not be installed: " + status);
                }
                return id;
            }
            case KIND_API -> {
                return null;
            }
            case KIND_FLOW -> {
                // Paused and without secrets, like a duplicate: a copy of a flow that fires at
                // 07:00 must not fire at 07:00 tomorrow in an organization that has not looked at
                // it yet. The id is the earlier install's when it still exists, so "Update" updates.
                FlowGraph flow = stripFlow(flowOf(payload), new ArrayList<>()).withId(existingResourceId);
                FlowGraph saved = flows.save(flow);
                versions.snapshot(saved, orgContext.currentUser().map(ConcentusUserDetails::email).orElse(null));
                return saved.id();
            }
            default -> throw new IllegalArgumentException("Unknown kind '" + item.kind() + "'.");
        }
    }

    /** Removes the resource an install created, if it is still there. Never throws for one already gone. */
    public void uninstall(String kind, String resourceId) {
        if (resourceId == null || resourceId.isBlank()) return;
        switch (kind) {
            case KIND_MCP -> mcps.delete(resourceId);
            case KIND_AGENT -> agents.delete(resourceId);
            case KIND_FACADE -> facades.delete(resourceId);
            case KIND_SKILL -> skills.delete(resourceId);
            case KIND_FLOW -> flows.delete(resourceId);
            case KIND_PLUGIN -> plugins.uninstall(resourceId);
            default -> {
                // api: nothing was created.
            }
        }
    }

    /** Whether the resource an install created still exists in the caller's organization. */
    public boolean resourceExists(String kind, String resourceId) {
        if (resourceId == null || resourceId.isBlank()) return false;
        return switch (kind) {
            case KIND_MCP -> mcps.get(resourceId).isPresent();
            case KIND_AGENT -> agents.get(resourceId).isPresent();
            case KIND_FACADE -> facades.get(resourceId).isPresent();
            case KIND_SKILL -> skills.get(resourceId).isPresent();
            case KIND_FLOW -> flows.get(resourceId).isPresent();
            case KIND_PLUGIN -> plugins.list().stream().anyMatch(p -> p.id().equals(resourceId));
            default -> false;
        };
    }

    // ------------------------------------------------------------------ payload readers

    private McpDef mcpOf(JsonNode p, String id) {
        Map<String, String> env = new LinkedHashMap<>();
        if (p.path("env").isObject()) {
            // Keys kept, values as published — usually empty, which is the definition showing what
            // must be filled in the installing organization.
            p.path("env").properties().forEach(e -> env.put(e.getKey(), e.getValue().asText("")));
        }
        List<String> args = new ArrayList<>();
        p.path("args").forEach(a -> args.add(a.asText()));
        String command = blankToNull(text(p, "command"));
        return new McpDef(id, text(p, "name"), command == null ? blankToNull(text(p, "url")) : null,
                null, blankToNull(text(p, "authHeader")), command, args, env);
    }

    private static LibraryAgent agentOf(JsonNode p, String id) {
        return new LibraryAgent(id, text(p, "name"), text(p, "model"), text(p, "effort"),
                p.path("maxTokens").asLong(0), text(p, "systemPrompt"), text(p, "description"), 1);
    }

    private FacadeProfile facadeOf(JsonNode p, String id) {
        JsonNode dryRun = p.path("dryRun");
        return new FacadeProfile(id, text(p, "name"), text(p, "description"),
                strings(p.path("tools")), p.path("readOnly").asBoolean(false),
                dryRun.isMissingNode() || dryRun.isNull() ? null : dryRun.asBoolean(true),
                strings(p.path("readAlso")));
    }

    private List<SkillDef.SkillFile> skillFiles(JsonNode p) {
        try {
            return mapper.convertValue(p.path("files").isArray() ? p.path("files") : mapper.createArrayNode(),
                    new TypeReference<List<SkillDef.SkillFile>>() {});
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("A skill's files are [{path, contentBase64}]: " + e.getMessage());
        }
    }

    private static String pluginIdOf(JsonNode p) {
        String plugin = text(p, "pluginId");
        String marketplace = text(p, "marketplace");
        String id = marketplace.isBlank() ? plugin : plugin + "@" + marketplace;
        if (plugin.isBlank() || !PluginRegistry.isSafeId(id)) {
            throw new IllegalArgumentException("A plugin item names a pluginId (and optionally the "
                    + "marketplace it comes from): name or name@marketplace.");
        }
        return id;
    }

    private FlowGraph flowOf(JsonNode p) {
        try {
            return lenient.treeToValue(p, FlowGraph.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a flow: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ stripping

    /**
     * A flow with nothing in it that must not travel: no id (the installing organization mints
     * one), no webhook secret, no publish token, no credential on any node, no notification or
     * approval URL, variable values blanked to their names — and paused, so it acts on nothing
     * until somebody has looked at it. Each thing removed is named in {@code stripped}.
     */
    public static FlowGraph stripFlow(FlowGraph source, List<String> stripped) {
        List<FlowNode> nodes = new ArrayList<>();
        for (FlowNode n : source.nodesOrEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>(n.dataOrEmpty());
            if ("input".equalsIgnoreCase(n.type())) {
                if (blankOut(data, "secret")) stripped.add("secret");
                if (blankOut(data, "publishToken")) stripped.add("publishToken");
                if (Boolean.TRUE.equals(data.get("published"))) data.put("published", Boolean.FALSE);
            }
            if (blankOut(data, "credentialId")) stripped.add(n.id() + ".credentialId");
            nodes.add(new FlowNode(n.id(), n.type(), n.role(), data));
        }
        if (notBlank(source.approvalSlackCredentialId())) stripped.add("approvalSlackCredentialId");
        if (notBlank(source.notifyWebhook())) stripped.add("notifyWebhook");
        if (notBlank(source.approvalTeamsWebhook())) stripped.add("approvalTeamsWebhook");
        Map<String, String> variables = null;
        if (source.variables() != null && !source.variables().isEmpty()) {
            variables = new LinkedHashMap<>();
            boolean hadValues = false;
            for (Map.Entry<String, String> v : source.variables().entrySet()) {
                hadValues |= notBlank(v.getValue());
                variables.put(v.getKey(), "");
            }
            if (hadValues) stripped.add("variables");
        }
        return new FlowGraph(null, source.name(), nodes, source.edgesOrEmpty(),
                Boolean.FALSE, source.tagsOrEmpty(), Boolean.FALSE, null, source.budgetUsd(),
                null, source.approvalSlackChannel(), null, variables, null, Boolean.FALSE);
    }

    /** The coordinator's own description, which is the closest thing a flow has to a one-line summary. */
    public static String flowSummary(FlowGraph flow) {
        return flow.nodesOrEmpty().stream()
                .filter(n -> "agent".equalsIgnoreCase(n.type()))
                .sorted((a, b) -> Boolean.compare(!"coordinator".equals(a.role()), !"coordinator".equals(b.role())))
                .map(n -> String.valueOf(n.dataOrEmpty().getOrDefault("description", "")))
                .filter(s -> !s.isBlank() && !"null".equals(s))
                .findFirst().orElse("");
    }

    /**
     * Env with the values that are credentials blanked: anything referencing a stored credential,
     * and anything under a key that says token, secret, key or password. Flags such as
     * {@code GOOGLE_ADS_MCP_WRITE=true} are configuration and travel as they are.
     */
    private static Map<String, String> blankedEnv(Map<String, String> env, List<String> stripped) {
        Map<String, String> out = new LinkedHashMap<>();
        if (env == null) return out;
        for (Map.Entry<String, String> e : env.entrySet()) {
            String value = e.getValue() == null ? "" : e.getValue();
            boolean secret = value.toLowerCase(Locale.ROOT).startsWith("credential:")
                    || SECRET_KEY.matcher(e.getKey()).find();
            if (secret && !value.isBlank()) {
                stripped.add("env." + e.getKey());
                value = "";
            }
            out.put(e.getKey(), value);
        }
        return out;
    }

    // ------------------------------------------------------------------ small helpers

    private static boolean blankOut(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value instanceof String s && !s.isBlank()) {
            data.put(key, "");
            return true;
        }
        return false;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.path(key);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("");
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isArray()) array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static ResponseStatusException notFound(String what) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such " + what + " in this organization.");
    }
}
