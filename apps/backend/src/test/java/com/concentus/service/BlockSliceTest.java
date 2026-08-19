package com.concentus.service;

import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Cutting one block out of a flow so it can be run again on its own.
 *
 * <p>The slice has to be a flow the compiler accepts, or the re-run fails at launch with an error
 * about a graph nobody drew — so most of what these assert is that the cut leaves something whole.
 */
class BlockSliceTest {

    private static final FlowCompiler COMPILER = new FlowCompiler();

    private static FlowNode node(String id, String type, String role, Map<String, Object> data) {
        return new FlowNode(id, type, role, data);
    }

    /** Lead delegates to Writer, Writer to Reviewer; Writer alone holds an MCP server. */
    private static FlowGraph team() {
        return new FlowGraph("flow_1", "Newsroom", "local",
                List.of(node("in-1", "input", null, Map.of("mode", "cron", "cron", "0 7 * * *",
                                "prompt", "Write the daily briefing.")),
                        node("lead", "agent", "coordinator", Map.of("name", "Lead")),
                        node("writer", "agent", "subagent", Map.of("name", "Writer")),
                        node("reviewer", "agent", "subagent", Map.of("name", "Reviewer")),
                        node("mcp-1", "mcp", null, Map.of("name", "Slack", "url", "http://x")),
                        node("hand-off", "flow", null, Map.of("flowId", "flow_2"))),
                List.of(new FlowEdge("e1", "in-1", "lead"),
                        new FlowEdge("e2", "lead", "writer"),
                        new FlowEdge("e3", "writer", "reviewer"),
                        new FlowEdge("e4", "mcp-1", "writer"),
                        new FlowEdge("e5", "lead", "hand-off")),
                null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void the_block_alone_leads_its_own_slice() {
        FlowGraph slice = BlockSlice.of(team(), "writer", false);

        CompiledFlow compiled = COMPILER.compile(slice);
        assertThat(compiled.coordinator().name).isEqualTo("Writer");
        assertThat(compiled.subAgents()).isEmpty();
    }

    @Test
    void the_capabilities_wired_to_it_come_along_or_it_cannot_do_its_job() {
        FlowGraph slice = BlockSlice.of(team(), "writer", false);

        assertThat(COMPILER.compile(slice).coordinator().mcpServers)
                .extracting(s -> s.name)
                .contains("Slack");
    }

    @Test
    void a_capability_wired_to_someone_else_is_left_behind() {
        FlowGraph slice = BlockSlice.of(team(), "reviewer", false);

        assertThat(slice.nodesOrEmpty()).extracting(FlowNode::id).doesNotContain("mcp-1");
    }

    // Continuing a failed run from the block that broke: the agents below it are part of its job,
    // not later steps someone can skip.
    @Test
    void continuing_keeps_the_agents_this_one_delegates_to() {
        FlowGraph slice = BlockSlice.of(team(), "writer", true);

        CompiledFlow compiled = COMPILER.compile(slice);
        assertThat(compiled.coordinator().name).isEqualTo("Writer");
        assertThat(compiled.subAgents()).extracting(s -> s.name).containsExactly("Reviewer");
    }

    @Test
    void the_agent_that_delegates_to_this_one_is_not_dragged_in() {
        FlowGraph slice = BlockSlice.of(team(), "writer", true);

        assertThat(slice.nodesOrEmpty()).extracting(FlowNode::id).doesNotContain("lead");
    }

    // A slice carrying the flow's trigger would schedule itself: re-running one block at 07:00
    // every morning is nobody's intention, and a webhook secret in a slice answers the internet.
    @Test
    void the_trigger_stays_with_the_flow_it_belongs_to() {
        FlowGraph slice = BlockSlice.of(team(), "writer", false);

        assertThat(slice.nodesOrEmpty()).extracting(FlowNode::type).doesNotContain("input");
    }

    // A hand-off fires when the FLOW finishes. Re-running a block someone is still tuning is not
    // the flow finishing, and chaining onward would send a draft into real downstream work.
    @Test
    void hand_offs_do_not_fire_from_a_block_re_run() {
        FlowGraph slice = BlockSlice.of(team(), "lead", true);

        assertThat(slice.nodesOrEmpty()).extracting(FlowNode::type).doesNotContain("flow");
        assertThat(COMPILER.compile(slice).afterFlows()).isEmpty();
    }

    @Test
    void a_slice_is_never_saved_over_the_flow_it_was_cut_from() {
        FlowGraph slice = BlockSlice.of(team(), "writer", false);

        assertThat(slice.id()).isNull();
        assertThat(slice.name()).isEqualTo("Newsroom · Writer");
    }

    @Test
    void a_capability_block_cannot_be_run_on_its_own() {
        Throwable thrown = catchThrowable(() -> BlockSlice.of(team(), "mcp-1", false));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capability");
    }

    @Test
    void a_block_that_is_not_in_the_flow_says_so() {
        Throwable thrown = catchThrowable(() -> BlockSlice.of(team(), "ghost", false));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }
// Same input, same instructions, one thing changed — the only shape in which "would the
    // cheaper model do this just as well" has an answer worth reading.
    @Test
    void the_block_can_be_re_run_on_a_different_model() {
        FlowGraph slice = BlockSlice.of(team(), "writer", false, "claude-haiku-4-5");

        assertThat(COMPILER.compile(slice).coordinator().model.id).isEqualTo("claude-haiku-4-5");
    }

    @Test
    void the_agents_it_delegates_to_keep_their_own_model() {
        FlowGraph slice = BlockSlice.of(team(), "writer", true, "claude-haiku-4-5");

        CompiledFlow compiled = COMPILER.compile(slice);
        assertThat(compiled.coordinator().model.id).isEqualTo("claude-haiku-4-5");
        // Reviewer names no model of its own in this fixture, so what matters is that the
        // override did not reach it: moving four models at once compares two flows, not one box.
        assertThat(compiled.subAgents()).allSatisfy(s ->
                assertThat(s.model == null ? null : s.model.id).isNotEqualTo("claude-haiku-4-5"));
    }

    @Test
    void the_flow_on_disk_keeps_its_model() {
        FlowGraph flow = team();
        BlockSlice.of(flow, "writer", false, "claude-haiku-4-5");

        assertThat(flow.nodesOrEmpty().stream().filter(n -> n.id().equals("writer")).findFirst()
                .orElseThrow().dataOrEmpty()).doesNotContainKey("model");
    }
}
