package com.concentus.model;

/**
 * One thing that would go wrong at run time, found before the run.
 *
 * <p>Every finding names its own fix, because a check that only says "MCP server unauthorised" has
 * moved the problem rather than solved it. {@code where} points at the node it is about, so the UI
 * can say which box on the canvas to open.
 *
 * @param level   "error" (this will fail) or "warn" (this may fail, or will work oddly)
 * @param area    what part of the flow it concerns — "graph", "credential", "mcp", "plugin",
 *                "trigger", "budget", "runtime", "cli", "variables"
 * @param message what is wrong, in the user's terms
 * @param fix     what to do about it
 * @param where   node id this is about, or null when it concerns the whole flow
 */
public record DoctorFinding(String level, String area, String message, String fix, String where) {

    public static DoctorFinding error(String area, String message, String fix, String where) {
        return new DoctorFinding("error", area, message, fix, where);
    }

    public static DoctorFinding warn(String area, String message, String fix, String where) {
        return new DoctorFinding("warn", area, message, fix, where);
    }
}
