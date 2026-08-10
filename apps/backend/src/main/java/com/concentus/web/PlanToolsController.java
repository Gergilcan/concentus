package com.concentus.web;

import com.concentus.model.FacadeProfile;
import com.concentus.model.RunEvent;
import com.concentus.model.WorkPlan;
import com.concentus.service.AgentRun;
import com.concentus.service.RunService;
import com.concentus.store.FacadeProfileStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The planning coordinator's one tool: {@code plan_submit}.
 *
 * <p>Its own endpoint rather than a tool on {@code /tools}, on purpose. The run-wide tools
 * endpoint also serves the flow's API-node operations, and the planning process must not be able
 * to call external APIs — it reads, thinks, and submits a plan. Scoping the endpoint is what
 * makes that a property of the system instead of a hope about the prompt.
 *
 * <p>Validation answers as tool results, never as transport errors: a rejected plan comes back
 * with every problem named at once, and the coordinator corrects itself within the same session —
 * the same self-repair loop every other tool in this codebase leans on.
 */
@RestController
@RequestMapping("/api/runs/{runId}/plan")
public class PlanToolsController {

    public static final String TOKEN_HEADER = RunToolsController.TOKEN_HEADER;

    private final RunService runs;
    private final ObjectMapper mapper;
    private final FacadeProfileStore profiles;
    private final int maxItems;

    public PlanToolsController(RunService runs, ObjectMapper mapper, FacadeProfileStore profiles,
                               @Value("${workers.max-items:8}") int maxItems) {
        this.runs = runs;
        this.mapper = mapper;
        this.profiles = profiles;
        this.maxItems = maxItems;
    }

    @PostMapping
    public ResponseEntity<JsonNode> rpc(@PathVariable String runId,
                                        @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                        @RequestBody JsonNode request) {
        AgentRun run = runs.get(runId).orElse(null);
        if (run == null || run.toolToken == null || token == null
                || !MessageDigest.isEqual(run.toolToken.getBytes(StandardCharsets.UTF_8),
                        token.getBytes(StandardCharsets.UTF_8))
                || run.compiled == null || !run.compiled.fanout()) {
            return ResponseEntity.status(401).build();
        }

        String method = request.path("method").asText("");
        JsonNode id = request.get("id");
        return switch (method) {
            case "initialize" -> ok(id, initializeResult());
            case "notifications/initialized", "notifications/cancelled" ->
                    ResponseEntity.accepted().build();
            case "tools/list" -> ok(id, toolsList());
            case "tools/call" -> ok(id, toolsCall(run, request.path("params")));
            case "ping" -> ok(id, mapper.createObjectNode());
            default -> error(id, -32601, "Method not supported: " + method);
        };
    }

    private ObjectNode initializeResult() {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.putObject("capabilities").putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "concentus-plan");
        info.put("version", "1.0");
        return result;
    }

    private ObjectNode toolsList() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        ObjectNode t = tools.addObject();
        t.put("name", "plan_submit");
        t.put("description", "Submit the fan-out plan: the independent work items Concentus "
                + "will run as parallel worker processes. Call it exactly once, when your plan "
                + "is complete. A rejected plan returns every problem at once — fix them all and "
                + "resubmit. At most " + maxItems + " items; no two items may declare the same "
                + "file; dependsOn is not supported (items run in parallel).");
        t.set("inputSchema", planSchema());
        return result;
    }

    private ObjectNode planSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("goal").put("type", "string")
                .put("description", "One line naming the overall objective.");
        ObjectNode items = props.putObject("items");
        items.put("type", "array");
        ObjectNode item = items.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("id").put("type", "string")
                .put("description", "Short unique handle, e.g. 'backend'.");
        ip.putObject("title").put("type", "string")
                .put("description", "Human name for this item's box.");
        ip.putObject("prompt").put("type", "string")
                .put("description", "The worker's whole instruction — self-contained; the worker "
                        + "sees nothing else of this conversation.");
        ip.putObject("context").put("type", "array")
                .put("description", "Only the facts THIS item needs; they become the worker's "
                        + "project context.")
                .putObject("items").put("type", "string");
        ip.putObject("files").put("type", "array")
                .put("description", "Paths this item will touch. Two items declaring the same "
                        + "file reject the whole plan.")
                .putObject("items").put("type", "string");
        ip.putObject("contextFolders").put("type", "array")
                .put("description", "Host folders this worker should read (must be inside the "
                        + "deployment's allowed roots).")
                .putObject("items").put("type", "string");
        ip.putObject("model").put("type", "string")
                .put("description", "Model for this worker; empty inherits the coordinator's.");
        ip.putObject("profile").put("type", "string")
                .put("description", "Facade profile NAME for this worker's MCP access; empty "
                        + "means the worker gets no MCP tools.");
        item.putArray("required").add("id").add("prompt");
        schema.putArray("required").add("items");
        return schema;
    }

    private ObjectNode toolsCall(AgentRun run, JsonNode params) {
        if (!"plan_submit".equals(params.path("name").asText(""))) {
            return callResult(true, "Unknown tool. Only plan_submit exists here.");
        }

        WorkPlan plan;
        try {
            plan = mapper.treeToValue(params.path("arguments"), WorkPlan.class);
        } catch (Exception e) {
            return callResult(true, "The plan could not be read as JSON: " + e.getMessage());
        }

        List<String> problems = new ArrayList<>(plan.problems(maxItems));
        List<WorkPlan.WorkItem> resolved = resolveProfiles(plan, problems);
        if (!problems.isEmpty()) {
            return callResult(true, "The plan was NOT accepted. Fix all of this and resubmit:\n- "
                    + String.join("\n- ", problems));
        }

        WorkPlan accepted = new WorkPlan(plan.goal(), resolved);
        run.submittedPlan = accepted;
        run.emit(RunEvent.of("system", "Plan received: " + resolved.size() + " item(s) — "
                + resolved.stream().map(WorkPlan.WorkItem::displayName)
                        .collect(Collectors.joining(", ")) + "."));
        return callResult(false, "Plan accepted: " + resolved.size() + " item(s). The workers "
                + "run when this turn ends — finish with a one-line summary of the plan.");
    }

    /**
     * Turns profile names into ids, collecting a problem per unknown name. Names, because the
     * planner reads them off its instructions — nobody plans in terms of {@code fprof_x4k2}.
     */
    private List<WorkPlan.WorkItem> resolveProfiles(WorkPlan plan, List<String> problems) {
        List<FacadeProfile> all = profiles.list();
        List<WorkPlan.WorkItem> out = new ArrayList<>();
        for (WorkPlan.WorkItem item : plan.itemsOrEmpty()) {
            if (item.profile() == null || item.profile().isBlank()) {
                out.add(item.withProfileId(""));
                continue;
            }
            String wanted = item.profile().trim().toLowerCase(Locale.ROOT);
            FacadeProfile match = all.stream()
                    .filter(p -> p.name() != null && p.name().trim().toLowerCase(Locale.ROOT).equals(wanted))
                    .findFirst().orElse(null);
            if (match == null) {
                problems.add("Item '" + item.id() + "' names the facade profile '" + item.profile()
                        + "', which does not exist. Available: "
                        + (all.isEmpty() ? "none — leave profile empty"
                                : all.stream().map(FacadeProfile::name)
                                        .collect(Collectors.joining(", "))) + ".");
                out.add(item);
            } else {
                out.add(item.withProfileId(match.id()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------- plumbing

    private ObjectNode callResult(boolean isError, String text) {
        ObjectNode result = mapper.createObjectNode();
        ObjectNode content = result.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", text);
        result.put("isError", isError);
        return result;
    }

    private ResponseEntity<JsonNode> ok(JsonNode id, ObjectNode result) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        if (id != null) envelope.set("id", id);
        envelope.set("result", result);
        return ResponseEntity.ok(envelope);
    }

    private ResponseEntity<JsonNode> error(JsonNode id, int code, String message) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        if (id != null) envelope.set("id", id);
        ObjectNode err = envelope.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return ResponseEntity.ok(envelope);
    }
}
