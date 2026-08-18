package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.llm.ChatTypes;
import com.concentus.llm.LlmException;
import com.concentus.llm.McpClient;
import com.concentus.llm.LocalModelCatalog;
import com.concentus.llm.LocalModelClient;
import com.concentus.llm.ToolSearchIndex;
import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs a compiled flow against a model you host yourself.
 *
 * <p>The other two backends lean on an Anthropic product for orchestration — Claude Code's
 * subagents, or Managed Agents' hosted loop. Neither exists here, so this one owns the loop:
 * delegation is expressed as an ordinary tool call, which is the one mechanism every model that
 * can be useful in a flow supports.
 *
 * <p>Each agent gets a {@code delegate_to_<name>} tool for the agents wired behind it, so the
 * delegation chains drawn on the canvas work exactly as they do on Claude — a reviewer behind an
 * engineer reviews that engineer's work, not the flow's work in general.
 *
 * <p>Agents also get file tools (confined to their context folders) and their MCP servers' tools,
 * both as ordinary function calls.
 *
 * <h2>What this backend cannot do</h2>
 * <b>Bash is deliberately absent</b>, and so, therefore, are repository nodes — cloning, committing
 * and opening a PR all run through git on a shell. Flows can be triggered by public webhooks, so
 * model-generated shell commands on the host is a remote-code-execution path. Claude Code carries
 * its own permission model and a trust boundary you accepted by installing it; none of that
 * transfers when this app spawns a process itself. Doing it safely needs an isolation boundary,
 * which is a design decision rather than a default. A flow that edits repositories belongs on the
 * Claude backend; one that reads mail, queries SQL and calls MCP tools runs fine here.
 *
 * <h2>Smaller models</h2>
 * A 14B model is not a frontier model, and the difference shows up as tool-calling discipline
 * rather than as prose quality: repeated calls, malformed arguments, or answering in text when it
 * should have called something. The turn cap is what stops that becoming an infinite loop, and
 * hitting it is reported rather than passed off as an answer.
 */
@Component
public class LocalModelAgentExecutor {

    /** Stops a coordinator that keeps delegating instead of answering. */
    private static final int MAX_TURNS_PER_AGENT = 12;
    /** Delegation is one level on the canvas but chains can nest; bound the recursion. */
    private static final int MAX_DELEGATION_DEPTH = 4;

    private final LocalModelClient client;
    private final LocalModelCatalog catalog;
    private final com.concentus.llm.McpOAuthStore mcpOAuth;
    private final com.concentus.auth.OrgContext orgContext;
    private final RagContextInjector ragInjector;
    private final ObjectMapper mapper;
    private final FileTools fileTools;
    private final ContextFolderResolver contextFolders;
    private final int maxTurns;
    /** Every tool is a JSON schema in the prompt; past a point they crowd out the conversation. */
    private final int maxTools;
    private final ToolSearchIndex toolSearch;
    /** Above this many tools, the agent searches instead of being handed every definition. */
    private final int toolSearchThreshold;

    public LocalModelAgentExecutor(LocalModelClient client, LocalModelCatalog catalog,
                                   com.concentus.llm.McpOAuthStore mcpOAuth,
                                   com.concentus.auth.OrgContext orgContext,
                                   RagContextInjector ragInjector,
                                   ObjectMapper mapper, FileTools fileTools,
                                   ContextFolderResolver contextFolders,
                                   @Value("${local-model.max-turns-per-agent:12}") int maxTurns,
                                   ToolSearchIndex toolSearch,
                                   @Value("${local-model.max-tools-per-agent:0}") int maxTools,
                                   @Value("${local-model.tool-search-threshold:25}") int toolSearchThreshold) {
        this.client = client;
        this.catalog = catalog;
        this.mcpOAuth = mcpOAuth;
        this.orgContext = orgContext;
        this.ragInjector = ragInjector;
        this.mapper = mapper;
        this.fileTools = fileTools;
        this.contextFolders = contextFolders;
        this.maxTurns = maxTurns > 0 ? maxTurns : MAX_TURNS_PER_AGENT;
        // 0 means no cap, which is the default: dropping tools silently is worse than a big
        // prompt, and the run now says exactly what it sent and what it cost.
        this.maxTools = Math.max(0, maxTools);
        this.toolSearch = toolSearch;
        this.toolSearchThreshold = toolSearchThreshold;
    }

    /** Runs one turn of the flow. Blocking — call on a worker thread. */
    public void runTurn(AgentRun run, CompiledFlow flow, String userText) {
        AgentSpec coordinator = flow.coordinator();
        if (!catalog.serves(coordinator.model.id)) {
            fail(run, unavailableMessage(coordinator.model.id));
            return;
        }

        if (!run.modelContextPrepared) {
            // Injected once, not per turn — re-running the query every turn would re-read the
            // database and re-append the same rows to the prompt.
            ragInjector.inject(coordinator, run, m -> run.emit(RunEvent.of("system", m)));
            for (AgentSpec sub : flow.subAgents()) {
                ragInjector.inject(sub, run, m -> run.emit(RunEvent.of("system", m)));
            }
            run.modelContextPrepared = true;
            run.emit(RunEvent.of("system", "Running on your own hardware — " + coordinator.model.id
                    + " at " + client.baseUrl() + ". Delegation, file tools, SQL context and MCP all"
                    + " work here. Bash and repository nodes do not: those need the Claude backend."));
            if (!run.checkouts.isEmpty()) {
                // Said out loud, because the canvas shows a repository node wired in and the run
                // would otherwise just quietly not touch it.
                run.emit(RunEvent.of("system", "This flow has repository nodes. They are not cloned "
                        + "on this backend — move the flow to Claude (local/cloud) if it has to open "
                        + "a pull request."));
            }
        }

        NodeExec exec = run.nodeExec(coordinator.nodeId, "agent", coordinator.name);
        if (exec != null) {
            exec.appendInput(userText);
            exec.status = "running";
        }
        run.status = "RUNNING";
        run.emit(RunEvent.of("system", "› " + userText));

        try {
            String answer = runAgent(run, flow, coordinator, userText, 0);
            if (exec != null && !"failed".equals(exec.status)) {
                exec.status = "passed";
                exec.endedAt = System.currentTimeMillis();
            }
            if (answer != null && !answer.isBlank()) {
                run.emit(RunEvent.of("status", "idle"));
            }
            if (!"TERMINATED".equals(run.status)) run.status = "IDLE";
        } catch (LlmException e) {
            markFailed(exec, e.getMessage());
            fail(run, e.getMessage());
        } catch (RuntimeException e) {
            markFailed(exec, e.getMessage());
            fail(run, "Run failed: " + e.getMessage());
        }
    }

    /**
     * Drives one agent to an answer, executing any delegations it asks for along the way.
     *
     * @return the agent's final text, which becomes the tool result for whoever delegated to it
     */
    private String runAgent(AgentRun run, CompiledFlow flow, AgentSpec spec, String task, int depth) {
        if (!catalog.serves(spec.model.id)) {
            // Thrown rather than returned: a sub-agent whose model isn't there cannot contribute,
            // and the delegator gets this as its tool result so it can say so.
            throw new LlmException(LocalModelClient.ID, unavailableMessage(spec.model.id));
        }

        // Delegation is capped by depth; file and MCP tools are not — an agent deep in a chain
        // still needs to do its own work.
        List<ChatTypes.ToolSpec> tools = new ArrayList<>();
        if (depth < MAX_DELEGATION_DEPTH) tools.addAll(delegationTools(flow, spec));
        List<Path> folders = contextFoldersFor(run, spec);
        tools.addAll(fileTools.toolsFor(folders));
        List<String> unavailable = new ArrayList<>();
        Map<String, Searchable> searchable = new LinkedHashMap<>();
        Map<String, McpClient> mcpByTool = mcpToolsFor(run, spec, tools, unavailable, searchable);

        List<ChatTypes.ChatMessage> messages = new ArrayList<>();
        if (!unavailable.isEmpty()) {
            // Told to the model, not just emitted to the console.
            //
            // Without this the agent simply has fewer tools and no idea why, so it answers the
            // question from whatever it already knows — which for a question about data in a
            // system it cannot reach means inventing the answer. That is the worst possible
            // failure mode: it looks like a confident reply rather than a broken connection.
            messages.add(ChatTypes.ChatMessage.user(
                    "SYSTEM NOTICE — read before answering.\n\n"
                    + "These tool servers failed to connect for this run, so their tools are NOT "
                    + "available to you:\n- " + String.join("\n- ", unavailable) + "\n\n"
                    + "If answering the request needs any of them, do NOT answer from your own "
                    + "knowledge and do NOT guess what the data might be. Say plainly which server "
                    + "is unavailable and that the request cannot be completed until it is fixed. "
                    + "Reporting a broken connection is the correct outcome here; a plausible "
                    + "invented answer is the harmful one."));
        }
        messages.add(ChatTypes.ChatMessage.user(task));

        String lastText = null;
        for (int turn = 0; turn < maxTurns; turn++) {
            // Checked between turns, because there is no process to kill: this is what makes Stop
            // actually stop. An in-flight generation finishes; nothing further is dispatched.
            if ("TERMINATED".equals(run.status)) return lastText;

            ChatTypes.ChatReply reply = client.chat(new ChatTypes.ChatRequest(
                    spec.model.id, spec.systemPrompt, messages, tools, spec.model.maxTokens));

            record(run, spec, reply);

            if (reply.text() != null && !reply.text().isBlank()) {
                lastText = reply.text();
                run.emit(RunEvent.of("agent_message", reply.text(), spec.name, spec.nodeId));
            }
            if (!reply.hasToolCalls()) {
                return lastText;
            }

            messages.add(ChatTypes.ChatMessage.assistant(reply.text(), reply.toolCalls()));
            for (ChatTypes.ToolCall call : reply.toolCalls()) {
                String result = executeTool(run, flow, spec, call, depth, folders, mcpByTool,
                        searchable);
                messages.add(ChatTypes.ChatMessage.toolResult(call.id(), result));
            }
        }
        // Hitting the cap is a real outcome, not a silent stop — say so rather than returning
        // whatever half-answer happened to be last.
        run.emit(RunEvent.of("system",
                spec.name + " stopped after " + maxTurns + " turns without finishing.",
                spec.name, spec.nodeId));
        return lastText;
    }

    /**
     * Routes one tool call to whichever kind of tool it is.
     *
     * <p>Every branch returns text rather than throwing: a tool the model got wrong should let it
     * correct itself on the next turn, not end the run.
     */
    private String executeTool(AgentRun run, CompiledFlow flow, AgentSpec spec,
                               ChatTypes.ToolCall call, int depth, List<Path> folders,
                               Map<String, McpClient> mcpByTool,
                               Map<String, Searchable> searchable) {
        Searchable search = searchable.get(call.name());
        if (search != null) {
            run.emit(RunEvent.of("tool_use", call.name() + " " + briefArgs(call.argumentsJson()),
                    spec.name, spec.nodeId));
            return runToolSearch(search, call.argumentsJson());
        }
        if (fileTools.isFileTool(call.name())) {
            if (folders.isEmpty()) {
                return "No context folders are configured for this agent, so file tools are unavailable.";
            }
            run.emit(RunEvent.of("tool_use", call.name(), spec.name, spec.nodeId));
            return fileTools.execute(call.name(), call.argumentsJson(), folders);
        }

        McpClient mcp = mcpByTool.get(call.name());
        if (mcp != null) {
            run.emit(RunEvent.of("tool_use", mcp.serverName() + " · " + call.name(),
                    spec.name, spec.nodeId));
            try {
                return mcp.callTool(call.name(), call.argumentsJson());
            } catch (LlmException e) {
                return "That tool failed: " + e.getMessage();
            }
        }

        return executeDelegation(run, flow, spec, call, depth);
    }

    /**
     * The folders this agent may read and write, filtered through the same allowlist the local
     * backend uses — a model cannot widen its own workspace by asking.
     */
    private List<Path> contextFoldersFor(AgentRun run, AgentSpec spec) {
        return contextFolders.resolve(spec.contextFolders,
                (path, reason) -> run.emit(RunEvent.of("system",
                        "Context folder ignored — " + path + ": " + reason, spec.name, spec.nodeId)));
    }

    /**
     * Connects to this agent's MCP servers and appends their tools, returning a map from tool
     * name back to the server that owns it.
     *
     * <p>Clients are cached per run: the handshake is per session, so reconnecting every turn
     * would pay it repeatedly and churn server-side sessions. A server that can't be reached is
     * reported and skipped — one bad server shouldn't cost the agent its other tools.
     *
     * @param unavailable filled with a description per server that failed, so the caller can put
     *                    it in front of the model. Reporting it only to the console leaves the
     *                    agent with fewer tools and no idea why, and it then answers from memory
     */
    private Map<String, McpClient> mcpToolsFor(AgentRun run, AgentSpec spec,
                                               List<ChatTypes.ToolSpec> tools,
                                               List<String> unavailable,
                                               Map<String, Searchable> searchable) {
        Map<String, McpClient> byTool = new LinkedHashMap<>();
        // Whether we had anything to authenticate with, per server. A 401 means two very
        // different things depending on this, and saying the same thing for both sends people
        // to re-do a sign-in they already completed.
        Map<String, Boolean> authenticated = new LinkedHashMap<>();
        for (AgentSpec.McpServerSpec server : spec.mcpServers) {
            if (server.isStdio()) {
                // A stdio server is a process the claude CLI launches; this executor talks MCP
                // over HTTP only. Said explicitly — a tool that silently isn't there reads as the
                // model ignoring instructions.
                unavailable.add(server.name + " (stdio servers need the Claude backend)");
                continue;
            }
            if (server.url == null || server.url.isBlank()) continue;
            try {
                // The grant first, the node's static token second — see McpAuthSources, which is
                // also what the facade and the tool picker use, so all three see one server the
                // same way and a rejected token is renewed rather than merely reported.
                McpClient.TokenSource auth = McpAuthSources.forServer(server, mcpOAuth,
                        orgContext.defaultOrganizationId());
                authenticated.put(server.name, auth.current() != null);
                McpClient client = run.mcpClients.computeIfAbsent(server.name,
                        k -> new McpClient(server.name, server.url, auth, mapper));
                List<ChatTypes.ToolSpec> offered = client.listTools();
                List<ChatTypes.ToolSpec> selected = selectTools(offered, server.tools);

                // Every tool stays callable regardless of what is advertised. That is what makes
                // search work: the model finds a tool it was never shown a definition for, and
                // calls it by name.
                for (ChatTypes.ToolSpec tool : offered) byTool.put(tool.name(), client);

                if (useToolSearch(selected)) {
                    // One tool instead of hundreds of schemas. The agent asks for what it needs and
                    // gets back the few definitions that match, so the prompt carries a search box
                    // rather than a catalogue.
                    searchable.put(searchToolName(server.name), new Searchable(server, selected));
                    tools.add(searchTool(server.name, selected.size()));
                    boolean vectors = toolSearch.ensureIndexed(server.url, selected);
                    run.emit(RunEvent.of("system", "MCP '" + server.name + "': " + selected.size()
                            + " tools behind " + searchToolName(server.name) + " ("
                            + (vectors ? "semantic" : "word-overlap") + " ranking) — the agent "
                            + "searches instead of being handed every definition.",
                            spec.name, spec.nodeId));
                    continue;
                }

                // A cap only exists if someone asked for one. Silently dropping tools is how an
                // agent ends up unable to do its job with nothing on screen to explain it, so the
                // default is to send everything and say plainly what that costs.
                boolean cappedHere = maxTools > 0 && selected.size() > maxTools;
                List<ChatTypes.ToolSpec> included = cappedHere ? selected.subList(0, maxTools) : selected;

                List<ChatTypes.ToolSpec> compressed = new ArrayList<>();
                for (ChatTypes.ToolSpec tool : included) {
                    ChatTypes.ToolSpec small = compress(tool);
                    compressed.add(small);
                    tools.add(small);
                }
                reportTools(run, spec, server.name, offered, included, compressed, cappedHere);
            } catch (RuntimeException e) {
                run.mcpClients.remove(server.name);
                String why = describeMcpFailure(server.name, e.getMessage(),
                        Boolean.TRUE.equals(authenticated.get(server.name)));
                unavailable.add(why);
                run.emit(RunEvent.of("system", "MCP server '" + server.name + "' unavailable: " + why,
                        spec.name, spec.nodeId));
            }
        }
        return byTool;
    }

    /**
     * Roughly how much prompt a set of tool definitions costs.
     *
     * <p>Four characters per token is the usual English approximation, and it does not need to be
     * better than that: this exists to be compared against the model server's context length, where
     * the useful distinction is 2,000 against 40,000 rather than any exact figure. Without it the
     * one number that decides whether a run works is invisible.
     */
    static int approxTokens(List<ChatTypes.ToolSpec> tools) {
        int chars = 0;
        for (ChatTypes.ToolSpec t : tools) {
            chars += t.name().length();
            chars += t.description() == null ? 0 : t.description().length();
            chars += t.parameters() == null ? 0 : t.parameters().toString().length();
        }
        return chars / 4;
    }

    /** Beyond this a tool description is prose, not an instruction; the model reads the first line. */
    private static final int MAX_DESCRIPTION_CHARS = 220;
    /** An enum longer than this is data, and listing it teaches the model nothing it will use. */
    private static final int MAX_ENUM_VALUES = 12;

    /**
     * Trims a tool definition down to what a model actually needs to call it.
     *
     * <p>Free, and it compounds with the allowlist. Real schemas carry paragraphs of description
     * and enums with hundreds of members — a currency list, every country code — and all of it is
     * prompt. The name, the parameter names and their types are what decide whether the model
     * calls the tool correctly; the third paragraph of prose does not.
     *
     * <p>Only ever removes. A model that needed a value from a truncated enum will send something
     * outside it and get the server's own error back, which is recoverable; a context that
     * overflowed is not.
     */
    static ChatTypes.ToolSpec compress(ChatTypes.ToolSpec tool) {
        String description = tool.description() == null ? "" : tool.description().strip();
        if (description.length() > MAX_DESCRIPTION_CHARS) {
            description = description.substring(0, MAX_DESCRIPTION_CHARS).strip() + "…";
        }
        JsonNode schema = tool.parameters();
        if (schema != null && schema.isObject()) {
            schema = schema.deepCopy();
            trim((ObjectNode) schema, 0);
        }
        return new ChatTypes.ToolSpec(tool.name(), description, schema);
    }

    /** Walks a JSON Schema, shortening descriptions and capping enums. Depth-bounded. */
    private static void trim(ObjectNode node, int depth) {
        if (depth > 6) return;
        JsonNode description = node.get("description");
        if (description != null && description.isTextual()
                && description.asText().length() > MAX_DESCRIPTION_CHARS) {
            node.put("description",
                    description.asText().substring(0, MAX_DESCRIPTION_CHARS).strip() + "…");
        }
        JsonNode enumValues = node.get("enum");
        if (enumValues != null && enumValues.isArray() && enumValues.size() > MAX_ENUM_VALUES) {
            com.fasterxml.jackson.databind.node.ArrayNode capped = node.putArray("enum");
            for (int i = 0; i < MAX_ENUM_VALUES; i++) capped.add(enumValues.get(i));
            // Said in the schema rather than dropped silently, so the model knows the list goes on.
            node.put("description", (node.path("description").asText("") + " (one of "
                    + enumValues.size() + " values; first " + MAX_ENUM_VALUES + " shown)").strip());
        }
        // Examples are for humans reading docs; they are pure prompt weight here.
        node.remove("examples");
        node.remove("$comment");

        for (String container : new String[]{"properties", "definitions", "$defs"}) {
            JsonNode child = node.get(container);
            if (child instanceof ObjectNode obj) {
                obj.fields().forEachRemaining(e -> {
                    if (e.getValue() instanceof ObjectNode o) trim(o, depth + 1);
                });
            }
        }
        if (node.get("items") instanceof ObjectNode items) trim(items, depth + 1);
    }

    /**
     * The subset of a server's tools an agent is given.
     *
     * <p>Substring matching rather than exact names: someone narrowing Holded's 338 tools down to
     * contacts should be able to type {@code contact}, not transcribe eleven names. An empty
     * filter means everything, which is right for the small servers and wrong for the large ones —
     * hence the cap that follows it.
     */
    static List<ChatTypes.ToolSpec> selectTools(List<ChatTypes.ToolSpec> offered, List<String> filter) {
        if (filter == null || filter.isEmpty()) return offered;
        List<String> wanted = new ArrayList<>();
        for (String f : filter) {
            if (f != null && !f.isBlank()) wanted.add(f.trim().toLowerCase(java.util.Locale.ROOT));
        }
        if (wanted.isEmpty()) return offered;

        List<ChatTypes.ToolSpec> out = new ArrayList<>();
        for (ChatTypes.ToolSpec tool : offered) {
            String name = tool.name().toLowerCase(java.util.Locale.ROOT);
            for (String w : wanted) {
                if (name.contains(w)) {
                    out.add(tool);
                    break;
                }
            }
        }
        return out;
    }

    /** A server whose tools are reachable through search rather than advertised individually. */
    private record Searchable(AgentSpec.McpServerSpec server, List<ChatTypes.ToolSpec> tools) {
    }

    /** One search tool per server, since two servers may both have a {@code list_invoices}. */
    static String searchToolName(String serverName) {
        return "search_" + serverName.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]", "_") + "_tools";
    }

    /**
     * Whether this server is big enough to be worth searching instead of listing.
     *
     * <p>A threshold rather than a switch: for eight tools, search is pure overhead — a whole extra
     * round trip before the agent can do anything. For three hundred it is the only thing that
     * fits. An explicit selection on the node always wins, because someone who picked eight tools
     * meant those eight.
     */
    private boolean useToolSearch(List<ChatTypes.ToolSpec> selected) {
        return toolSearchThreshold > 0 && selected.size() > toolSearchThreshold;
    }

    /** The one tool the agent sees in place of the server's catalogue. */
    private ChatTypes.ToolSpec searchTool(String serverName, int count) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("query").put("type", "string")
                .put("description", "What you are trying to do, in your own words — e.g. "
                        + "\"find a customer by tax id\" or \"create a draft quote\".");
        props.putObject("limit").put("type", "integer")
                .put("description", "How many tools to return. Default 5.");
        schema.putArray("required").add("query");
        schema.put("additionalProperties", false);

        return new ChatTypes.ToolSpec(searchToolName(serverName),
                "Searches the " + count + " tools available on '" + serverName + "' and returns the "
                + "ones that match, with their full parameters. Call this FIRST whenever you need "
                + "to do anything with " + serverName + ", then call the tool it returns by name. "
                + "The tools are not listed here because there are too many; they are all callable "
                + "once you have found them.", schema);
    }

    /**
     * Runs a tool search and renders the matches for the model.
     *
     * <p>The full schema goes back, not a summary: the next thing the model does is call one of
     * these by name, and it can only get the arguments right if it has seen them. This is the
     * whole trade — one round trip in exchange for not carrying every definition in every prompt.
     */
    private String runToolSearch(Searchable target, String argumentsJson) {
        String query;
        int limit = 5;
        try {
            JsonNode args = mapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
            query = args.path("query").asText("");
            if (args.hasNonNull("limit")) limit = args.path("limit").asInt(5);
        } catch (Exception e) {
            query = argumentsJson == null ? "" : argumentsJson;
        }
        if (query.isBlank()) {
            return "Give me something to search for — describe what you are trying to do.";
        }

        ToolSearchIndex.Results results =
                toolSearch.search(target.server().url, target.tools(), query, limit);
        if (results.hits().isEmpty()) {
            return "No tool on '" + target.server().name + "' matches \"" + query + "\". Try "
                    + "different words, or say that this cannot be done here — do not invent an "
                    + "answer from your own knowledge.";
        }

        StringBuilder out = new StringBuilder();
        if (results.note() != null) out.append("(").append(results.note()).append(")\n\n");
        out.append("Matching tools. Call one of these by name:\n\n");
        for (ToolSearchIndex.Hit hit : results.hits()) {
            out.append("## ").append(hit.name()).append('\n');
            if (hit.description() != null && !hit.description().isBlank()) {
                out.append(hit.description().strip()).append('\n');
            }
            out.append("Parameters: ").append(hit.schemaJson()).append("\n\n");
        }
        return out.toString().strip();
    }

    /** A tool call's arguments, short enough for a console line. */
    private static String briefArgs(String argumentsJson) {
        if (argumentsJson == null) return "";
        String s = argumentsJson.strip();
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }

    /**
     * Says exactly which of a server's tools the agent got, which it did not, and why.
     *
     * <p>Written because a count answers the wrong question. "338 of 338" does not tell you whether
     * {@code list_contacts} is among them, and when a model reports not having a tool that is the
     * only thing worth knowing — the alternative is guessing at a model that seems to be lying.
     *
     * <p>Nothing is dropped by default. The one thing that <em>is</em> silent and does real damage
     * is the model server truncating an oversized prompt, so the token estimate is stated next to
     * the context length it has to fit inside.
     */
    private void reportTools(AgentRun run, AgentSpec spec, String serverName,
                             List<ChatTypes.ToolSpec> offered, List<ChatTypes.ToolSpec> included,
                             List<ChatTypes.ToolSpec> compressed, boolean capped) {
        int tokens = approxTokens(compressed);
        run.emit(RunEvent.of("system", "MCP '" + serverName + "': " + included.size() + " of "
                + offered.size() + " tools sent, ~" + tokens + " tokens of prompt.",
                spec.name, spec.nodeId));

        run.emit(RunEvent.of("system", "Sent: " + names(compressed), spec.name, spec.nodeId));

        // Excluded, with the reason attached to each group rather than left to be inferred.
        List<ChatTypes.ToolSpec> excluded = new ArrayList<>(offered);
        excluded.removeAll(included);
        if (!excluded.isEmpty()) {
            String why = capped
                    ? "over the cap of " + maxTools + " (local-model.max-tools-per-agent); the cut "
                      + "follows the server's own order, so it is arbitrary"
                    : "not ticked in the tool picker on this node";
            run.emit(RunEvent.of("system", "Not sent — " + why + ": " + names(excluded),
                    spec.name, spec.nodeId));
        }

        // The number that actually decides whether any of this works. Ollama serves 2048 by
        // default whatever the model supports, and truncates past it without saying so — which is
        // what makes a model report having only the last few tools in the list.
        if (tokens > 1500) {
            run.emit(RunEvent.of("system", "These definitions alone are ~" + tokens + " tokens. "
                    + "Your model server must hold that plus the system prompt and the "
                    + "conversation, or it will truncate them silently — Ollama defaults to 2048 "
                    + "regardless of the model, so start it with OLLAMA_CONTEXT_LENGTH=32768. "
                    + "Fewer tools is the other half of the fix.", spec.name, spec.nodeId));
        }
    }

    /** Tool names, all of them — an elided list defeats the point of printing it. */
    private static String names(List<ChatTypes.ToolSpec> tools) {
        return tools.stream().map(ChatTypes.ToolSpec::name).collect(Collectors.joining(", "));
    }

    /**
     * Turns an MCP connection failure into something that names the fix.
     *
     * <p>A 401 is the one worth spelling out. This backend authenticates with a bearer token from
     * the node's credential, and many MCP servers instead expect an OAuth authorization that only
     * the {@code claude} CLI holds — so the same server works on the Claude backend and returns
     * 401 here. That looks like a bug in the flow rather than a difference in how the two connect.
     */
    private static String describeMcpFailure(String serverName, String message, boolean hadCredential) {
        String detail = message == null ? "" : message;
        if (detail.contains("401") || detail.toLowerCase().contains("unauthorized")) {
            if (!hadCredential) {
                return "'" + serverName + "' rejected the connection as unauthorized (401), and "
                        + "nothing was sent to authenticate: the node has no token and this "
                        + "deployment holds no OAuth grant for that URL. Press \"Sign in to this "
                        + "server\" on the MCP node, or select a token. (" + detail + ")";
            }
            return "'" + serverName + "' rejected the credential we sent (401). The stored "
                    + "authorization exists but the server refused it — it may have been revoked, "
                    + "or issued for a different account. Re-authorize the server from the MCP "
                    + "node. (" + detail + ")";
        }
        return "'" + serverName + "' could not be reached: " + detail;
    }

    /** Runs the delegate named by a tool call and returns its answer as the tool result. */
    private String executeDelegation(AgentRun run, CompiledFlow flow, AgentSpec delegator,
                                     ChatTypes.ToolCall call, int depth) {
        AgentSpec target = findByToolName(flow, delegator, call.name());
        if (target == null) {
            // Reported back to the model rather than thrown: it can pick a valid agent instead of
            // the whole run dying on one bad name.
            return "No agent named '" + call.name() + "' is available to you.";
        }

        String task = extractTask(call.argumentsJson());
        NodeExec targetExec = run.nodeExec(target.nodeId, "agent", target.name);
        if (targetExec != null) {
            targetExec.status = "running";
            targetExec.appendInput(task);
        }
        run.emit(RunEvent.of("tool_use", "Delegate → " + target.name,
                delegator.name, delegator.nodeId));

        try {
            String answer = runAgent(run, flow, target, task, depth + 1);
            if (targetExec != null) {
                if (answer != null) targetExec.appendOutput(answer);
                if (!"failed".equals(targetExec.status)) {
                    targetExec.status = "passed";
                    targetExec.endedAt = System.currentTimeMillis();
                }
            }
            return answer == null ? "(no answer)" : answer;
        } catch (LlmException e) {
            markFailed(targetExec, e.getMessage());
            run.emit(RunEvent.of("error", target.name + " failed: " + e.getMessage(),
                    target.name, target.nodeId));
            // Handed back as a tool result so the delegator can react rather than the run dying.
            return "That agent failed: " + e.getMessage();
        }
    }

    /** One tool per agent wired behind this one, so its roster is exactly what it may call. */
    private List<ChatTypes.ToolSpec> delegationTools(CompiledFlow flow, AgentSpec spec) {
        List<ChatTypes.ToolSpec> tools = new ArrayList<>();
        for (String cliName : spec.delegatesTo) {
            AgentSpec target = byCliName(flow, cliName);
            if (target == null) continue;
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = schema.putObject("properties");
            props.putObject("task").put("type", "string")
                    .put("description", "The work for this agent, with everything it needs to know.");
            schema.putArray("required").add("task");
            schema.put("additionalProperties", false);

            String description = target.description == null || target.description.isBlank()
                    ? "Delegate work to " + target.name + "."
                    : target.description;
            tools.add(new ChatTypes.ToolSpec(toolNameFor(cliName), description, schema));
        }
        return tools;
    }

    /** Tool names are constrained to a safe charset by most providers. */
    static String toolNameFor(String cliName) {
        return "delegate_to_" + cliName.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private AgentSpec findByToolName(CompiledFlow flow, AgentSpec delegator, String toolName) {
        for (String cliName : delegator.delegatesTo) {
            if (toolNameFor(cliName).equals(toolName)) return byCliName(flow, cliName);
        }
        return null;
    }

    private static AgentSpec byCliName(CompiledFlow flow, String cliName) {
        if (cliName == null) return null;
        if (cliName.equals(flow.coordinator().cliName)) return flow.coordinator();
        for (AgentSpec s : flow.subAgents()) {
            if (cliName.equals(s.cliName)) return s;
        }
        return null;
    }

    /** Pulls the `task` argument, falling back to the raw JSON so nothing is silently dropped. */
    private String extractTask(String argumentsJson) {
        try {
            JsonNode node = mapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
            String task = node.path("task").asText("");
            return task.isBlank() ? node.toString() : task;
        } catch (Exception e) {
            return argumentsJson == null ? "" : argumentsJson;
        }
    }

    /** Attributes usage to the agent that spent it, matching the other backends. */
    private void record(AgentRun run, AgentSpec spec, ChatTypes.ChatReply reply) {
        NodeExec exec = run.nodeExec(spec.nodeId, "agent", spec.name);
        ChatTypes.TokenUsage usage = reply.usage() == null ? ChatTypes.TokenUsage.NONE : reply.usage();
        if (exec != null) {
            exec.inputTokens += usage.inputTokens();
            exec.outputTokens += usage.outputTokens();
            exec.cacheReadTokens += usage.cacheReadTokens();
            exec.cacheWriteTokens += usage.cacheWriteTokens();
            // OpenAI-shaped prompt_tokens already span the whole prompt — snapshot, not sum.
            long prompt = usage.inputTokens() + usage.cacheReadTokens() + usage.cacheWriteTokens();
            LocalStreamEventHandler.applyContext(exec, prompt, prompt + usage.outputTokens());
            if (reply.text() != null && !reply.text().isBlank()) exec.appendOutput(reply.text());
        }
        run.totalInputTokens += usage.inputTokens();
        run.totalOutputTokens += usage.outputTokens();
        run.cacheReadTokens += usage.cacheReadTokens();
        run.cacheWriteTokens += usage.cacheWriteTokens();
    }

    /**
     * Why a model can't run here, said in terms of what to do about it.
     *
     * <p>The three causes look identical from the outside — no server, wrong id, not pulled — and
     * "model unavailable" sends someone hunting through the wrong one.
     */
    private String unavailableMessage(String model) {
        if (!client.isConfigured()) {
            return "No local model server is configured, so '" + model + "' cannot run. Set "
                    + "LOCAL_MODEL_BASE_URL (e.g. http://localhost:11434/v1), or pick a Claude "
                    + "model to run this flow on the Claude backend instead.";
        }
        java.util.Set<String> served = catalog.models();
        if (served.isEmpty()) {
            return "The local model server at " + client.baseUrl() + " is not answering: "
                    + catalog.lastError();
        }
        return "The server at " + client.baseUrl() + " does not serve '" + model + "'. It has: "
                + String.join(", ", served) + ". Ids include the tag, so `qwen3` and `qwen3:14b` "
                + "are different — pull it with `ollama pull " + model + "` if it should be there.";
    }

    private static void markFailed(NodeExec exec, String error) {
        if (exec == null) return;
        exec.status = "failed";
        exec.error = error;
        exec.endedAt = System.currentTimeMillis();
    }

    private static void fail(AgentRun run, String message) {
        run.status = "ERROR";
        run.error = message;
        run.emit(RunEvent.of("error", message));
    }
}
