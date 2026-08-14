package com.concentus.model;

/**
 * What one MCP command needs to run, and whether this machine has it.
 *
 * <p>{@code runtime} is null when the command's first word is not one this app knows how to
 * install — a bare binary the user put on their PATH, say. That is reported as "unknown", never as
 * "missing": claiming a command is broken because its launcher is unfamiliar would be a lie, and
 * the run would have worked.
 *
 * @param command  the command as configured, echoed back so a stale answer is recognisable
 * @param runtime  the runtime it needs, or null when it needs nothing this app manages
 * @param satisfied true when nothing is missing — including the unknown-runtime case
 */
public record RuntimeCheck(String command, RuntimeStatus runtime, boolean satisfied) {

    /** A command whose launcher this app does not manage: nothing to check, nothing to install. */
    public static RuntimeCheck unmanaged(String command) {
        return new RuntimeCheck(command, null, true);
    }
}
