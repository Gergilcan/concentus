package com.concentus.mcp;

import com.concentus.model.DoctorFinding;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowMemoryView;
import com.concentus.model.FlowNode;
import com.concentus.model.FlowVersionInfo;
import com.concentus.model.TriggerSpec;
import com.concentus.service.FlowCompiler;
import com.concentus.service.FlowDoctor;
import com.concentus.store.FlowMemoryStore;
import com.concentus.web.FlowController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Designing flows: the schema to design against, storage, surgical edits, and the two checks that
 * say whether a graph would actually run.
 *
 * <p>Writes go through {@link FlowController} rather than straight to the store, and that is the
 * load-bearing decision in this class. Saving a flow is not "write a row" — it snapshots a
 * restorable revision, reschedules cron and mail triggers, and may replay the golden reference.
 * A second front door that reimplemented those would work on the day it was written and drift
 * afterwards: a flow saved over MCP would quietly leave its history un-snapshotted, or its cron
 * trigger pointing at the previous graph. Calling the controller means the MCP cannot behave
 * differently from the canvas, because it is the same code path.
 */
// Fixes this group's place in tools/list — designing comes first: flow_schema is what the rest depends on.
@Order(1)
@Component
public class FlowStudioTools implements StudioToolset {

    private static final Logger log = LoggerFactory.getLogger(FlowStudioTools.class);

    /** The example shipped with {@code flow_schema} — a real bundled flow, so it cannot drift. */
    private static final String EXAMPLE_RESOURCE = "library-flows/docs-from-code.json";
    /** Ceiling on notes returned by {@code read_flow_memory}. */
    private static final int MEMORY_MAX = 200;

    private final FlowController flows;
    private final FlowCompiler compiler;
    private final FlowDoctor doctor;
    private final ObjectMapper mapper;

    public FlowStudioTools(FlowController flows, FlowCompiler compiler, FlowDoctor doctor,
                           ObjectMapper mapper) {
        this.flows = flows;
        this.compiler = compiler;
        this.doctor = doctor;
        this.mapper = mapper;
    }

    @Override
    public List<StudioTool> tools() {
        return List.of(
                new StudioTool("flow_schema",
                        "The Concentus flow format: every node type, the fields its data holds, how "
                                + "edges work, and the rules a graph must satisfy to run. Call this "
                                + "FIRST, before writing or editing any flow — the format is specific "
                                + "to this product and cannot be guessed from the name of a field.",
                        Schema.of(mapper).build(),
                        args -> StudioTool.Result.ok(schema())),

                new StudioTool("list_flows",
                        "Every saved flow: id, name, folder, trigger and size. Start here to find the "
                                + "id of a flow the user is talking about by name.",
                        Schema.of(mapper)
                                .str("folder", "Only flows in this dashboard folder. Omit for all of them.")
                                .str("tag", "Only flows carrying this tag. Omit for all of them.")
                                .build(),
                        this::listFlows),

                new StudioTool("get_flow",
                        "One saved flow as the exact JSON graph the app stores. This is what you edit: "
                                + "read it, change it, send it back to update_flow.",
                        Schema.of(mapper)
                                .requiredStr("flowId", "Id of the flow, from list_flows.")
                                .build(),
                        this::getFlow),

                new StudioTool("create_flow",
                        "Saves a NEW flow and returns it with the id it was given. The graph is compiled "
                                + "first and refused if it would not run, so a rejection is a fixable "
                                + "message rather than a broken flow on someone's dashboard.",
                        Schema.of(mapper)
                                .object("flow", "The whole flow graph: name, nodes, edges. Any id "
                                        + "you put here is ignored — a new one is assigned. See flow_schema.", true)
                                .build(),
                        this::createFlow),

                new StudioTool("update_flow",
                        "Replaces a saved flow with the graph you give. Send the WHOLE graph, not a "
                                + "fragment: anything you leave out is removed. To change one node's "
                                + "settings, prefer update_flow_node. Every update snapshots a restorable "
                                + "revision and reschedules the flow's triggers.",
                        Schema.of(mapper)
                                .requiredStr("flowId", "Id of the flow to overwrite.")
                                .object("flow", "The complete replacement graph.", true)
                                .build(),
                        this::updateFlow),

                new StudioTool("update_flow_node",
                        "Changes one node's data in a saved flow, leaving everything else exactly as it "
                                + "is. The safe way to edit a system prompt, a model, a cron expression "
                                + "or a tool list — no risk of dropping the rest of the graph in a "
                                + "rewrite.",
                        Schema.of(mapper)
                                .requiredStr("flowId", "Id of the flow.")
                                .requiredStr("nodeId", "Id of the node inside that flow (from get_flow).")
                                .object("data", "Fields to set on the node's data. Merged over what is "
                                        + "there unless replace is true.", true)
                                .bool("replace", "True to replace the node's data outright instead of "
                                        + "merging. Default false.")
                                .build(),
                        this::updateFlowNode),

                new StudioTool("delete_flow",
                        "Permanently deletes a saved flow. Its executions and revision history go with "
                                + "it. Ask the user before calling this.",
                        Schema.of(mapper)
                                .requiredStr("flowId", "Id of the flow to delete.")
                                .bool("confirm", "Must be true. A guard against deleting a flow while "
                                        + "reaching for something else.")
                                .build(),
                        this::deleteFlow),

                new StudioTool("validate_flow",
                        "Checks a flow WITHOUT running it: compiles the graph, then reports everything "
                                + "that would fail at run time — missing credentials, an MCP server that "
                                + "needs a login, an unresolved {{VARIABLE}}, a cron expression that does "
                                + "not parse. Call it on a draft before saving, and after any edit.",
                        Schema.of(mapper)
                                .str("flowId", "Id of a saved flow to check. Give this OR flow.")
                                .object("flow", "An unsaved draft graph to check instead. Give this OR flowId.", false)
                                .build(),
                        this::validateFlow),

                new StudioTool("list_flow_versions",
                        "Revision history of a flow, newest first — every save is a restorable revision.",
                        Schema.of(mapper)
                                .requiredStr("flowId", "Id of the flow.")
                                .build(),
                        this::listVersions),

                new StudioTool("get_flow_version",
                        "One earlier revision of a flow, as JSON. Reading it changes nothing.",
                        Schema.of(mapper)
                                .requiredStr("flowId", "Id of the flow.")
                                .number("version", "Revision number, from list_flow_versions.")
                                .build(),
                        this::getVersion),

                new StudioTool("restore_flow_version",
                        "Makes an earlier revision the current flow. The revision being replaced is kept "
                                + "in history, so this is reversible.",
                        Schema.of(mapper)
                                .requiredStr("flowId", "Id of the flow.")
                                .number("version", "Revision number to restore.")
                                .build(),
                        this::restoreVersion),

                new StudioTool("read_flow_memory",
                        "The notes this flow's agents have left for their future runs. Useful context "
                                + "before editing a flow: it says what its runs have actually learned.",
                        Schema.of(mapper)
                                .requiredStr("flowId", "Id of the flow.")
                                .number("limit", "How many notes, newest first. Default 50.")
                                .build(),
                        this::readMemory),

                new StudioTool("clear_flow_memory",
                        "Wipes a flow's memory. Irreversible, and the notes are the only record of what "
                                + "earlier runs learned — ask the user first.",
                        Schema.of(mapper)
                                .requiredStr("flowId", "Id of the flow.")
                                .bool("confirm", "Must be true.")
                                .build(),
                        this::clearMemory));
    }

    // ------------------------------------------------------------------ schema

    /**
     * What a model needs to write a valid flow, in one answer.
     *
     * <p>Prose plus one real flow, rather than a JSON Schema. A JSON Schema would say that
     * {@code data} is an object and stop there — the fields that matter are per node type, and the
     * rules that decide whether a graph runs (exactly one coordinator, delegation walked outwards
     * from it) are relationships between nodes that no property schema expresses. The example is
     * read from the classpath for the same reason {@code FlowGenerator} does it: a shipped flow
     * cannot drift from the format, and a hand-written one in a string literal always does.
     */
    private String schema() {
        StringBuilder out = new StringBuilder("""
                CONCENTUS FLOW FORMAT

                A flow is a multi-agent orchestration graph. It is stored as one JSON object:

                  id        assigned on first save — never invent one
                  name      short human name
                  nodes     [{id, type, role, data}]
                  edges     [{id, source, target}] joining node ids
                  enabled   false pauses cron/mail triggers without deleting them
                  tags      free-form labels; folder: dashboard folder (one level, blank = root)
                  variables {"NAME": "value"} — usable as {{NAME}} inside any prompt in this flow
                  budgetUsd monthly ceiling in USD; at or past it, new runs are refused

                NODE TYPES, and what their data holds

                  input     How the flow starts. Exactly one per flow.
                            mode: "manual"  a person types the first message
                                  "prompt"  data.prompt is sent automatically on Run
                                  "cron"    as prompt, and also runs on data.cron (5-field expression)
                                  "webhook" an external POST starts a run; data.secret is the secret
                                            the PROVIDER issued, data.authParam names the header it
                                            arrives in (default "Linear-Signature")
                                  "subflow" this flow only runs when another flow calls it
                                  "mail"    a run per matching IMAP message (mailHost, mailPort,
                                            mailSsl, mailUsername, mailCredentialId, mailAuthMode,
                                            mailFolder, and conditions mailFrom, mailSubjectContains,
                                            mailBodyContains, mailUnseenOnly, mailFlaggedOnly,
                                            mailWithAttachmentsOnly)
                            shadow: true makes triggered runs plan and stop, so you can watch what a
                                    trigger WOULD do before trusting it.

                  agent     role "coordinator" (exactly one — the agent that answers) or "subagent".
                            data: name, model, systemPrompt (the agent's whole briefing — write a
                            real one), description (for a subagent: WHEN to delegate to it; this is
                            what its delegator routes on), maxTokens, effort,
                            contextFolders [host folders it may read], claudeMdPath,
                            tools [allowlist, e.g. ["Read","Grep","Glob"] to make a reviewer
                                   read-only — the one permission enforced per agent],
                            skillIds, plugins.
                            libraryAgentId (+ libraryVersion) links the block to a library agent
                            (list_resources kind=agents): name, model, systemPrompt, description,
                            maxTokens and effort are then read from the library at every run, so
                            editing the library agent reaches every flow that links it.
                            Coordinator only: permissionMode ("default" | "acceptEdits" |
                            "bypassPermissions" | "plan" — bypassPermissions is the only one that
                            works unattended) and execution ("" for Claude Code subagents in one
                            session, "fanout" for one independent process per sub-agent).

                  mcp       An external tool server. data: name, and EITHER url (+ credentialId,
                            authHeader) for a remote one, OR command/args/env for a local stdio one.
                            tools: [substrings] narrows which of its tools the agent gets.

                  repo      A git repository the agents work in. data: provider ("github"|"gitlab"),
                            url, credentialId, branch, mountPath; or group + only[] to stand for
                            every repository in an organization.

                  sql       A query whose rows are injected into the connected agent's context.
                            data: label, jdbcUrl, username, credentialId, query, maxRows.

                  knowledge Passages from a knowledge base, injected at run start.
                            data: label, baseId (from list_resources kind=knowledge), topK.

                  api       An OpenAPI spec turned into typed tools. data: label, specUrl or
                            specInline, baseUrl, credentialId, authHeader, and ops: ["GET /pets"] —
                            allowing operations is explicit, an empty ops grants nothing.

                  flow      Another saved flow, run from this one. data: label, flowId, mode, and
                            waitForResult.
                            mode "tool"  connect it to an agent: the agent gets a run_flow tool
                                          and calls it when it decides to.
                            mode "after" leave it unconnected: it fires when this run COMPLETES,
                                          with the run's final answer as its input.
                            waitForResult (tool mode only) true = the agent waits for the child's
                            answer, false = it starts it and moves on. The child runs with its OWN
                            budget and permission mode; loops and chains deeper than 3 are refused.

                  merge     Fan-out only. Reconciles every worker's output into the final answer.
                            data: name, model, systemPrompt, maxTokens, effort.

                  verifier  Fan-out only. Judges each worker's output before the merge; a rejected
                            output is withheld. data: as merge.

                  note      A sticky note for the people reading the canvas. data: text, color
                            ("yellow" | "blue" | "green" | "pink"). Never compiled, never run,
                            never wired.

                  group     A labelled frame around related nodes, purely visual. data: label,
                            color (as note), _size {"w", "h"}; a node inside the frame carries
                            data._parent = the frame's id, and its _pos stays absolute. Never
                            compiled, never run, never wired.

                EDGES

                  Connect a capability node (mcp, repo, sql, knowledge, api) to an agent to grant it.
                  Connect agents to each other to delegate: the hierarchy is walked OUTWARDS from the
                  coordinator, so edge direction does not matter — whichever agent reaches another
                  first becomes its delegator. An agent not reachable from the coordinator is left
                  out of the run entirely.

                RULES A GRAPH MUST SATISFY

                  - exactly one input node, and exactly one agent with role "coordinator"
                  - every edge's source and target must be ids that exist
                  - the input node connects to the coordinator
                  - at most one merge node and at most one verifier node
                  - write real system prompts, in the language the user is writing in

                CANVAS POSITION

                  data._pos = {"x": 40, "y": 200} places the node in the editor. Set it on every
                  node, spread out (~300 apart on x, ~180 on y) — nodes without one land on top of
                  each other and read as a single block.

                EXAMPLE — a real flow shipped with the app:
                """);
        String example = example();
        if (example != null) out.append('\n').append(example).append('\n');
        out.append("\nAlways call validate_flow before create_flow or update_flow.\n");
        return out.toString();
    }

    private String example() {
        try {
            return new String(new ClassPathResource(EXAMPLE_RESOURCE).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            // The rules above still describe the format, and every write is validated anyway — a
            // missing example costs quality, not correctness.
            log.debug("Schema example {} unavailable: {}", EXAMPLE_RESOURCE, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------- flows

    private StudioTool.Result listFlows(JsonNode args) {
        String folder = Args.str(args, "folder", null);
        String tag = Args.str(args, "tag", null);
        List<FlowGraph> matching = flows.list().stream()
                .filter(f -> folder == null || folder.equalsIgnoreCase(
                        f.folder() == null ? "" : f.folder()))
                .filter(f -> tag == null || f.tagsOrEmpty().stream().anyMatch(tag::equalsIgnoreCase))
                .toList();
        boolean filtered = folder != null || tag != null;
        return StudioTool.Result.ok(Answers.lines("flow(s)",
                filtered
                        ? "No saved flow matches that filter. Call list_flows with no arguments to "
                                + "see all of them."
                        : "There are no saved flows yet. create_flow makes the first one.",
                matching, this::describe));
    }

    /** One line per flow: enough to pick the right one, cheap enough to list fifty. */
    private String describe(FlowGraph flow) {
        TriggerSpec trigger = TriggerSpec.from(flow);
        StringBuilder line = new StringBuilder(flow.id() + "  " + flow.name());
        line.append("  [trigger ")
                .append(trigger.mode() == null || trigger.mode().isBlank() ? "manual" : trigger.mode())
                .append(", ").append(flow.nodesOrEmpty().size()).append(" nodes, ")
                .append(flow.edgesOrEmpty().size()).append(" edges");
        if (!flow.enabledOrDefault()) line.append(", PAUSED");
        line.append(']');
        if (flow.folder() != null && !flow.folder().isBlank()) line.append("  folder: ").append(flow.folder());
        if (!flow.tagsOrEmpty().isEmpty()) line.append("  tags: ").append(String.join(", ", flow.tagsOrEmpty()));
        return line.toString();
    }

    private StudioTool.Result getFlow(JsonNode args) {
        return StudioTool.Result.ok(Answers.json(mapper, flows.get(Args.require(args, "flowId"))));
    }

    private StudioTool.Result createFlow(JsonNode args) {
        FlowGraph draft = Args.flow(mapper, Args.object(args, "flow"));
        // An id in a "create" is either a copy-paste leftover or a model reusing the example's —
        // both of which would silently overwrite an existing flow. Dropping it is the only reading
        // that cannot destroy something.
        FlowGraph saved = flows.save(draft.withId(null));
        return StudioTool.Result.ok("Created. " + Answers.json(mapper, saved));
    }

    private StudioTool.Result updateFlow(JsonNode args) {
        String flowId = Args.require(args, "flowId");
        flows.get(flowId);  // 404s here rather than creating a flow under an id that never existed
        FlowGraph saved = flows.save(Args.flow(mapper, Args.object(args, "flow")).withId(flowId));
        return StudioTool.Result.ok("Updated. " + Answers.json(mapper, saved));
    }

    /**
     * One node's data, changed in place.
     *
     * <p>Merging by default is the whole point of this tool. A model asked to change a model id
     * will happily send back the node's data with the six fields it did not think about missing,
     * and a replace-always tool would take that literally — dropping an agent's tool allowlist
     * because the edit was about something else. {@code replace} is there for the rarer case where
     * removing a field IS the edit.
     */
    private StudioTool.Result updateFlowNode(JsonNode args) {
        String flowId = Args.require(args, "flowId");
        String nodeId = Args.require(args, "nodeId");
        Map<String, Object> patch = readData(Args.object(args, "data"));
        boolean replace = Args.bool(args, "replace", false);

        FlowGraph flow = flows.get(flowId);
        List<FlowNode> updated = new ArrayList<>();
        boolean found = false;
        for (FlowNode node : flow.nodesOrEmpty()) {
            if (!node.id().equals(nodeId)) {
                updated.add(node);
                continue;
            }
            found = true;
            Map<String, Object> data;
            if (replace) {
                data = new LinkedHashMap<>(patch);
            } else {
                data = new LinkedHashMap<>(node.dataOrEmpty());
                data.putAll(patch);
            }
            // role lives on the node, not in data, but a model editing "the coordinator" reaches
            // for the word it can see — so accept it there and put it where it belongs.
            Object role = data.remove("role");
            updated.add(new FlowNode(node.id(), node.type(),
                    role == null ? node.role() : String.valueOf(role), data));
        }
        if (!found) {
            return StudioTool.Result.failed("Flow '" + flowId + "' has no node '" + nodeId
                    + "'. Its node ids are: " + flow.nodesOrEmpty().stream()
                    .map(FlowNode::id).toList());
        }
        FlowGraph saved = flows.save(withNodes(flow, updated));
        return StudioTool.Result.ok("Node '" + nodeId + "' updated. " + Answers.json(mapper, saved));
    }

    private StudioTool.Result deleteFlow(JsonNode args) {
        String flowId = Args.require(args, "flowId");
        if (!Args.bool(args, "confirm", false)) {
            return StudioTool.Result.failed("Not deleted: pass confirm=true to delete flow '"
                    + flowId + "'. Check with the user first — this also removes its executions "
                    + "and its revision history.");
        }
        FlowGraph flow = flows.get(flowId);
        flows.delete(flowId);
        return StudioTool.Result.ok("Deleted flow '" + flow.name() + "' (" + flowId + ").");
    }

    // -------------------------------------------------------------- validation

    /**
     * Compile, then diagnose — in that order, and reporting both.
     *
     * <p>Compiling answers "is this a graph the runner can turn into agents", which is fatal and
     * comes with a message naming what is wrong. The doctor answers "would this actually work on
     * this machine", which is mostly not fatal. A tool that stopped at the first compile error
     * would hand back one problem per round trip; running both means one call tells the model
     * everything it has to fix.
     */
    private StudioTool.Result validateFlow(JsonNode args) {
        String flowId = Args.str(args, "flowId", null);
        JsonNode inline = args.path("flow");
        if (flowId == null && !inline.isObject()) {
            throw new IllegalArgumentException("Give either 'flowId' (a saved flow) or 'flow' (a draft graph).");
        }
        FlowGraph flow = flowId != null ? flows.get(flowId) : Args.flow(mapper, inline);

        StringBuilder out = new StringBuilder();
        boolean fatal = false;
        try {
            compiler.compile(flow);
            out.append("Compiles: the graph is runnable.\n");
        } catch (RuntimeException e) {
            fatal = true;
            out.append("WILL NOT RUN — the graph does not compile:\n  ").append(e.getMessage())
                    .append("\n");
        }

        List<DoctorFinding> findings;
        try {
            findings = doctor.check(flow);
        } catch (RuntimeException e) {
            // The doctor probes the host (the CLI, runtimes, credentials). A probe that blows up
            // must not swallow the compile result the caller actually came for.
            out.append("\nThe pre-run checks could not complete: ").append(e.getMessage());
            return new StudioTool.Result(fatal, out.toString());
        }

        if (findings.isEmpty()) {
            out.append("\nPre-run checks: nothing to report.");
        } else {
            out.append("\n").append(findings.size()).append(" pre-run finding(s):");
            for (DoctorFinding f : findings) {
                out.append("\n\n[").append(f.level().toUpperCase()).append("] ")
                        .append(f.area());
                if (f.where() != null && !f.where().isBlank()) out.append(" (").append(f.where()).append(")");
                out.append("\n  ").append(f.message()).append("\n  Fix: ").append(f.fix());
            }
            out.append("\n\nErrors will fail the run; warnings may work oddly. Neither blocks Run.");
        }
        return new StudioTool.Result(fatal, out.toString());
    }

    // ---------------------------------------------------------------- versions

    private StudioTool.Result listVersions(JsonNode args) {
        String flowId = Args.require(args, "flowId");
        List<FlowVersionInfo> history = flows.versions(flowId);
        return StudioTool.Result.ok(Answers.lines("revision(s), newest first",
                "This flow has no revision history yet.", history,
                v -> "v" + v.version() + "  " + Answers.when(v.createdAt())
                        + "  " + v.name()
                        + (v.author() == null ? "" : "  by " + v.author())
                        + "  [" + v.nodeCount() + " nodes " + signed(v.nodesDelta())
                        + ", " + v.edgeCount() + " edges " + signed(v.edgesDelta())
                        + (v.renamed() ? ", renamed" : "") + "]"));
    }

    private static String signed(int delta) {
        return delta == 0 ? "±0" : (delta > 0 ? "+" + delta : String.valueOf(delta));
    }

    private StudioTool.Result getVersion(JsonNode args) {
        return StudioTool.Result.ok(Answers.json(mapper,
                flows.version(Args.require(args, "flowId"), version(args))));
    }

    private StudioTool.Result restoreVersion(JsonNode args) {
        String flowId = Args.require(args, "flowId");
        int version = version(args);
        FlowGraph restored = flows.restore(flowId, version);
        return StudioTool.Result.ok("Restored revision v" + version + " as the current flow. "
                + Answers.json(mapper, restored));
    }

    private static int version(JsonNode args) {
        int version = args.path("version").asInt(0);
        if (version <= 0) {
            throw new IllegalArgumentException(
                    "'version' is required — a revision number from list_flow_versions.");
        }
        return version;
    }

    // ------------------------------------------------------------------ memory

    private StudioTool.Result readMemory(JsonNode args) {
        String flowId = Args.require(args, "flowId");
        int limit = Args.count(args, "limit", 50, MEMORY_MAX);
        FlowMemoryView view = flows.memory(flowId);
        if (!view.available()) {
            return StudioTool.Result.failed("This flow's memory cannot be read right now: the "
                    + "database is unreachable. The notes may still exist — do not report the "
                    + "memory as empty.");
        }
        List<FlowMemoryStore.Note> notes = view.notes().stream().limit(limit).toList();
        String heading = "note(s), newest first"
                + (view.count() > notes.size() ? " (of " + view.count() + ")" : "");
        return StudioTool.Result.ok(Answers.lines(heading,
                "This flow's memory is empty — no run has left a note yet.", notes,
                n -> "[" + Answers.when(n.createdAt()) + "] " + n.note()));
    }

    private StudioTool.Result clearMemory(JsonNode args) {
        String flowId = Args.require(args, "flowId");
        if (!Args.bool(args, "confirm", false)) {
            return StudioTool.Result.failed("Not cleared: pass confirm=true. These notes are the "
                    + "only record of what earlier runs learned, and there is no undo.");
        }
        flows.clearMemory(flowId);
        return StudioTool.Result.ok("Memory cleared for flow '" + flowId + "'.");
    }

    // ------------------------------------------------------------------ shared

    private Map<String, Object> readData(JsonNode node) {
        try {
            return mapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("'data' could not be read as an object: " + e.getMessage());
        }
    }

    /** The flow with a new node list. Canonical constructor — see {@code FlowGraph.withId}. */
    private static FlowGraph withNodes(FlowGraph flow, List<FlowNode> nodes) {
        return new FlowGraph(flow.id(), flow.name(), nodes, flow.edges(),
                flow.enabled(), flow.tags(), flow.favorite(), flow.notifyWebhook(), flow.budgetUsd(),
                flow.approvalSlackCredentialId(), flow.approvalSlackChannel(),
                flow.approvalTeamsWebhook(), flow.variables(), flow.folder(), flow.goldenAutoRun());
    }
}
