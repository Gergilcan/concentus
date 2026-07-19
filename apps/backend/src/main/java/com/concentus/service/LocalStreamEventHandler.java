package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses {@code claude --output-format stream-json} lines and applies them to a run: emitting
 * console events, attributing output/tool-use/tokens to the right node, and accruing totals.
 * Extracted from {@link LocalClaudeExecutor} to keep process/workspace management separate from
 * stream-json parsing.
 */
final class LocalStreamEventHandler {

    private final ObjectMapper mapper;

    LocalStreamEventHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void handleLine(AgentRun run, String line) {
        String t = line.trim();
        if (t.isEmpty()) return;
        JsonNode node;
        try {
            node = mapper.readTree(t);
        } catch (Exception e) {
            run.emit(RunEvent.of("system", t)); // non-JSON progress / error text
            return;
        }
        switch (node.path("type").asText("")) {
            case "system" -> {
                if ("init".equals(node.path("subtype").asText())) {
                    run.emit(RunEvent.of("system", "Local session ready (model "
                            + node.path("model").asText("?") + ")."));
                }
            }
            case "assistant" -> handleAssistant(run, node);
            case "user" -> handleUser(run, node);
            case "result" -> {
                accrueTotals(run, node.path("usage"));
                NodeExec coordExec = coordExec(run);
                if (node.path("is_error").asBoolean(false)) {
                    String err = node.path("result").asText("run failed");
                    markNodeExec(coordExec, "failed", err);
                    // Mark the run itself failed so it shows as failed and triggers notifications.
                    run.status = "ERROR";
                    run.error = err;
                    run.emit(RunEvent.of("error", err));
                } else {
                    if (coordExec != null && !"failed".equals(coordExec.status)) {
                        markNodeExec(coordExec, "passed", null);
                    }
                    run.emit(RunEvent.of("status", "idle"));
                }
            }
            case "rate_limit_event" -> {
                String status = node.path("rate_limit_info").path("status").asText("");
                if (!status.isEmpty() && !"allowed".equals(status)) {
                    run.emit(RunEvent.of("system", "Rate limit: " + status));
                }
            }
            default -> {
                // stream_event / etc. — ignored for the console
            }
        }
    }

    /** Attribute an assistant message's text, tool calls, and token usage to the right node. */
    private void handleAssistant(AgentRun run, JsonNode node) {
        String parent = node.path("parent_tool_use_id").asText("");
        // A non-blank parent means a sub-agent produced this, even if we never saw the Task
        // that opened it (e.g. the run was resumed mid-flight). Falling back to the coordinator
        // in that case would silently file another agent's work under the coordinator's box,
        // so an unidentified sub-agent is left unattributed instead.
        boolean fromSub = !parent.isBlank();
        String targetNodeId = fromSub ? run.taskToNode.get(parent) : coordNodeId(run);
        // The real agent name, not a generic "subagent" — with several sub-agents running
        // concurrently the console is unreadable unless each line says who produced it.
        String label = targetNodeId != null ? agentLabel(run, targetNodeId) : "sub-agent";
        NodeExec target = run.nodeExec(targetNodeId, "agent", label);

        JsonNode usage = node.path("message").path("usage");
        if (target != null && usage.isObject()) {
            target.outputTokens += usage.path("output_tokens").asLong(0);
            target.inputTokens += usage.path("input_tokens").asLong(0)
                    + usage.path("cache_read_input_tokens").asLong(0);
        }

        JsonNode content = node.path("message").path("content");
        if (!content.isArray()) return;
        for (JsonNode b : content) {
            String bt = b.path("type").asText("");
            if ("text".equals(bt)) {
                String text = b.path("text").asText("");
                if (!text.isBlank()) {
                    if (target != null) target.appendOutput(text);
                    run.emit(RunEvent.of("agent_message", text, label, targetNodeId));
                }
            } else if ("tool_use".equals(bt)) {
                String name = b.path("name").asText("tool");
                if ("Task".equals(name)) {
                    String subtype = b.path("input").path("subagent_type").asText("");
                    String subNodeId = subNodeIdByAgentName(run, subtype);
                    if (subNodeId != null) {
                        NodeExec sub = run.nodeExec(subNodeId, "agent", agentLabel(run, subNodeId));
                        if (sub != null) {
                            sub.status = "running";
                            String brief = b.path("input").path("prompt").asText(
                                    b.path("input").path("description").asText(""));
                            sub.appendInput(brief);
                        }
                        run.taskToNode.put(b.path("id").asText(""), subNodeId);
                        // Attributed to the delegator (the coordinator), which is who ran the tool.
                        run.emit(RunEvent.of("tool_use", "Task → " + subtype, label, targetNodeId));
                        continue;
                    }
                }
                run.emit(RunEvent.of("tool_use", name, label, targetNodeId));
            }
        }
    }

    /** A user event may carry tool_result blocks — a Task result closes out that sub-agent node. */
    private void handleUser(AgentRun run, JsonNode node) {
        JsonNode content = node.path("message").path("content");
        if (!content.isArray()) return;
        for (JsonNode b : content) {
            if ("tool_result".equals(b.path("type").asText(""))) {
                String id = b.path("tool_use_id").asText("");
                String subNodeId = run.taskToNode.get(id);
                if (subNodeId != null) {
                    NodeExec sub = run.nodeExec(subNodeId, "agent", agentLabel(run, subNodeId));
                    if (sub != null && !"failed".equals(sub.status)) {
                        boolean bad = b.path("is_error").asBoolean(false);
                        markNodeExec(sub, bad ? "failed" : "passed", bad ? "sub-agent reported an error" : null);
                    }
                }
            }
        }
    }

    private void accrueTotals(AgentRun run, JsonNode usage) {
        if (!usage.isObject()) return;
        run.totalInputTokens += usage.path("input_tokens").asLong(0)
                + usage.path("cache_read_input_tokens").asLong(0)
                + usage.path("cache_creation_input_tokens").asLong(0);
        run.totalOutputTokens += usage.path("output_tokens").asLong(0);
    }

    /** Sets status/error/endedAt on a node execution record; a no-op if {@code ne} is null. */
    private static void markNodeExec(NodeExec ne, String status, String error) {
        if (ne == null) return;
        ne.status = status;
        if (error != null) ne.error = error;
        ne.endedAt = System.currentTimeMillis();
    }

    private static String coordNodeId(AgentRun run) {
        return run.compiled == null ? null : run.compiled.coordinator().nodeId;
    }

    private NodeExec coordExec(AgentRun run) {
        String id = coordNodeId(run);
        return id == null ? null : run.nodeExec(id, "agent", run.compiled.coordinator().name);
    }

    private static String agentLabel(AgentRun run, String nodeId) {
        if (run.compiled == null || nodeId == null) return nodeId;
        if (nodeId.equals(run.compiled.coordinator().nodeId)) return run.compiled.coordinator().name;
        for (AgentSpec s : run.compiled.subAgents()) {
            if (nodeId.equals(s.nodeId)) return s.name;
        }
        return nodeId;
    }

    private static String subNodeIdByAgentName(AgentRun run, String sanitizedName) {
        if (run.compiled == null || sanitizedName == null || sanitizedName.isBlank()) return null;
        for (AgentSpec s : run.compiled.subAgents()) {
            if (LocalClaudeExecutor.sanitize(s.name).equals(sanitizedName)) return s.nodeId;
        }
        return null;
    }
}
