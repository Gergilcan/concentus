package com.concentus.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * The two records the account system is built from.
 *
 * <p>An <b>organization</b> is the isolation boundary: every row the integrations write carries an
 * {@code organization_id}, and every query filters on it. A <b>user</b> belongs to exactly one
 * organization, which is what makes "verify ownership on every endpoint" a single lookup rather
 * than a per-controller convention.
 */
public final class Accounts {

    private Accounts() {
    }

    /** Roles, ordered least to most privileged. */
    public static final String ROLE_MEMBER = "MEMBER";
    public static final String ROLE_ADMIN = "ADMIN";

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
     * One sign-in identity.
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
