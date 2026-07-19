package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attribution of {@code claude --output-format stream-json} lines to individual flow nodes.
 *
 * <p>Correlation runs through {@code parent_tool_use_id}: a {@code Task} tool call records
 * {@code toolUseId -> sub-agent node}, and every later assistant message carrying that id as its
 * parent belongs to that sub-agent. Getting this wrong is silent — the output still appears, just
 * filed under the wrong agent — so the mis-attribution cases are pinned down here explicitly.
 */
class LocalStreamEventHandlerTest {

    private static final String COORD_NODE = "agent_coord";
    private static final String SUB_NODE = "agent_sub";

    private LocalStreamEventHandler handler;
    private AgentRun run;

    @BeforeEach
    void setUp() {
        handler = new LocalStreamEventHandler(new ObjectMapper());
        run = new AgentRun("run1", "f1", "Flow", "local");
        run.compiled = new CompiledFlow(agent(COORD_NODE, "Coordinator"), List.of(agent(SUB_NODE, "Researcher")));
    }

    private static AgentSpec agent(String nodeId, String name) {
        AgentSpec s = new AgentSpec();
        s.nodeId = nodeId;
        s.name = name;
        return s;
    }

    private NodeExec exec(String nodeId) {
        return run.nodeExecList().stream().filter(n -> nodeId.equals(n.nodeId)).findFirst().orElse(null);
    }

    private RunEvent lastEvent() {
        List<RunEvent> all = run.bufferedEvents();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /** An assistant text message, optionally from a sub-agent thread. */
    private static String assistantText(String text, String parentToolUseId) {
        String parent = parentToolUseId == null ? "null" : "\"" + parentToolUseId + "\"";
        return """
                {"type":"assistant","parent_tool_use_id":%s,
                 "message":{"content":[{"type":"text","text":"%s"}]}}"""
                .formatted(parent, text);
    }

    /** The coordinator delegating to a sub-agent via the Task tool. */
    private static String taskCall(String toolUseId, String subagentType, String prompt) {
        return """
                {"type":"assistant","parent_tool_use_id":null,
                 "message":{"content":[{"type":"tool_use","id":"%s","name":"Task",
                   "input":{"subagent_type":"%s","prompt":"%s"}}]}}"""
                .formatted(toolUseId, subagentType, prompt);
    }

    @Test
    void coordinatorTextLandsOnTheCoordinatorNode() {
        handler.handleLine(run, assistantText("planning", null));

        assertThat(exec(COORD_NODE).output).contains("planning");
        assertThat(lastEvent().agent()).isEqualTo("Coordinator");
    }

    @Test
    void taskCallOpensTheSubAgentBlockWithItsPromptAsInput() {
        handler.handleLine(run, taskCall("tool_1", "researcher", "find the changelog"));

        NodeExec sub = exec(SUB_NODE);
        assertThat(sub.status).isEqualTo("running");
        assertThat(sub.input).contains("find the changelog");
        assertThat(run.taskToNode).containsEntry("tool_1", SUB_NODE);
        // the delegation itself is the coordinator's action
        assertThat(lastEvent().agent()).isEqualTo("Coordinator");
    }

    @Test
    void subAgentTextIsAttributedToItByName() {
        handler.handleLine(run, taskCall("tool_1", "researcher", "go"));
        handler.handleLine(run, assistantText("found 3 entries", "tool_1"));

        assertThat(exec(SUB_NODE).output).contains("found 3 entries");
        // The coordinator's block exists (it made the Task call) but produced no text of its
        // own, so the sub-agent's output has not leaked into it.
        assertThat(exec(COORD_NODE).output).isNull();
        assertThat(lastEvent().agent()).isEqualTo("Researcher");
    }

    @Test
    void toolResultClosesTheSubAgentBlock() {
        handler.handleLine(run, taskCall("tool_1", "researcher", "go"));
        handler.handleLine(run, """
                {"type":"user","message":{"content":[
                  {"type":"tool_result","tool_use_id":"tool_1","is_error":false}]}}""");

        assertThat(exec(SUB_NODE).status).isEqualTo("passed");
    }

    @Test
    void erroringToolResultFailsTheSubAgentBlock() {
        handler.handleLine(run, taskCall("tool_1", "researcher", "go"));
        handler.handleLine(run, """
                {"type":"user","message":{"content":[
                  {"type":"tool_result","tool_use_id":"tool_1","is_error":true}]}}""");

        assertThat(exec(SUB_NODE).status).isEqualTo("failed");
    }

    @Test
    void messageFromAnUnknownSubAgentThreadIsNotBlamedOnTheCoordinator() {
        // No Task call was seen for "tool_ghost" (e.g. the run was resumed mid-flight).
        // The parent id still proves a sub-agent wrote this, so it must not be filed
        // under the coordinator — that would be a silent, invisible mis-attribution.
        handler.handleLine(run, assistantText("orphan output", "tool_ghost"));

        assertThat(exec(COORD_NODE)).isNull();
        assertThat(lastEvent().agent()).isEqualTo("sub-agent");
    }

    @Test
    void anAgentNamedWithCapitalsOrSpacesStillMatches() {
        // The CLI reports the sanitized name it was given, but comparing it raw against the
        // canvas name failed for anything that wasn't already lowercase-and-hyphenated — and
        // a failed match is invisible: every sub-agent collapses into one generic label.
        run.compiled = new CompiledFlow(
                agent(COORD_NODE, "Coordinator"), List.of(agent(SUB_NODE, "Backend Dev")));

        handler.handleLine(run, taskCall("tool_1", "backend-dev", "go"));
        handler.handleLine(run, assistantText("done", "tool_1"));

        assertThat(exec(SUB_NODE).output).contains("done");
        assertThat(lastEvent().agent()).isEqualTo("Backend Dev");
    }

    @Test
    void delegationToAnUnknownAgentIsLabelledAndReported() {
        // e.g. a Claude Code built-in subagent, or a node renamed since the run started.
        handler.handleLine(run, taskCall("tool_1", "general-purpose", "go"));

        assertThat(run.taskToLabel).containsEntry("tool_1", "general-purpose");
        // The mismatch is stated outright rather than silently swallowed...
        assertThat(run.bufferedEvents())
                .anySatisfy(e -> assertThat(e.text()).contains("matches no agent in this flow"));
        // ...and names both sides so the fix is obvious.
        assertThat(run.bufferedEvents())
                .anySatisfy(e -> assertThat(e.text()).contains("Researcher → researcher"));
    }

    @Test
    void anUnknownSubAgentKeepsItsOwnNameInsteadOfOneSharedBucket() {
        handler.handleLine(run, taskCall("tool_1", "general-purpose", "go"));
        handler.handleLine(run, taskCall("tool_2", "code-reviewer", "review"));
        handler.handleLine(run, assistantText("from A", "tool_1"));
        handler.handleLine(run, assistantText("from B", "tool_2"));

        // Two distinct agents, two distinct labels — not both "sub-agent".
        var events = run.bufferedEvents();
        assertThat(events).anySatisfy(e -> {
            assertThat(e.text()).isEqualTo("from A");
            assertThat(e.agent()).isEqualTo("general-purpose");
        });
        assertThat(events).anySatisfy(e -> {
            assertThat(e.text()).isEqualTo("from B");
            assertThat(e.agent()).isEqualTo("code-reviewer");
        });
    }

    @Test
    void twoSubAgentsKeepTheirOutputSeparate() {
        run.compiled = new CompiledFlow(
                agent(COORD_NODE, "Coordinator"),
                List.of(agent("agent_a", "Alpha"), agent("agent_b", "Beta")));

        handler.handleLine(run, taskCall("t_a", "alpha", "task A"));
        handler.handleLine(run, taskCall("t_b", "beta", "task B"));
        handler.handleLine(run, assistantText("result A", "t_a"));
        handler.handleLine(run, assistantText("result B", "t_b"));

        assertThat(exec("agent_a").input).contains("task A").doesNotContain("task B");
        assertThat(exec("agent_a").output).contains("result A").doesNotContain("result B");
        assertThat(exec("agent_b").input).contains("task B").doesNotContain("task A");
        assertThat(exec("agent_b").output).contains("result B").doesNotContain("result A");
    }

    @Test
    void cachedPromptTokensAreTrackedApartFromFreshInput() {
        // input_tokens is the UNCACHED remainder; the cached spans are reported separately and
        // bill at ~0.1x (read) and ~1.25x (write). Summing all three as plain input made a
        // resumed session — which re-sends the whole conversation from cache every turn — look
        // roughly an order of magnitude more expensive than it is.
        handler.handleLine(run, """
                {"type":"result","usage":{"input_tokens":500,"output_tokens":200,
                 "cache_read_input_tokens":40000,"cache_creation_input_tokens":2000}}""");

        assertThat(run.totalInputTokens).isEqualTo(500L);
        assertThat(run.cacheReadTokens).isEqualTo(40000L);
        assertThat(run.cacheWriteTokens).isEqualTo(2000L);
        assertThat(run.totalOutputTokens).isEqualTo(200L);
    }

    @Test
    void costWeightsCachedTokensBelowFreshInput() {
        // Coordinator on Opus ($5/M in), sub-agent on Sonnet ($3/M in) — a flat rate would
        // misprice one of them.
        run.pricing = new PricingTable("claude-opus-4-8:5:25,claude-sonnet-5:3:15", 3.0, 15.0);
        run.compiled = new CompiledFlow(
                agentWithModel(COORD_NODE, "Coordinator", "claude-opus-4-8"),
                List.of(agentWithModel(SUB_NODE, "Researcher", "claude-sonnet-5")));

        handler.handleLine(run, """
                {"type":"assistant","parent_tool_use_id":null,
                 "message":{"usage":{"cache_read_input_tokens":1000000},
                            "content":[{"type":"text","text":"x"}]}}""");

        // 1M cache-read tokens on Opus bill as 100k input-equivalent: $0.50, not $5.00.
        assertThat(exec(COORD_NODE).cacheReadTokens).isEqualTo(1_000_000L);
        assertThat(run.estimatedCostUsd()).isEqualTo(0.50);
    }

    @Test
    void eachBlockIsPricedByItsOwnModel() {
        run.pricing = new PricingTable("claude-opus-4-8:5:25,claude-sonnet-5:3:15", 3.0, 15.0);
        run.compiled = new CompiledFlow(
                agentWithModel(COORD_NODE, "Coordinator", "claude-opus-4-8"),
                List.of(agentWithModel(SUB_NODE, "Researcher", "claude-sonnet-5")));

        // 1M fresh input tokens on each: Opus $5, Sonnet $3.
        handler.handleLine(run, """
                {"type":"assistant","parent_tool_use_id":null,
                 "message":{"usage":{"input_tokens":1000000},"content":[{"type":"text","text":"a"}]}}""");
        handler.handleLine(run, taskCall("tool_1", "researcher", "go"));
        handler.handleLine(run, """
                {"type":"assistant","parent_tool_use_id":"tool_1",
                 "message":{"usage":{"input_tokens":1000000},"content":[{"type":"text","text":"b"}]}}""");

        assertThat(exec(COORD_NODE).model).isEqualTo("claude-opus-4-8");
        assertThat(exec(SUB_NODE).model).isEqualTo("claude-sonnet-5");
        assertThat(run.estimatedCostUsd()).isEqualTo(8.00);
    }

    private static AgentSpec agentWithModel(String nodeId, String name, String model) {
        AgentSpec s = agent(nodeId, name);
        s.model = new AgentSpec.ModelSpec();
        s.model.id = model;
        return s;
    }

    @Test
    void sessionReadyLineReportsHowManyToolsAreInContext() {
        // Tool schemas persist for the whole session, so a couple of big MCP servers can dwarf
        // the actual task. Surfacing the count makes that cost visible instead of mysterious.
        handler.handleLine(run, """
                {"type":"system","subtype":"init","model":"claude-opus-4-8",
                 "tools":["Read","Write","Bash","mcp__github__x","mcp__linear__y"],
                 "mcp_servers":[{"name":"github"},{"name":"linear"}]}""");

        assertThat(lastEvent().text())
                .contains("5 tools loaded")
                .contains("2 MCP server(s)");
    }

    @Test
    void tokensAccrueToTheAgentThatSpentThem() {
        handler.handleLine(run, taskCall("tool_1", "researcher", "go"));
        handler.handleLine(run, """
                {"type":"assistant","parent_tool_use_id":"tool_1",
                 "message":{"usage":{"input_tokens":100,"output_tokens":40},
                            "content":[{"type":"text","text":"done"}]}}""");

        assertThat(exec(SUB_NODE).outputTokens).isEqualTo(40L);
        assertThat(exec(SUB_NODE).inputTokens).isEqualTo(100L);
    }
}
