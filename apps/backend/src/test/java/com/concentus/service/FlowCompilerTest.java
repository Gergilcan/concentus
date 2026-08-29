package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link FlowCompiler}'s pure flow -> spec compilation logic. */
class FlowCompilerTest {

    private final FlowCompiler compiler = new FlowCompiler();

    private static FlowNode agent(String id, String role, String name) {
        return new FlowNode(id, "agent", role, Map.of("name", name, "systemPrompt", "do stuff"));
    }

    private static FlowNode mcp(String id, String name, String url) {
        return new FlowNode(id, "mcp", null, Map.of("name", name, "url", url));
    }

    private static FlowEdge edge(String source, String target) {
        return new FlowEdge(source + "-" + target, source, target);
    }

    // ------------------------------------------------------------- valid flow

    @Test
    void retriesOnTheNodeReachTheSpecAndBlankMeansTheDeploymentsDefault() {
        FlowNode lead = agent("a1", "coordinator", "Lead");
        FlowNode counted = new FlowNode("w1", "agent", "subagent",
                Map.of("name", "Careful", "retries", 2));
        FlowNode none = new FlowNode("w2", "agent", "subagent",
                Map.of("name", "Once", "retries", 0));
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.of(lead, counted, none),
                List.of(edge("a1", "w1"), edge("a1", "w2")), null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        // -1 is "not set": the executor falls back to its own configured count. 0 is a real
        // answer — one attempt — and must not be mistaken for unset.
        assertThat(compiled.coordinator().retries).isEqualTo(-1);
        assertThat(compiled.subAgents()).extracting(s -> s.name, s -> s.retries)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Careful", 2),
                        org.assertj.core.groups.Tuple.tuple("Once", 0));
    }

    @Test
    void compilesAValidSingleAgentFlow() {
        FlowNode a = agent("a1", "coordinator", "Solo");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.of(a), List.<FlowEdge>of(),
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        assertThat(compiled.coordinator().name).isEqualTo("Solo");
        assertThat(compiled.coordinator().nodeId).isEqualTo("a1");
        assertThat(compiled.subAgents()).isEmpty();
    }

    @Test
    void notesAndFramesAreNotBlocksAndTheCompilerLooksPastThem() {
        // What the author drew for the next reader: a sticky note, a frame with the agent inside
        // it. Neither is a block; a compiler that tripped over them would refuse a flow that ran
        // yesterday because somebody annotated it today.
        FlowNode a = new FlowNode("a1", "agent", "coordinator", Map.of("name", "Solo",
                "systemPrompt", "do stuff", "_pos", Map.of("x", 150, "y", 160), "_parent", "g1"));
        FlowNode note = new FlowNode("n1", "note", null, Map.of("text", "Runs nightly", "color", "yellow"));
        FlowNode frame = new FlowNode("g1", "group", null, Map.of("label", "Ingest", "color", "blue",
                "_size", Map.of("w", 480, "h", 260)));
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.of(frame, a, note), List.<FlowEdge>of(),
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        assertThat(compiled.coordinator().name).isEqualTo("Solo");
        assertThat(compiled.subAgents()).isEmpty();
        assertThat(compiled.merger()).isNull();
        assertThat(compiled.afterFlows()).isEmpty();
    }

    @Test
    void singleAgentWithNoRoleIsTreatedAsCoordinator() {
        // No agent is explicitly marked "coordinator", but since there's only one agent it's used as such.
        FlowNode a = agent("a1", null, "Solo");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.of(a), List.<FlowEdge>of(),
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        assertThat(compiled.coordinator().nodeId).isEqualTo("a1");
    }

    // ---------------------------------------------------- planner access setting

    @Test
    void coordinatorAccessPassesThroughAndTyposLandOnAuto() {
        // Absent → auto (the derived rule decides at run time).
        FlowNode absent = agent("a1", "coordinator", "Coord");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.of(absent),
                List.<FlowEdge>of(), null, List.<String>of(), null, null);
        assertThat(compiler.compile(flow).coordinator().coordinatorAccess).isEmpty();

        FlowNode forced = new FlowNode("a1", "agent", "coordinator",
                Map.of("name", "Coord", "coordinatorAccess", "read-only"));
        FlowGraph flow2 = new FlowGraph("f1", "Flow", "managed", List.of(forced),
                List.<FlowEdge>of(), null, List.<String>of(), null, null);
        assertThat(compiler.compile(flow2).coordinator().coordinatorAccess).isEqualTo("read-only");

        // A typo can only ever land back on auto — it must never force a widening.
        FlowNode typo = new FlowNode("a1", "agent", "coordinator",
                Map.of("name", "Coord", "coordinatorAccess", "mayact"));
        FlowGraph flow3 = new FlowGraph("f1", "Flow", "managed", List.of(typo),
                List.<FlowEdge>of(), null, List.<String>of(), null, null);
        assertThat(compiler.compile(flow3).coordinator().coordinatorAccess).isEmpty();
    }

    // ------------------------------------------------------------- merge node

    @Test
    void aMergeNodeCompilesIntoTheMergerSpec() {
        FlowNode a = agent("a1", "coordinator", "Coord");
        FlowNode m = new FlowNode("m1", "merge", null,
                Map.of("name", "Merge", "model", "claude-sonnet-5", "systemPrompt", "reconcile"));
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.of(a, m), List.<FlowEdge>of(),
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        assertThat(compiled.merger()).isNotNull();
        assertThat(compiled.merger().name).isEqualTo("Merge");
        assertThat(compiled.merger().model.id).isEqualTo("claude-sonnet-5");
        assertThat(compiled.merger().systemPrompt).isEqualTo("reconcile");
        // A merge node is not a sub-agent: it must never join the delegation roster.
        assertThat(compiled.subAgents()).isEmpty();
    }

    @Test
    void aFlowWithoutAMergeNodeHasNoMerger() {
        FlowNode a = agent("a1", "coordinator", "Solo");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.of(a), List.<FlowEdge>of(),
                null, List.<String>of(), null, null);

        assertThat(compiler.compile(flow).merger()).isNull();
    }

    @Test
    void twoMergeNodesAreRejected() {
        FlowNode a = agent("a1", "coordinator", "Coord");
        FlowNode m1 = new FlowNode("m1", "merge", null, Map.of("name", "Merge"));
        FlowNode m2 = new FlowNode("m2", "merge", null, Map.of("name", "Merge 2"));
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.of(a, m1, m2),
                List.<FlowEdge>of(), null, List.<String>of(), null, null);

        assertThatThrownBy(() -> compiler.compile(flow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("merge");
    }

    // ------------------------------------------------------------- verifier node

    @Test
    void aVerifierNodeCompilesIntoTheVerifierSpec() {
        FlowNode a = agent("a1", "coordinator", "Coord");
        FlowNode v = new FlowNode("v1", "verifier", null,
                Map.of("name", "Verifier", "model", "claude-sonnet-5", "systemPrompt", "be harsh"));
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.of(a, v), List.<FlowEdge>of(),
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        assertThat(compiled.verifier()).isNotNull();
        assertThat(compiled.verifier().name).isEqualTo("Verifier");
        assertThat(compiled.verifier().model.id).isEqualTo("claude-sonnet-5");
        assertThat(compiled.verifier().systemPrompt).isEqualTo("be harsh");
        // A verifier is not a sub-agent: it must never join the delegation roster.
        assertThat(compiled.subAgents()).isEmpty();
        assertThat(compiled.merger()).isNull();
    }

    @Test
    void twoVerifierNodesAreRejected() {
        FlowNode a = agent("a1", "coordinator", "Coord");
        FlowNode v1 = new FlowNode("v1", "verifier", null, Map.of("name", "V1"));
        FlowNode v2 = new FlowNode("v2", "verifier", null, Map.of("name", "V2"));
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.of(a, v1, v2),
                List.<FlowEdge>of(), null, List.<String>of(), null, null);

        assertThatThrownBy(() -> compiler.compile(flow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verifier");
    }

    // ------------------------------------------------------ delegation wiring

    @Test
    void onlyAgentsLinkedToCoordinatorBecomeSubAgents() {
        FlowNode coord = agent("c1", "coordinator", "Coordinator");
        FlowNode wired = agent("s1", "subagent", "Wired");
        FlowNode unwired = agent("s2", "subagent", "Unwired");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed",
                List.of(coord, wired, unwired),
                List.of(edge("c1", "s1")),
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        assertThat(compiled.coordinator().nodeId).isEqualTo("c1");
        assertThat(compiled.subAgents()).hasSize(1);
        assertThat(compiled.subAgents().get(0).nodeId).isEqualTo("s1");
        assertThat(compiled.subAgents().get(0).name).isEqualTo("Wired");
    }

    @Test
    void delegationEdgeIsUndirectedForWiring() {
        // The coordinator<->subagent edge direction shouldn't matter for whether the agent is wired in.
        FlowNode coord = agent("c1", "coordinator", "Coordinator");
        FlowNode sub = agent("s1", "subagent", "Sub");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed",
                List.of(coord, sub),
                List.of(edge("s1", "c1")), // reversed direction
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        assertThat(compiled.subAgents()).extracting(s -> s.nodeId).containsExactly("s1");
    }

    @Test
    void resourceNodesAreOnlyAttachedToTheAgentTheyAreWiredTo() {
        FlowNode coord = agent("c1", "coordinator", "Coordinator");
        FlowNode sub = agent("s1", "subagent", "Sub");
        FlowNode mcpNode = mcp("m1", "github", "https://example.com/mcp");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed",
                List.of(coord, sub, mcpNode),
                List.of(edge("c1", "s1"), edge("s1", "m1")),
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        AgentSpec subSpec = compiled.subAgents().get(0);
        assertThat(subSpec.mcpServers).hasSize(1);
        assertThat(subSpec.mcpServers.get(0).name).isEqualTo("github");
        assertThat(compiled.coordinator().mcpServers).isEmpty();
    }

    // ----------------------------------------------------------- invalid flows

    @Test
    void flowWithNoAgentNodesThrows() {
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.<FlowNode>of(), List.<FlowEdge>of(),
                null, List.<String>of(), null, null);

        assertThatThrownBy(() -> compiler.compile(flow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no agent nodes");
    }

    @Test
    void multipleAgentsWithoutACoordinatorThrows() {
        FlowNode a1 = agent("a1", "subagent", "One");
        FlowNode a2 = agent("a2", "subagent", "Two");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.of(a1, a2), List.<FlowEdge>of(),
                null, List.<String>of(), null, null);

        assertThatThrownBy(() -> compiler.compile(flow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no coordinator");
    }

    @Test
    void multipleCoordinatorsThrows() {
        FlowNode a1 = agent("a1", "coordinator", "One");
        FlowNode a2 = agent("a2", "coordinator", "Two");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed", List.of(a1, a2), List.<FlowEdge>of(),
                null, List.<String>of(), null, null);

        assertThatThrownBy(() -> compiler.compile(flow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than one coordinator");
    }

    @Test
    void badEdgeWiringAResourceMissingRequiredFieldsThrowsOnValidation() {
        // An MCP node wired to the coordinator but missing its `url` produces an invalid AgentSpec;
        // FlowCompiler surfaces that as a compile-time failure rather than a silently broken agent.
        FlowNode coord = agent("c1", "coordinator", "Coordinator");
        FlowNode badMcp = new FlowNode("m1", "mcp", null, Map.of("name", "broken")); // no url
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed",
                List.of(coord, badMcp),
                List.of(edge("c1", "m1")),
                null, List.<String>of(), null, null);

        assertThatThrownBy(() -> compiler.compile(flow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }
// ---------------------------------------------- nested delegation (review chains)

    @Test
    void anAgentWiredBehindASubAgentIsStillPartOfTheRun() {
        // Tech Lead -> Backend Engineer -> Code Reviewer. The reviewer is deliberately NOT wired
        // to the coordinator: it reviews that engineer's work, not the flow's work in general.
        FlowNode coord = agent("c1", "coordinator", "Tech Lead");
        FlowNode backend = agent("s1", "subagent", "Backend Engineer");
        FlowNode reviewer = agent("s2", "subagent", "Code Reviewer");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed",
                List.of(coord, backend, reviewer),
                List.of(edge("c1", "s1"), edge("s1", "s2")),
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        // Previously the reviewer was dropped, so it had no definition file and delegating to it
        // silently fell back to a built-in agent.
        assertThat(compiled.subAgents()).extracting(s -> s.nodeId).containsExactly("s1", "s2");
    }

    @Test
    void eachAgentDelegatesOnlyToTheAgentsWiredBehindIt() {
        FlowNode coord = agent("c1", "coordinator", "Tech Lead");
        FlowNode backend = agent("s1", "subagent", "Backend Engineer");
        FlowNode frontend = agent("s2", "subagent", "Frontend Engineer");
        FlowNode backendReviewer = agent("s3", "subagent", "Backend Reviewer");
        FlowNode frontendReviewer = agent("s4", "subagent", "Frontend Reviewer");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed",
                List.of(coord, backend, frontend, backendReviewer, frontendReviewer),
                List.of(edge("c1", "s1"), edge("c1", "s2"),
                        edge("s1", "s3"), edge("s2", "s4")),
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        assertThat(compiled.coordinator().delegatesTo)
                .containsExactlyInAnyOrder("backend-engineer", "frontend-engineer");
        // Each reviewer belongs to its own engineer, not to the coordinator and not to each other.
        assertThat(specFor(compiled, "s1").delegatesTo).containsExactly("backend-reviewer");
        assertThat(specFor(compiled, "s2").delegatesTo).containsExactly("frontend-reviewer");
        assertThat(specFor(compiled, "s3").delegatesTo).isEmpty();
    }

    @Test
    void agentsSharingADisplayNameGetDistinctCliNames() {
        // Two "Code Reviewer" nodes is a reasonable thing to draw, but they cannot share a
        // definition file — one would overwrite the other and their logs would be identical.
        FlowNode coord = agent("c1", "coordinator", "Tech Lead");
        FlowNode backend = agent("s1", "subagent", "Backend Engineer");
        FlowNode frontend = agent("s2", "subagent", "Frontend Engineer");
        FlowNode r1 = agent("s3", "subagent", "Code Reviewer");
        FlowNode r2 = agent("s4", "subagent", "Code Reviewer");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed",
                List.of(coord, backend, frontend, r1, r2),
                List.of(edge("c1", "s1"), edge("c1", "s2"),
                        edge("s1", "s3"), edge("s2", "s4")),
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        String a = specFor(compiled, "s3").cliName;
        String b = specFor(compiled, "s4").cliName;
        assertThat(a).isNotEqualTo(b);
        assertThat(List.of(a, b)).containsExactlyInAnyOrder("code-reviewer", "code-reviewer-2");
        // and each engineer points at its own reviewer
        assertThat(specFor(compiled, "s1").delegatesTo).containsExactly(a);
        assertThat(specFor(compiled, "s2").delegatesTo).containsExactly(b);
    }

    @Test
    void aCycleBetweenAgentsTerminates() {
        FlowNode coord = agent("c1", "coordinator", "Tech Lead");
        FlowNode a1 = agent("s1", "subagent", "A");
        FlowNode a2 = agent("s2", "subagent", "B");
        FlowGraph flow = new FlowGraph("f1", "Flow", "managed",
                List.of(coord, a1, a2),
                List.of(edge("c1", "s1"), edge("s1", "s2"), edge("s2", "s1")),
                null, List.<String>of(), null, null);

        CompiledFlow compiled = compiler.compile(flow);

        assertThat(compiled.subAgents()).extracting(s -> s.nodeId).containsExactly("s1", "s2");
    }

    private static AgentSpec specFor(CompiledFlow compiled, String nodeId) {
        return compiled.subAgents().stream()
                .filter(s -> nodeId.equals(s.nodeId)).findFirst().orElseThrow();
    }

    @Test
    void perAgentToolAllowlistFlowsThroughToTheSpec() {
        FlowNode coord = new FlowNode("c", "agent", "coordinator", Map.of("name", "Lead", "systemPrompt", "x"));
        FlowNode sub = new FlowNode("s", "agent", "subagent", Map.of(
                "name", "Reviewer", "systemPrompt", "x",
                "tools", java.util.List.of("Read", "Grep", "Glob")));
        FlowGraph flow = new FlowGraph("f", "F", "local", java.util.List.of(coord, sub),
                java.util.List.of(new FlowEdge("e1", "c", "s")), null, java.util.List.of(), null, null);

        CompiledFlow compiled = new FlowCompiler().compile(flow);

        assertThat(compiled.subAgents().get(0).tools).containsExactly("Read", "Grep", "Glob");
        // Blank stays blank — the executor omits the frontmatter line, which the CLI reads as
        // "inherit all"; an empty list would instead mean "none".
        assertThat(compiled.coordinator().tools).isEmpty();
    }

    // ---------------------------------------------------------- library links

    private static com.concentus.model.LibraryAgent reviewer(long version) {
        return new com.concentus.model.LibraryAgent("lib-reviewer", "Reviewer", "claude-sonnet-4-5",
                "medium", 9000, "Review the diff for correctness.", "Use for every review.", version);
    }

    /** A block that linked the reviewer at v1 and still carries that version's copy of the fields. */
    private static FlowNode linkedWorker() {
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("name", "Old copy");
        data.put("model", "claude-opus-4-8");
        data.put("effort", "high");
        data.put("maxTokens", 16000);
        data.put("systemPrompt", "stale prompt");
        data.put("description", "stale routing");
        data.put("libraryAgentId", "lib-reviewer");
        data.put("libraryVersion", 1);
        // The per-flow half: what this reviewer gets to use HERE.
        data.put("tools", List.of("Read", "Grep"));
        data.put("retries", 2);
        data.put("fallbackModelId", "claude-opus-4-8");
        data.put("contextFolders", List.of("C:/code/wirej"));
        data.put("facadeProfileId", "fp-readonly");
        return new FlowNode("w1", "agent", "subagent", data);
    }

    private static FlowGraph leadWith(FlowNode worker) {
        return new FlowGraph("f1", "Flow", "managed", List.of(agent("a1", "coordinator", "Lead"), worker),
                List.of(edge("a1", "w1")), null, List.<String>of(), null, null);
    }

    @Test
    void aLinkedBlockTakesItsDefinitionFromTheLibraryAndKeepsItsOwnPerFlowFields() {
        // The library has moved to v3 since the block linked it; the block's copy is v1's. The run
        // must get v3 — that is what a link is for — without anyone re-saving the flow.
        FlowCompiler linked = new FlowCompiler(
                id -> id.equals("lib-reviewer") ? java.util.Optional.of(reviewer(3)) : java.util.Optional.empty());

        AgentSpec spec = linked.compile(leadWith(linkedWorker())).subAgents().get(0);

        assertThat(spec.name).isEqualTo("Reviewer");
        assertThat(spec.model.id).isEqualTo("claude-sonnet-4-5");
        assertThat(spec.model.effort).isEqualTo("medium");
        assertThat(spec.model.maxTokens).isEqualTo(9000);
        assertThat(spec.systemPrompt).isEqualTo("Review the diff for correctness.");
        assertThat(spec.description).isEqualTo("Use for every review.");
        // The library says what the agent is; the flow says what it may use here.
        assertThat(spec.tools).containsExactly("Read", "Grep");
        assertThat(spec.retries).isEqualTo(2);
        assertThat(spec.fallbackModelId).isEqualTo("claude-opus-4-8");
        assertThat(spec.contextFolders).containsExactly("C:/code/wirej");
        assertThat(spec.facadeProfileId).isEqualTo("fp-readonly");
    }

    @Test
    void aLinkedBlockWhoseLibraryAgentIsGoneIsRefusedNamingTheBlockAndTheId() {
        // The default compiler resolves nothing — exactly what a deleted agent looks like. Running
        // the block's stale copy instead would run a reviewer somebody deleted on purpose.
        assertThatThrownBy(() -> compiler.compile(leadWith(linkedWorker())))
                .isInstanceOf(FlowCompiler.MissingLibraryAgent.class)
                .hasMessageContaining("'Old copy'")
                .hasMessageContaining("lib-reviewer")
                .satisfies(e -> assertThat(((FlowCompiler.MissingLibraryAgent) e).nodeId()).isEqualTo("w1"));
    }

    @Test
    void anUnlinkedBlockNeverAsksTheLibrary() {
        // A library that answers every id: if the compiler asked for an unlinked block, "Old copy"
        // would come back as "Reviewer" and a plain block would silently change under a link it
        // never made.
        FlowCompiler eager = new FlowCompiler(id -> java.util.Optional.of(reviewer(3)));
        FlowNode plain = new FlowNode("w1", "agent", "subagent",
                Map.of("name", "Old copy", "systemPrompt", "own prompt"));

        AgentSpec spec = eager.compile(leadWith(plain)).subAgents().get(0);

        assertThat(spec.name).isEqualTo("Old copy");
        assertThat(spec.systemPrompt).isEqualTo("own prompt");
    }

    // ------------------------------------------------------- organization policy: facades

    /** A fan-out lead with one worker wired to a remote MCP server and one with nothing wired. */
    private static FlowGraph fanoutWithAWiredWorker(String workerProfile) {
        FlowNode lead = new FlowNode("c1", "agent", "coordinator",
                Map.of("name", "Lead", "execution", "fanout"));
        Map<String, Object> w = new java.util.HashMap<>(Map.of("name", "Reader"));
        if (workerProfile != null) w.put("facadeProfileId", workerProfile);
        FlowNode wired = new FlowNode("w1", "agent", "subagent", w);
        FlowNode lonely = agent("w2", "subagent", "Thinker");
        FlowNode mcpNode = mcp("m1", "linear", "https://mcp.linear.app/mcp");
        return new FlowGraph("f1", "Flow", "managed", List.of(lead, wired, lonely, mcpNode),
                List.of(edge("c1", "w1"), edge("c1", "w2"), edge("w1", "m1")),
                null, List.<String>of(), null, null);
    }

    private static FlowCompiler under(com.concentus.policy.OrgPolicy policy) {
        return new FlowCompiler(id -> java.util.Optional.empty(), () -> policy);
    }

    private static AgentSpec named(CompiledFlow compiled, String name) {
        return compiled.subAgents().stream().filter(s -> s.name.equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theOrganizationsDefaultProfileFillsAWorkerThatNamesNoneAndIsMarkedAsSuch() {
        CompiledFlow compiled = under(new com.concentus.policy.OrgPolicy("o", "fprof_reader", false, "", null, false))
                .compile(fanoutWithAWiredWorker(null));

        AgentSpec reader = named(compiled, "Reader");
        assertThat(reader.facadeProfileId).isEqualTo("fprof_reader");
        assertThat(reader.facadeByPolicy).isTrue();
        // Nothing to reach, nothing to run behind: the default is not sprayed over every block.
        assertThat(named(compiled, "Thinker").facadeProfileId).isEmpty();
        assertThat(named(compiled, "Thinker").facadeByPolicy).isFalse();
    }

    @Test
    void aWorkersOwnProfileWinsOverTheOrganizationsDefault() {
        CompiledFlow compiled = under(new com.concentus.policy.OrgPolicy("o", "fprof_reader", true, "", null, false))
                .compile(fanoutWithAWiredWorker("fprof_mine"));

        AgentSpec reader = named(compiled, "Reader");
        assertThat(reader.facadeProfileId).isEqualTo("fprof_mine");
        assertThat(reader.facadeByPolicy).isFalse();
    }

    @Test
    void aRequiredFacadeWithNoDefaultRefusesTheFlowNamingTheWorker() {
        FlowCompiler strict = under(new com.concentus.policy.OrgPolicy("o", "", true, "", null, false));

        assertThatThrownBy(() -> strict.compile(fanoutWithAWiredWorker(null)))
                .isInstanceOfSatisfying(FlowCompiler.PolicyViolation.class, e -> {
                    assertThat(e.nodeId()).isEqualTo("w1");
                    assertThat(e.getMessage()).contains("organization's policy").contains("'Reader'")
                            .contains("Resources → Policies");
                });
        // The same rule is satisfied by a profile on the block, with no default in sight.
        assertThat(strict.compile(fanoutWithAWiredWorker("fprof_mine")).subAgents()).hasSize(2);
    }

    @Test
    void theFacadeRuleIsOnlyForIndependentWorkers() {
        FlowCompiler strict = under(new com.concentus.policy.OrgPolicy("o", "fprof_reader", true, "", null, false));
        FlowNode lead = agent("c1", "coordinator", "Lead");
        FlowNode sub = agent("s1", "subagent", "Sub");
        FlowNode mcpNode = mcp("m1", "linear", "https://mcp.linear.app/mcp");
        FlowGraph shared = new FlowGraph("f1", "Flow", "managed", List.of(lead, sub, mcpNode),
                List.of(edge("c1", "s1"), edge("s1", "m1")), null, List.<String>of(), null, null);

        // A sub-agent of a shared session has no facade to run behind, so nothing is filled or refused.
        assertThat(strict.compile(shared).subAgents().get(0).facadeProfileId).isEmpty();
    }

    @Test
    void withNoPolicyTheCompilerDoesExactlyWhatItDidBefore() {
        CompiledFlow compiled = new FlowCompiler().compile(fanoutWithAWiredWorker(null));

        assertThat(named(compiled, "Reader").facadeProfileId).isEmpty();
        assertThat(named(compiled, "Reader").facadeByPolicy).isFalse();
    }
}
