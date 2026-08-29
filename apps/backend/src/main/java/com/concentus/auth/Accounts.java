package com.concentus.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * The two records the account system is built from.
 *
 * <p>An <b>organization</b> is the isolation boundary: every row the stores write carries an
 * {@code organization_id}, and every query filters on it. A <b>user</b> holds a
 * {@link Membership} in one organization or several, each with its own role, and is working in
 * exactly one of them at a time — the <em>current</em> organization, which the account row names.
 * That is what keeps "verify ownership on every endpoint" a single lookup rather than a
 * per-controller convention: the principal carries one organization, and the switch is the only
 * thing that changes it.
 */
public final class Accounts {

    private Accounts() {
    }

    /**
     * Roles, ordered least to most privileged.
     *
     * <p>The two at the bottom were added under the two that existed rather than beside them, so
     * nobody's account changed meaning the day they appeared: every existing MEMBER still edits and
     * every ADMIN still administers. What is new is that an account can now be given LESS.
     *
     * <ul>
     *   <li>{@code VIEWER} — reads. Flows, runs, transcripts, costs. Changes nothing and starts
     *       nothing, which is the right shape for the person who wants to know what the automation
     *       did last night without being able to make it do anything tonight.</li>
     *   <li>{@code OPERATOR} — the above, plus running flows and answering them: start, stop,
     *       approve, retry, re-run a block. Cannot change what a flow IS. This is the split that
     *       matters in an organization — the person who runs the daily cycle is rarely the person
     *       who should be free to rewrite what it does to the ad account.</li>
     *   <li>{@code MEMBER} — edits flows, agents, servers, credentials, everything the work is
     *       made of.</li>
     *   <li>{@code ADMIN} — the above, plus the organization itself: who is in it, and as what.</li>
     * </ul>
     */
    public static final String ROLE_VIEWER = "VIEWER";
    public static final String ROLE_OPERATOR = "OPERATOR";
    public static final String ROLE_MEMBER = "MEMBER";
    public static final String ROLE_ADMIN = "ADMIN";

    /** Least privileged first. The order IS the ladder — {@link #rank} reads it. */
    public static final java.util.List<String> ROLES =
            java.util.List.of(ROLE_VIEWER, ROLE_OPERATOR, ROLE_MEMBER, ROLE_ADMIN);

    /**
     * Where a role sits on the ladder; -1 for a name nothing recognises.
     *
     * <p>Unknown means -1 rather than "treat it as the lowest", so a typo in configuration cannot
     * quietly hand someone a working account with fewer powers than intended — it hands them one
     * that fails the first check and gets fixed.
     */
    public static int rank(String role) {
        if (role == null) return -1;
        return ROLES.indexOf(role.trim().toUpperCase(java.util.Locale.ROOT));
    }

    /** Whether {@code role} is at least {@code minimum} on the ladder. */
    public static boolean atLeast(String role, String minimum) {
        int have = rank(role);
        return have >= 0 && have >= rank(minimum);
    }

    /** The role to store for a requested name, or null when it is not one we have. */
    public static String normalizeRole(String requested) {
        int index = rank(requested);
        return index < 0 ? null : ROLES.get(index);
    }

    /**
     * The role an admin asked for when adding somebody, MEMBER when they asked for none.
     *
     * <p>An unrecognised name is refused rather than quietly downgraded: an admin who typed
     * "editor" meaning MEMBER should be told, not handed an account that cannot do the job and
     * a puzzle to solve next week.
     */
    public static String roleOrMember(String requested) {
        if (requested == null || requested.isBlank()) return ROLE_MEMBER;
        String role = normalizeRole(requested);
        if (role == null) {
            throw new IllegalArgumentException("Unknown role '" + requested + "'. Use one of: "
                    + String.join(", ", ROLES) + ".");
        }
        return role;
    }

    /**
     * Minimum password length, applied everywhere a password is set.
     *
     * <p>Shared rather than duplicated per call site, because an account created under a looser
     * rule than the one that governs changing it is a trap: the owner can sign in but cannot
     * re-set the password to anything similar, with no explanation of why.
     */
    public static final int MIN_PASSWORD_LENGTH = 12;

    /**
     * @throws IllegalArgumentException with a message naming the actual requirement, since this
     *         is read by an operator editing configuration as often as by a user filling a form
     */
    public static void requireStrongPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
    }

    /**
     * One tenant.
     *
     * @param id        stable id, also the value stored in every {@code organization_id} column
     * @param name      display name
     * @param createdAt epoch millis
     */
    public record Organization(String id, String name, long createdAt) {
    }

    /**
     * One account's place in one organization.
     *
     * <p>The role lives here, not on the account: the same person may administer the organization
     * they set up and only read the one they were invited into. {@link UserAccount#role} is a
     * copy of this for the account's current organization.
     */
    public record Membership(String userId, String organizationId, String role, long createdAt) {
    }

    /**
     * One sign-in identity.
     *
     * <p>{@code organizationId} and {@code role} describe the organization the account is
     * currently working in; the memberships table holds every other one. Switching organization
     * rewrites both, so everything that reads the principal keeps reading one organization.
     *
     * <p>{@code passwordHash} is a BCrypt digest and is marked {@link JsonIgnore} so a user record
     * can be returned from a controller without the hash ever reaching the client.
     */
    public record UserAccount(String id, String organizationId, String email,
                              @JsonIgnore String passwordHash, String role, boolean enabled,
                              long createdAt) {

        public boolean isAdmin() {
            return ROLE_ADMIN.equalsIgnoreCase(role);
        }

        /** A copy with the hash blanked, for anything that leaves the backend. */
        public UserAccount redacted() {
            return new UserAccount(id, organizationId, email, null, role, enabled, createdAt);
        }
    }
}
