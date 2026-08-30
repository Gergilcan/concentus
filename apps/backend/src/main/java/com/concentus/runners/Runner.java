package com.concentus.runners;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One registered runner: a machine somebody operates that executes runs for this backend.
 *
 * <p>The row and nothing live: whether it is connected, what it said about itself and how busy it
 * is are {@link RunnerRegistry}'s to answer, and {@link RunnerView} is where the two meet for the
 * screen. {@code tokenHash} is {@link JsonIgnore}d — the hash is not the secret, but a list of
 * hashes is still nobody's business.
 *
 * @param scope     {@link #SCOPE_ORGANIZATION}, {@link #SCOPE_GROUP} or {@link #SCOPE_USER}
 * @param groupId   the group whose members may use it, when the scope is a group
 * @param userId    the one account that may use it, when the scope is a user
 * @param createdBy the email of whoever registered it, for the audit line
 * @param lastSeenAt epoch millis of the last heartbeat, minute resolution; null before the first
 * @param revokedAt epoch millis, null while the token still works
 */
public record Runner(String id, String organizationId, String name, String scope, String groupId,
                     String userId, @JsonIgnore String tokenHash, String createdBy, long createdAt,
                     Long lastSeenAt, Long revokedAt) {

    public static final String ID_PREFIX = "rn_";

    public static final String SCOPE_ORGANIZATION = "organization";
    public static final String SCOPE_GROUP = "group";
    public static final String SCOPE_USER = "user";

    /** A valid scope, lower-cased, or null. */
    public static String normalizeScope(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (s) {
            case SCOPE_ORGANIZATION, SCOPE_GROUP, SCOPE_USER -> s;
            default -> null;
        };
    }

    public boolean revoked() {
        return revokedAt != null;
    }
}
