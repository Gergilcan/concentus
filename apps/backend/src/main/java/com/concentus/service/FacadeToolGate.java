package com.concentus.service;

import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.llm.ChatTypes;
import com.concentus.model.FacadeProfile;

import java.util.List;
import java.util.Locale;

/**
 * The decision logic of a worker's MCP facade: which of a server's tools a profile exposes, and
 * what happens when one is called. Pure functions, separated from the HTTP endpoint so the rules
 * that decide whether a write executes are the most-tested code in the feature, not the least.
 *
 * <h2>What counts as a write</h2>
 * By name, and failing closed: a tool is read-shaped only when its name starts with a verb from
 * a known-safe list ({@code get_}, {@code list_}, {@code search_}, ...). Everything else —
 * {@code create_*}, {@code send_*}, but also anything simply unrecognized — is treated as a
 * write. Misclassifying a read as a write costs one blocked call and an honest message;
 * misclassifying a write as a read executes it, which is the direction this must never fail in.
 *
 * <p>MCP's {@code readOnlyHint} annotation would let well-behaved servers state this themselves;
 * the client does not surface annotations yet, so the heuristic stands alone for now — another
 * reason it errs toward "write".
 */
public final class FacadeToolGate {

    private FacadeToolGate() {
    }

    /**
     * Verbs that only ever fetch. Prefixes, not substrings: {@code get_} anywhere would match
     * {@code forget_customer}, and a prefix list is auditable at a glance.
     */
    private static final List<String> READ_PREFIXES = List.of(
            "get", "list", "search", "find", "read", "fetch", "query", "preview",
            "download", "describe", "show", "count", "check", "status", "history", "ping");

    /** Whether a tool's name says it only reads. Anything unrecognized is a write — fail closed. */
    public static boolean isReadTool(String name) {
        if (name == null || name.isBlank()) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String prefix : READ_PREFIXES) {
            if (n.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * The tools a worker sees on {@code tools/list}: the node's own filter first (what this flow
     * wired), then the profile's allowlist (what this worker's role needs), then — under
     * {@code readOnly} — only the read-shaped survivors. Under {@code dryRun} writes stay
     * visible: the worker is supposed to plan with them and get a simulation back.
     */
    public static List<ChatTypes.ToolSpec> visible(List<ChatTypes.ToolSpec> offered,
                                                   McpServerSpec server, FacadeProfile profile) {
        List<ChatTypes.ToolSpec> afterNode =
                LocalModelAgentExecutor.selectTools(offered, server.tools);
        List<ChatTypes.ToolSpec> afterProfile =
                LocalModelAgentExecutor.selectTools(afterNode, profile.toolsOrEmpty());
        if (!profile.readOnly()) return afterProfile;
        return afterProfile.stream().filter(t -> isRead(t.name(), profile)).toList();
    }

    /**
     * Whether this profile treats the tool as a read: the name says so, or the profile does.
     *
     * <p>The name check errs toward "write" on purpose, and that is right until it meets a server
     * whose reads are called {@code run_gaql_query} or {@code run_report}. Declaring those beats
     * the alternative people actually take, which is to give up on read-only for that worker.
     */
    static boolean isRead(String name, FacadeProfile profile) {
        if (isReadTool(name)) return true;
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        for (String declared : profile.readAlsoOrEmpty()) {
            if (declared != null && !declared.isBlank()
                    && n.contains(declared.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a tool is inside this worker's allowlists at all (node filter ∩ profile filter),
     * before the read/write gate is even asked. Checked on {@code tools/call}, not just list:
     * an MCP server's tools are all callable by name whether or not they were advertised, and
     * the facade must hold either way.
     */
    public static boolean allowlisted(String toolName, McpServerSpec server, FacadeProfile profile) {
        List<ChatTypes.ToolSpec> one = List.of(new ChatTypes.ToolSpec(toolName, "", null));
        return !LocalModelAgentExecutor.selectTools(
                LocalModelAgentExecutor.selectTools(one, server.tools),
                profile.toolsOrEmpty()).isEmpty();
    }

    /**
     * What this worker can reach on one server, in one sentence, for the run's transcript.
     *
     * <p>The enforcement was already here and already silent: a worker saw a shorter list and
     * nobody watching the run could tell whether that was a profile doing its job or a server
     * with nothing on it. Both numbers say it — "4 of 31" is the narrowing, and the rest of the
     * line names what happens to a write — so a reviewer reads the proof instead of taking the
     * feature's word for it.
     *
     * @param offered how many tools the server itself advertised, before any filter
     * @param visible how many survived the node's filter, the profile's allowlist and read-only
     */
    public static String reachLine(String worker, String server, FacadeProfile profile,
                                   int offered, int visible) {
        String writes = profile.readOnly() ? "writes are blocked"
                : profile.dryRunEnabled() ? "writes are simulated" : "writes execute";
        return "Facade: " + worker + " reaches " + visible + " of " + offered + " tools on '"
                + server + "' — profile '" + profile.name() + "', " + writes + ".";
    }

    /** What a call to this tool does under this profile. */
    public enum CallDecision { EXECUTE, DRY_RUN, BLOCK }

    public static CallDecision decide(String toolName, FacadeProfile profile) {
        // The same question the listing asked, so a tool a worker can SEE under read-only is a
        // tool it can call. Advertising one and blocking it is a bug report waiting to happen.
        if (isRead(toolName, profile)) return CallDecision.EXECUTE;
        if (profile.readOnly()) return CallDecision.BLOCK;
        return profile.dryRunEnabled() ? CallDecision.DRY_RUN : CallDecision.EXECUTE;
    }
}
