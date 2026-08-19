package com.concentus.model;

import java.util.List;

/**
 * What an independent worker is allowed to reach through its MCP facade.
 *
 * <p>A worker process never talks to a flow's MCP servers directly: every call goes through this
 * backend, which applies the profile. That is the difference between steering and enforcement —
 * the single-session path can only write "these tools are yours" into an agent's instructions;
 * a facade profile is checked on every {@code tools/list} and every {@code tools/call}.
 *
 * @param id          stable id (assigned on first save)
 * @param name        display name ("reader", "holded-invoicing", ...)
 * @param description what this profile is for, shown in pickers
 * @param tools       case-insensitive substrings selecting which tools are exposed; empty = all
 *                    the server offers (the node's own tool filter still applies first)
 * @param readOnly    when true, write-shaped tools are not exposed and not callable at all
 * @param dryRun      when true, write-shaped tools are exposed but a call is simulated — the
 *                    facade answers "DRY RUN" and executes nothing. {@code null} means true:
 *                    writes stay behind the guard until someone deliberately clears it.
 */
public record FacadeProfile(String id, String name, String description,
                            List<String> tools, boolean readOnly, Boolean dryRun,
                            List<String> readAlso) {

    /** The shape before a profile could name its own reads, kept so existing callers still build. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public FacadeProfile(String id, String name, String description,
                         List<String> tools, boolean readOnly, Boolean dryRun) {
        this(id, name, description, tools, readOnly, dryRun, List.of());
    }

    public List<String> toolsOrEmpty() {
        return tools == null ? List.of() : tools;
    }

    /**
     * Tool names this profile declares to be reads, whatever their name suggests.
     *
     * <p>Read/write is guessed from the verb a tool's name starts with, and the guess errs toward
     * "write" because the other direction executes something. That is right by default and wrong
     * for a whole category of real tools: {@code run_gaql_query} on Google Ads and
     * {@code run_report} on Analytics are the two most useful reads either server has, and a
     * read-only profile hides both. Somebody who knows their server can say so here instead of
     * abandoning read-only entirely, which is what the alternative amounts to.
     *
     * <p>Matched the way every other filter in this class matches: case-insensitive substrings.
     */
    public List<String> readAlsoOrEmpty() {
        return readAlso == null ? List.of() : readAlso;
    }

    /** Absent means on. The one setting whose safe direction is the default one. */
    public boolean dryRunEnabled() {
        return dryRun == null || dryRun;
    }

    /**
     * Whether this profile withholds anything at all — an allowlist, read-only, or simulated
     * writes.
     *
     * <p>On the record rather than at either call site, because two of them have to agree: the
     * executor decides whether a worker may launch its local servers by this answer, and the
     * doctor predicts that decision before the run. A profile that withheld nothing while the
     * doctor said it did would be a warning about a run that behaves fine.
     */
    public boolean withholdsAnything() {
        return readOnly || dryRunEnabled() || !toolsOrEmpty().isEmpty();
    }
}
