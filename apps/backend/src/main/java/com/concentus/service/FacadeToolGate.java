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
        return afterProfile.stream().filter(t -> isReadTool(t.name())).toList();
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

    /** What a call to this tool does under this profile. */
    public enum CallDecision { EXECUTE, DRY_RUN, BLOCK }

    public static CallDecision decide(String toolName, FacadeProfile profile) {
        if (isReadTool(toolName)) return CallDecision.EXECUTE;
        if (profile.readOnly()) return CallDecision.BLOCK;
        return profile.dryRunEnabled() ? CallDecision.DRY_RUN : CallDecision.EXECUTE;
    }
}
