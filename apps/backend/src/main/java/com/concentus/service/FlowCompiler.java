package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.config.AgentSpec.ModelSpec;
import com.concentus.config.AgentSpec.RepoSpec;
import com.concentus.config.AgentSpec.KnowledgeSourceSpec;
import com.concentus.config.AgentSpec.SqlSourceSpec;
import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.model.LibraryAgent;
import com.concentus.store.AgentLibraryStore;
import com.concentus.support.Variables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static com.concentus.support.MapValues.bool;
import static com.concentus.support.MapValues.lng;
import static com.concentus.support.MapValues.str;
import static com.concentus.support.MapValues.strList;
import static com.concentus.support.MapValues.strMap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Compiles a {@link FlowGraph} into a coordinator + sub-agent {@link AgentSpec}s. */
@Component
public class FlowCompiler {

    /**
     * Where a linked block's definition comes from. A function rather than the store itself so a
     * test can compile a linked flow against three records in a map, and so the compiler stays
     * usable with no database at all — which is how every existing compiler test runs.
     */
    @FunctionalInterface
    public interface LibraryResolver {
        Optional<LibraryAgent> find(String id);
    }

    /**
     * A linked block whose library agent is gone. Its own type, and carrying the block, because
     * the doctor points findings at the block they concern — and a deleted library agent is the
     * one compile error whose fix is on a different page than the canvas.
     */
    public static class MissingLibraryAgent extends IllegalArgumentException {
        private final String nodeId;

        MissingLibraryAgent(String message, String nodeId) {
            super(message);
            this.nodeId = nodeId;
        }

        public String nodeId() {
            return nodeId;
        }
    }

    /**
     * A fan-out worker the organization's policy refuses: MCP servers wired, no facade profile
     * of its own, no default to fill it with, and a policy that requires one. Its own type for
     * the reason {@link MissingLibraryAgent} is: the doctor points it at the block, and the fix
     * is on the block (pick a profile) or under Resources → Policies (set a default), not "fix
     * the graph".
     */
    public static class PolicyViolation extends IllegalArgumentException {
        private final String nodeId;

        PolicyViolation(String message, String nodeId) {
            super(message);
            this.nodeId = nodeId;
        }

        public String nodeId() {
            return nodeId;
        }
    }

    private final LibraryResolver library;
    /**
     * The organization's policy at compile time, read per compile rather than once: an admin
     * changing the default profile must reach the next run, not the next restart. A supplier
     * rather than the service so a test compiles against a literal policy with no license, no
     * store and no principal in sight — the same reason the library is a function.
     */
    private final java.util.function.Supplier<com.concentus.policy.OrgPolicy> policy;

    /** No library, no policy: a linked block cannot compile. For tests, and for slices that never link. */
    public FlowCompiler() {
        this(id -> Optional.empty());
    }

    public FlowCompiler(LibraryResolver library) {
        this(library, () -> com.concentus.policy.OrgPolicy.NONE);
    }

    public FlowCompiler(LibraryResolver library, java.util.function.Supplier<com.concentus.policy.OrgPolicy> policy) {
        this.library = library;
        this.policy = policy;
    }

    @Autowired
    public FlowCompiler(AgentLibraryStore store, com.concentus.policy.OrgPolicyService policies) {
        this(store::get, policies::effective);
    }

    public CompiledFlow compile(FlowGraph flow) {
        return compile(flow, Map.of(), null);
    }

    /**
     * Compiles with {@code {{NAME}}} substitution over every prompt-shaped field: system prompts,
     * routing descriptions, and the merge instructions. Values come merged already (organization
     * under the flow's own — see VariableStore.merged); names nothing defines stay verbatim in
     * the prompt and land in {@code unresolved}, so the run can say so instead of an agent
     * quietly receiving a hole.
     */
    public CompiledFlow compile(FlowGraph flow, Map<String, String> variables, Set<String> unresolved) {
        CompiledFlow compiled = compileRaw(flow);
        if (!variables.isEmpty() || unresolved != null) {
            List<AgentSpec> all = new ArrayList<>(compiled.allAgents());
            if (compiled.merger() != null) all.add(compiled.merger());
            if (compiled.verifier() != null) all.add(compiled.verifier());
            for (AgentSpec spec : all) {
                spec.systemPrompt = Variables.substitute(spec.systemPrompt, variables, unresolved);
                spec.description = Variables.substitute(spec.description, variables, unresolved);
            }
        }
        return compiled;
    }

    private CompiledFlow compileRaw(FlowGraph flow) {
        List<FlowNode> agents = byType(flow, "agent");
        Resources resources = Resources.of(flow);

        if (agents.isEmpty()) {
            throw new IllegalArgumentException("Flow has no agent nodes.");
        }

        FlowNode coordinatorNode = coordinatorOf(agents);

        AgentSpec coordinator = buildAgentSpec(coordinatorNode, flow, resources);

        // Every agent reachable from the coordinator through agent-to-agent edges — not just the
        // directly linked ones. A reviewer wired behind an engineer exists to review that
        // engineer's work; it was previously dropped from the run entirely.
        Delegation tree = delegationTree(coordinatorNode, agents, flow.edgesOrEmpty());
        List<AgentSpec> subAgents = new ArrayList<>();
        for (FlowNode n : tree.ordered()) {
            subAgents.add(buildAgentSpec(n, flow, resources));
        }

        assignCliNames(coordinator, subAgents);
        assignRosters(coordinator, subAgents, coordinatorNode, tree);

        // The merge step of a fan-out flow. Same data shape as an agent node, so the same spec
        // builder serves; at most one, because "the merge" must be a single answer.
        AgentSpec merger = singleAgentNode(flow, "merge", resources);

        // The adversarial verifier of a fan-out flow, cut from the same cloth as the merge: at
        // most one, because a worker's output must get ONE verdict, not a committee's.
        AgentSpec verifier = singleAgentNode(flow, "verifier", resources);

        requireEveryFlowNodeIsWired(flow);
        CompiledFlow compiled = new CompiledFlow(coordinator, subAgents, merger, verifier,
                afterFlows(flow), afterMails(flow));
        applyFacadePolicy(compiled);
        return compiled;
    }

    /**
     * The organization's facade rule, applied to every independent worker that reaches MCP.
     *
     * <p>Only for fan-out, because a facade only exists there: a sub-agent of a shared session
     * reaches whatever the session reaches, and no profile could be enforced on it. And only for
     * workers with servers wired — a worker with nothing to reach has nothing to run behind.
     *
     * <p>A blank on the node takes the organization's default, marked as such so the run says
     * so. With no default and a policy that requires one, the flow does not compile: the doctor
     * reports it on the block, and a run refuses to start rather than running a worker open
     * against a rule that says it must not. The merge step counts as a worker here for the same
     * reason it does in the executor — it is an independent process with servers of its own.
     */
    private void applyFacadePolicy(CompiledFlow compiled) {
        if (!compiled.fanout()) return;
        com.concentus.policy.OrgPolicy rules = policy.get();
        if (rules == null) return;
        String fallback = rules.defaultFacadeProfileIdOrEmpty();
        if (fallback.isEmpty() && !rules.requireFacade()) return;

        List<AgentSpec> workers = new ArrayList<>(compiled.subAgents());
        if (compiled.merger() != null) workers.add(compiled.merger());
        for (AgentSpec worker : workers) {
            if (worker.mcpServers.isEmpty()) continue;
            if (worker.facadeProfileId != null && !worker.facadeProfileId.isBlank()) continue;
            if (!fallback.isEmpty()) {
                worker.facadeProfileId = fallback;
                worker.facadeByPolicy = true;
                continue;
            }
            throw new PolicyViolation("The organization's policy requires a facade profile on every "
                    + "independent worker that reaches MCP, and '" + worker.name + "' has none — "
                    + "it is wired to " + worker.mcpServers.size() + " MCP server(s). Pick a profile "
                    + "on the block, or have an admin set a default under Resources → Policies.",
                    worker.nodeId);
        }
    }

    /**
     * Send mail nodes, every one wired out of a block: hand-offs that send when this run finishes.
     *
     * <p>Only ever a target — a mailbox has nothing to hand an agent — so there is no direction to
     * read and no legacy field to honour. Which output the wire left from (main, error, rejected)
     * is read from the graph when the run ends, exactly as it is for a flow on the same wire.
     *
     * <p>A node wired to nothing is refused for the reason {@link #requireEveryFlowNodeIsWired}
     * gives: it is on the canvas, so somebody believes their run mails them, and the only evidence
     * otherwise would be an inbox that stays empty.
     */
    private static List<com.concentus.mail.MailHandOffSpec> afterMails(FlowGraph flow) {
        List<com.concentus.mail.MailHandOffSpec> out = new ArrayList<>();
        Set<String> agentIds = agentIds(flow);
        for (FlowNode node : byType(flow, "mail")) {
            boolean fedByAnAgent = flow.edgesOrEmpty().stream()
                    .anyMatch(e -> agentIds.contains(e.source()) && node.id().equals(e.target()))
                    || agentIds.contains(String.valueOf(FlowGates.sourceThroughGates(flow, node.id())));
            if (!fedByAnAgent) {
                throw new IllegalArgumentException("The mail node '"
                        + str(node.dataOrEmpty(), "label", node.id())
                        + "' is not wired to a block, so it would never send. Wire it out of a "
                        + "block's output — the main one, on error, or on rejected.");
            }
            out.add(com.concentus.mail.MailHandOffSpec.from(node));
        }
        return out;
    }

    /**
     * A flow node joined to no agent at all does nothing, so it is refused rather than ignored.
     *
     * <p>Ignoring it is the worst of the three options: the box sits on the canvas, so somebody
     * believes their flow chains onward, and the only evidence otherwise is a run that quietly
     * ends. Refusing says which box, while they are looking at it.
     */
    private static void requireEveryFlowNodeIsWired(FlowGraph flow) {
        Set<String> agentIds = agentIds(flow);
        for (FlowNode node : byType(flow, "flow")) {
            // A node from before the direction rule said what it was in a field, and an "after"
            // one was legitimately wired to nothing. Refusing those would break flows that ran
            // yesterday — and a cron flow breaks at 07:00, with nobody reading the console.
            if (!legacyMode(node).isBlank()) continue;
            boolean joined = flow.edgesOrEmpty().stream().anyMatch(e ->
                    (agentIds.contains(e.source()) && node.id().equals(e.target()))
                            || (node.id().equals(e.source()) && agentIds.contains(e.target())))
                    // A condition or for-each drawn in between is part of the same wire, not a
                    // disconnection — see FlowGates.sourceThroughGates.
                    || agentIds.contains(String.valueOf(FlowGates.sourceThroughGates(flow, node.id())));
            if (!joined) {
                throw new IllegalArgumentException("The flow node '"
                        + str(node.dataOrEmpty(), "label", node.id())
                        + "' is not connected to an agent, so it would never run. Wire it into an "
                        + "agent to run it first, or out of one to run it afterwards.");
            }
        }
    }

    /**
     * Flow nodes wired OUT of an agent: hand-offs that fire when this run finishes.
     *
     * <p>Direction is the whole rule, and it replaced a field on the node that asked the canvas to
     * say twice what it already showed once. A box drawn feeding an agent runs before it, exactly
     * as an input node does; a box the agent feeds runs after, exactly as its output would.
     *
     * <p>A node wired to nothing is refused rather than ignored — see {@link #subflowSpec}. It is
     * on the canvas, so somebody believes it runs.
     */
    private static List<AgentSpec.SubflowSpec> afterFlows(FlowGraph flow) {
        List<AgentSpec.SubflowSpec> out = new ArrayList<>();
        Set<String> agentIds = agentIds(flow);
        for (FlowNode node : byType(flow, "flow")) {
            String legacy = legacyMode(node);
            if (!legacy.isBlank()) {
                // Saved when the node still carried the field: it keeps deciding, so a flow that
                // ran yesterday runs the same today. Nodes drawn since have no field to read.
                if ("after".equalsIgnoreCase(legacy)) out.add(subflowSpec(node, flow));
                continue;
            }
            boolean fedByAnAgent = flow.edgesOrEmpty().stream()
                    .anyMatch(e -> agentIds.contains(e.source()) && node.id().equals(e.target()))
                    // Through the gates, if any: a hand-off with a condition in front of it is
                    // still a hand-off, and drawing the rule must not change when it fires.
                    || agentIds.contains(String.valueOf(FlowGates.sourceThroughGates(flow, node.id())));
            if (fedByAnAgent) out.add(subflowSpec(node, flow));
        }
        return out;
    }

    /**
     * The one agent that gives the orders. A lone agent leads by default — asking someone to tick
     * "coordinator" on a flow with nothing to coordinate is ceremony — but past one, the role has
     * to be stated, because guessing wrong reverses who instructs whom.
     */
    static FlowNode coordinatorOf(List<FlowNode> agents) {
        List<FlowNode> coordinators = agents.stream()
                .filter(n -> "coordinator".equalsIgnoreCase(n.role()))
                .toList();

        if (coordinators.size() == 1) return coordinators.get(0);
        if (coordinators.isEmpty() && agents.size() == 1) return agents.get(0);
        if (coordinators.isEmpty()) {
            throw new IllegalArgumentException(
                    "Flow has multiple agents but no coordinator — mark exactly one agent as coordinator.");
        }
        throw new IllegalArgumentException("Flow has more than one coordinator — mark exactly one.");
    }

    /**
     * Who delegates to whom across a whole flow, keyed by node id — the same walk {@link #compile}
     * runs, exposed so a slice of a flow ({@link BlockSlice}) can agree with it. Empty when the
     * flow has no agents, or no single coordinator to walk out from: a caller asking about
     * delegation in a flow that cannot compile wants an empty answer, not the compiler's exception.
     */
    static Map<String, String> delegatorsOf(FlowGraph flow) {
        List<FlowNode> agents = byType(flow, "agent");
        if (agents.isEmpty()) return Map.of();
        FlowNode coordinatorNode;
        try {
            coordinatorNode = coordinatorOf(agents);
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
        return delegationTree(coordinatorNode, agents, flow.edgesOrEmpty()).delegatorOf();
    }

    /**
     * The {@code mode} a flow node was saved with — "tool" or "after" — or blank on one drawn
     * since the drawing itself started saying when a flow runs.
     *
     * <p>Kept because the field shipped: a released version wrote it onto every flow node people
     * drew, and reading those nodes by direction instead would silently change what their flows
     * do. A tool the agent chose to call would start running on its own, and an "after" node
     * wired to nothing — which was the correct way to draw a hand-off — would stop the flow from
     * compiling at all. New nodes carry no mode, so nothing here applies to them.
     */
    private static String legacyMode(FlowNode node) {
        return str(node.dataOrEmpty(), "mode", "");
    }

    /** Every node that behaves as an agent, so an edge can be read as "to" or "from" one. */
    private static Set<String> agentIds(FlowGraph flow) {
        Set<String> ids = new LinkedHashSet<>();
        for (String type : List.of("agent", "merge", "verifier")) {
            for (FlowNode n : byType(flow, type)) ids.add(n.id());
        }
        return ids;
    }

    /** One flow node as a spec, with the two mistakes that are worth refusing on sight. */
    private static AgentSpec.SubflowSpec subflowSpec(FlowNode node, FlowGraph flow) {
        Map<String, Object> data = node.dataOrEmpty();
        AgentSpec.SubflowSpec spec = new AgentSpec.SubflowSpec();
        spec.nodeId = node.id();
        spec.label = str(data, "label", node.id());
        spec.flowId = str(data, "flowId", "");
        spec.waitForResult = bool(data, "waitForResult", true);

        if (spec.flowId.isBlank()) {
            throw new IllegalArgumentException("The flow node '" + spec.label
                    + "' has no flow chosen yet.");
        }
        // The indirect case (A runs B runs A) is caught at run time, where the whole chain is
        // known. This one is catchable while the person is still looking at the canvas.
        if (spec.flowId.equals(flow.id())) {
            throw new IllegalArgumentException("The flow node '" + spec.label
                    + "' runs this same flow — a flow cannot run itself.");
        }
        return spec;
    }

    /**
     * The at-most-one node of a type, compiled with the agent spec builder; null when the flow
     * draws none. More than one is refused rather than silently picking a winner.
     */
    private AgentSpec singleAgentNode(FlowGraph flow, String type, Resources resources) {
        List<FlowNode> found = byType(flow, type);
        if (found.size() > 1) {
            throw new IllegalArgumentException(
                    "Flow has more than one " + type + " node — keep exactly one.");
        }
        return found.isEmpty() ? null : buildAgentSpec(found.get(0), flow, resources);
    }

    /**
     * The resource nodes an agent can be wired to, gathered once and passed as one — the five
     * lists were threaded through every spec-building call site individually.
     */
    private record Resources(List<FlowNode> mcps, List<FlowNode> repos, List<FlowNode> sqls,
                             List<FlowNode> knowledges, List<FlowNode> apis, List<FlowNode> flows) {

        static Resources of(FlowGraph flow) {
            return new Resources(byType(flow, "mcp"), byType(flow, "repo"), byType(flow, "sql"),
                    byType(flow, "knowledge"), byType(flow, "api"), byType(flow, "flow"));
        }
    }

    /** Agents reachable from the coordinator, plus who delegates to whom. */
    private record Delegation(List<FlowNode> ordered, Map<String, String> delegatorOf) {
    }

    /**
     * Builds the delegation hierarchy by walking out from the coordinator breadth-first.
     *
     * <p>Edges are followed in <b>either</b> direction — wiring has always been direction-agnostic
     * on this canvas, and requiring a particular direction would silently drop flows drawn the
     * other way. Direction is instead derived from the walk: whichever agent first reaches another
     * becomes its delegator. That gives an unambiguous tree from any drawing, and each node is
     * visited once, so a cycle terminates rather than looping forever.
     */
    private static Delegation delegationTree(FlowNode coordinator, List<FlowNode> agents,
                                             List<FlowEdge> edges) {
        Map<String, FlowNode> byId = new LinkedHashMap<>();
        for (FlowNode n : agents) byId.put(n.id(), n);

        List<FlowNode> ordered = new ArrayList<>();
        Map<String, String> delegatorOf = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        seen.add(coordinator.id());
        Deque<String> queue = new ArrayDeque<>();
        queue.add(coordinator.id());

        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            for (String next : adjacentAgents(current, byId.keySet(), edges)) {
                if (!seen.add(next)) continue;
                FlowNode node = byId.get(next);
                if (node == null) continue;
                ordered.add(node);
                delegatorOf.put(next, current);
                queue.add(next);
            }
        }
        return new Delegation(ordered, delegatorOf);
    }

    /** Agent nodes joined to this one by an edge, in either direction. */
    private static List<String> adjacentAgents(String agentId, Set<String> agentIds, List<FlowEdge> edges) {
        List<String> out = new ArrayList<>();
        for (FlowEdge e : edges) {
            String other = null;
            if (agentId.equals(e.source()) && agentIds.contains(e.target())) other = e.target();
            else if (agentId.equals(e.target()) && agentIds.contains(e.source())) other = e.source();
            if (other != null && !out.contains(other)) out.add(other);
        }
        return out;
    }

    /**
     * Gives every agent a unique CLI name. Duplicates get a numeric suffix rather than being
     * rejected: two "Code Reviewer" nodes reviewing different agents is a reasonable thing to
     * draw, but they cannot share a definition file.
     */
    private static void assignCliNames(AgentSpec coordinator, List<AgentSpec> subAgents) {
        Set<String> taken = new HashSet<>();
        coordinator.cliName = uniqueCliName(coordinator.name, taken);
        for (AgentSpec s : subAgents) {
            s.cliName = uniqueCliName(s.name, taken);
        }
    }

    private static String uniqueCliName(String name, Set<String> taken) {
        String base = LocalClaudeExecutor.sanitize(name);
        if (taken.add(base)) return base;
        for (int i = 2; ; i++) {
            String candidate = base + "-" + i;
            if (taken.add(candidate)) return candidate;
        }
    }

    /** Gives each agent the agents it delegates to, from the hierarchy the walk derived. */
    private static void assignRosters(AgentSpec coordinator, List<AgentSpec> subAgents,
                                      FlowNode coordinatorNode, Delegation tree) {
        Map<String, AgentSpec> byNodeId = new LinkedHashMap<>();
        byNodeId.put(coordinatorNode.id(), coordinator);
        for (AgentSpec s : subAgents) byNodeId.put(s.nodeId, s);

        for (Map.Entry<String, String> entry : tree.delegatorOf().entrySet()) {
            AgentSpec delegator = byNodeId.get(entry.getValue());
            AgentSpec delegate = byNodeId.get(entry.getKey());
            if (delegator == null || delegate == null) continue;
            if (!delegator.delegatesTo.contains(delegate.cliName)) {
                delegator.delegatesTo.add(delegate.cliName);
            }
        }
    }

    /**
     * The node's data, with the definition fields read from the library when the block links one.
     *
     * <p>Resolved here, at compile time, and not when the block was linked: that is the whole
     * point of a link. The block stores a copy of the fields too — the canvas card and the
     * inspector show it without asking the server — but the run never reads that copy, so an edit
     * under Resources → Agents reaches every linked flow on its next run without anyone opening
     * the flows. Everything else on the node (tools, skills, plugins, folders, retries, facade,
     * escalation…) is the block's own: the library says what the agent IS, the flow says what it
     * gets to use here.
     *
     * <p>A linked agent that no longer exists is refused with the block and the id named. Falling
     * back to the block's stale copy would run a reviewer somebody deleted on purpose, silently.
     */
    private Map<String, Object> withLibraryFields(FlowNode node, Map<String, Object> d) {
        String id = str(d, "libraryAgentId", "");
        if (id.isBlank()) return d;
        LibraryAgent agent = library.find(id).orElseThrow(() -> new MissingLibraryAgent(
                "The block '" + str(d, "name", node.id()) + "' is linked to the library agent "
                        + id + ", which no longer exists. Open the block and unlink it (keeping "
                        + "a copy), or link it to another agent under Resources → Agents.",
                node.id()));
        Map<String, Object> merged = new LinkedHashMap<>(d);
        merged.put("name", agent.name());
        merged.put("model", agent.model());
        merged.put("effort", agent.effort());
        merged.put("maxTokens", agent.maxTokens());
        merged.put("systemPrompt", agent.systemPrompt());
        merged.put("description", agent.description());
        return merged;
    }

    private AgentSpec buildAgentSpec(FlowNode node, FlowGraph flow, Resources resources) {
        Map<String, Object> d = withLibraryFields(node, node.dataOrEmpty());
        AgentSpec s = new AgentSpec();
        s.mode = flow.modeOrDefault();
        s.nodeId = node.id();
        s.name = str(d, "name", node.id());
        s.tools = strList(d, "tools");
        for (String skillId : strList(d, "skillIds")) {
            AgentSpec.SkillSpec skill = new AgentSpec.SkillSpec();
            skill.type = "custom";
            skill.id = skillId;
            s.skills.add(skill);
        }
        s.plugins = strList(d, "plugins");
        s.description = str(d, "description", "");
        s.systemPrompt = str(d, "systemPrompt", "");
        // Only the coordinator's value is ever read, but it is carried for every agent so the
        // compiler doesn't need to know which node wins — validate() normalizes typos away.
        s.execution = str(d, "execution", "");
        s.facadeProfileId = str(d, "facadeProfileId", "");
        s.coordinatorAccess = str(d, "coordinatorAccess", "");
        s.contextFolders = strList(d, "contextFolders");
        s.claudeMdPath = str(d, "claudeMdPath", "");

        s.model = new ModelSpec();
        s.model.id = str(d, "model", "claude-opus-4-8");
        s.model.maxTokens = lng(d, "maxTokens", 16000);
        s.model.effort = str(d, "effort", "high");
        // Carried for every agent like `execution` above: only workers ever escalate, and the
        // compiler does not need to know which node is one.
        s.fallbackModelId = str(d, "fallbackModelId", "");
        // Blank on the node means "the deployment's default", which is what every flow saved
        // before the field existed says.
        s.retries = (int) lng(d, "retries", -1);
        // Coordinator-only like the execution mode, carried for every agent for the same reason.
        s.allowanceFallback = str(d, "allowanceFallback", "");
        s.allowanceFallbackModel = str(d, "allowanceFallbackModel", "");

        collectConnected(flow, node, resources.mcps(), s.mcpServers, mcp -> {
            Map<String, Object> md = mcp.dataOrEmpty();
            McpServerSpec spec = new McpServerSpec();
            spec.nodeId = mcp.id();
            spec.name = str(md, "name", mcp.id());
            spec.url = str(md, "url", "");
            spec.credentialId = str(md, "credentialId", null);
            spec.authHeader = str(md, "authHeader", null);
            // Which of the server's tools this agent gets. A large server would otherwise put
            // hundreds of JSON schemas in the prompt.
            spec.tools = strList(md, "tools");
            // The stdio transport: the node carries the command its README told the user to put
            // in mcp.json, and the run's config launches it.
            spec.command = str(md, "command", null);
            spec.args = strList(md, "args");
            spec.env = strMap(md, "env");
            return spec;
        });
        collectConnected(flow, node, resources.repos(), s.repositories, repo -> {
            Map<String, Object> rd = repo.dataOrEmpty();
            RepoSpec spec = new RepoSpec();
            spec.provider = str(rd, "provider", "github");
            spec.url = str(rd, "url", "");
            spec.credentialId = str(rd, "credentialId", null);
            spec.mountPath = str(rd, "mountPath", null);
            spec.branch = str(rd, "branch", null);
            // A node in group mode names an organization instead of a URL; which repositories that
            // means is resolved when the run starts, not here — the compiler stays offline.
            spec.group = str(rd, "group", null);
            spec.baseUrl = str(rd, "baseUrl", null);
            spec.only = strList(rd, "only");
            spec.includeArchived = bool(rd, "includeArchived", false);
            return spec;
        });
        collectConnected(flow, node, resources.sqls(), s.ragSources, sql -> {
            Map<String, Object> qd = sql.dataOrEmpty();
            SqlSourceSpec spec = new SqlSourceSpec();
            spec.nodeId = sql.id();
            spec.label = str(qd, "label", sql.id());
            spec.jdbcUrl = str(qd, "jdbcUrl", "");
            spec.username = str(qd, "username", null);
            spec.credentialId = str(qd, "credentialId", null);
            spec.query = str(qd, "query", "");
            spec.maxRows = (int) lng(qd, "maxRows", 50);
            return spec;
        });
        collectConnected(flow, node, resources.knowledges(), s.knowledgeSources, kb -> {
            Map<String, Object> kd = kb.dataOrEmpty();
            KnowledgeSourceSpec spec = new KnowledgeSourceSpec();
            spec.nodeId = kb.id();
            spec.baseId = str(kd, "baseId", "");
            spec.label = str(kd, "label", kb.id());
            spec.topK = (int) lng(kd, "topK", 5);
            return spec;
        });
        collectConnected(flow, node, resources.apis(), s.apiSources, api -> {
            Map<String, Object> ad = api.dataOrEmpty();
            AgentSpec.ApiSourceSpec spec = new AgentSpec.ApiSourceSpec();
            spec.nodeId = api.id();
            spec.label = str(ad, "label", api.id());
            spec.specUrl = str(ad, "specUrl", "");
            spec.specInline = str(ad, "specInline", "");
            spec.baseUrl = str(ad, "baseUrl", "");
            spec.credentialId = str(ad, "credentialId", "");
            spec.authHeader = str(ad, "authHeader", "");
            // Explicitly chosen operations only. An empty list exposes nothing — for the one node
            // type that reaches out and changes external systems, allow is opt-in per operation.
            spec.ops = strList(ad, "ops");
            // One endpoint typed by hand instead of a document to read. Blank mode means spec, so
            // every API node saved before this existed compiles exactly as it did.
            spec.mode = str(ad, "mode", "");
            spec.url = str(ad, "url", "");
            spec.method = str(ad, "method", "GET");
            spec.description = str(ad, "description", "");
            spec.sendsBody = bool(ad, "sendsBody", false);
            return spec;
        });
        // Direction matters here, and only here: a flow node FEEDING this agent runs before it and
        // becomes one of its capabilities, while one the agent feeds is a hand-off for afterwards.
        // Every other capability is still matched in either direction — see collectConnected.
        for (FlowNode sub : resources.flows()) {
            String legacy = legacyMode(sub);
            if (!legacy.isBlank()) {
                // A node saved as a tool stays a tool, wired either way — and stays one the agent
                // has to ASK for. Injecting it at the start instead would run somebody's flow, and
                // spend somebody's money, on a graph they drew to mean the opposite.
                boolean joined = flow.edgesOrEmpty().stream().anyMatch(e ->
                        (sub.id().equals(e.source()) && node.id().equals(e.target()))
                                || (node.id().equals(e.source()) && sub.id().equals(e.target())));
                if (joined && !"after".equalsIgnoreCase(legacy)) {
                    AgentSpec.SubflowSpec spec = subflowSpec(sub, flow);
                    spec.preRun = false;
                    s.subflows.add(spec);
                }
                continue;
            }
            boolean feedsThisAgent = flow.edgesOrEmpty().stream()
                    .anyMatch(e -> sub.id().equals(e.source()) && node.id().equals(e.target()));
            if (feedsThisAgent) s.subflows.add(subflowSpec(sub, flow));
        }

        s.validate();
        return s;
    }

    /**
     * Shared iteration/connection-check for the three resource-node loops in {@link
     * #buildAgentSpec}: for each candidate node wired to {@code node}, build a spec via {@code
     * specBuilder} and append it to {@code target}.
     */
    private static <T> void collectConnected(FlowGraph flow, FlowNode node, List<FlowNode> candidates,
                                              List<T> target, Function<FlowNode, T> specBuilder) {
        for (FlowNode candidate : candidates) {
            if (connected(flow.edgesOrEmpty(), node.id(), candidate.id())) {
                target.add(specBuilder.apply(candidate));
            }
        }
    }

    private static List<FlowNode> byType(FlowGraph flow, String type) {
        return flow.nodesOrEmpty().stream().filter(n -> type.equalsIgnoreCase(n.type())).toList();
    }

    private static boolean connected(List<FlowEdge> edges, String a, String b) {
        return edges.stream().anyMatch(e ->
                (a.equals(e.source()) && b.equals(e.target())) || (b.equals(e.source()) && a.equals(e.target())));
    }
}
