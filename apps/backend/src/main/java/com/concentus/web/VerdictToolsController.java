package com.concentus.web;

import com.concentus.model.RunEvent;
import com.concentus.model.WorkVerdict;
import com.concentus.service.AgentRun;
import com.concentus.service.RunService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The adversarial verifier's one tool: {@code verdict_submit}.
 *
 * <p>Its own endpoint for the same reason the planner has one: the run-wide tools endpoint also
 * carries the flow's API operations, and a process whose job is to <em>judge</em> outputs must
 * not be able to act on external systems while judging. Its own token too — the verifier holding
 * the planner's token could resubmit the plan, and vice versa.
 *
 * <p>Validation answers as tool results, never as transport errors: a rejected verdict comes
 * back with every problem named at once — an unjudged worker, a kill without a reason — and the
 * verifier corrects itself within the same session.
 */
@RestController
@RequestMapping("/api/runs/{runId}/verdict")
public class VerdictToolsController {

    public static final String TOKEN_HEADER = RunToolsController.TOKEN_HEADER;

    private final RunService runs;
    private final ObjectMapper mapper;

    public VerdictToolsController(RunService runs, ObjectMapper mapper) {
        this.runs = runs;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<JsonNode> rpc(@PathVariable String runId,
                                        @RequestHeader(value = TOKEN_HEADER, required = false) String token,
                                        @RequestBody JsonNode request) {
        AgentRun run = runs.get(runId).orElse(null);
        if (run == null || !McpJsonRpc.tokenMatches(run.verdictToken, token)
                || run.compiled == null || !run.compiled.fanout()) {
            return ResponseEntity.status(401).build();
        }
        return McpJsonRpc.dispatch(mapper, request,
                () -> McpJsonRpc.initializeResult(mapper, "concentus-verdict"),
                () -> toolsList(run),
                params -> toolsCall(run, params));
    }

    private ObjectNode toolsList(AgentRun run) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        ObjectNode t = tools.addObject();
        t.put("name", "verdict_submit");
        t.put("description", "Submit your verdict on every worker output at once: accept or "
                + "reject, with the reason for each rejection. Call it exactly once, when every "
                + "worker is judged. Workers awaiting judgment: "
                + String.join(", ", run.verdictExpected) + ". A rejected submission returns "
                + "every problem at once — fix them all and resubmit.");
        t.set("inputSchema", verdictSchema());
        return result;
    }

    private ObjectNode verdictSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("summary").put("type", "string")
                .put("description", "One line on the overall judgment.");
        ObjectNode items = props.putObject("items");
        items.put("type", "array");
        ObjectNode item = items.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");
        ip.putObject("id").put("type", "string")
                .put("description", "The worker id, exactly as listed in your prompt.");
        ObjectNode verdict = ip.putObject("verdict");
        verdict.put("type", "string");
        verdict.putArray("enum").add("accept").add("reject");
        verdict.put("description", "reject withholds this worker's output from the merge step.");
        ip.putObject("reason").put("type", "string")
                .put("description", "Why. Required on a rejection.");
        item.putArray("required").add("id").add("verdict");
        schema.putArray("required").add("items");
        return schema;
    }

    private ObjectNode toolsCall(AgentRun run, JsonNode params) {
        if (!"verdict_submit".equals(params.path("name").asText(""))) {
            return callResult(true, "Unknown tool. Only verdict_submit exists here.");
        }

        WorkVerdict verdict;
        try {
            verdict = mapper.treeToValue(params.path("arguments"), WorkVerdict.class);
        } catch (Exception e) {
            return callResult(true, "The verdict could not be read as JSON: " + e.getMessage());
        }

        List<String> problems = verdict.problems(run.verdictExpected);
        if (!problems.isEmpty()) {
            return callResult(true, "The verdict was NOT accepted. Fix all of this and resubmit:\n- "
                    + String.join("\n- ", problems));
        }

        run.submittedVerdict = verdict;
        long rejected = verdict.rejectedCount();
        run.emit(RunEvent.of("system", "Verdict received: "
                + (verdict.itemsOrEmpty().size() - rejected) + " accepted, " + rejected
                + " rejected" + (verdict.summary() == null || verdict.summary().isBlank()
                        ? "" : " — " + verdict.summary()) + "."));
        return callResult(false, "Verdict accepted. Finish with a one-line summary of your "
                + "judgment — do not keep working.");
    }

    // ------------------------------------------------------------------- plumbing

    private ObjectNode callResult(boolean isError, String text) {
        return McpJsonRpc.callResult(mapper, isError, text);
    }
}
