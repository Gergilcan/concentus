package com.concentus.policy;

import java.util.List;

/**
 * The order the CLI's permission modes stand in, from "may do nothing" to "may do anything", so a
 * ceiling has a meaning: a mode is above it when it lets an agent act with fewer questions asked.
 *
 * <p>The order is the CLI's own semantics, written down once. {@code plan} proposes and changes
 * nothing; {@code default} asks before every sensitive action; {@code acceptEdits} accepts file
 * edits and still asks for the rest; {@code bypassPermissions} asks nothing. A ceiling of
 * {@code acceptEdits} therefore lets a flow plan or ask, and stops it bypassing.
 *
 * <p>Modes this list does not know rank lowest, which is the safe direction: an unrecognised
 * value never counts as more permissive than a real one.
 */
public final class PermissionCeiling {

    /** Least to most permissive. */
    public static final List<String> ORDER = List.of("plan", "default", "acceptEdits", "bypassPermissions");

    private PermissionCeiling() { }

    /** A mode's place in {@link #ORDER}; -1 for a blank or unknown one. */
    public static int rank(String mode) {
        return mode == null ? -1 : ORDER.indexOf(mode.trim());
    }

    /** Whether {@code mode} lets an agent do more than {@code ceiling} allows. */
    public static boolean above(String mode, String ceiling) {
        int limit = rank(ceiling);
        return limit >= 0 && rank(mode) > limit;
    }

    /** {@code mode}, or the ceiling when the mode is above it. A blank ceiling clamps nothing. */
    public static String clamp(String mode, String ceiling) {
        return above(mode, ceiling) ? ceiling.trim() : mode;
    }

    /** Whether a value names a mode the CLI has — what the panel and the store accept. */
    public static boolean known(String mode) {
        return rank(mode) >= 0;
    }
}
