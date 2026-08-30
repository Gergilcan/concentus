package com.concentus.runners;

/**
 * A runner as the screen sees it: the row and the socket together, plus what the caller may do.
 *
 * @param mine   the caller owns it (a user-scoped one) or registered it
 * @param usable the caller may run flows on it
 */
public record RunnerView(String id, String organizationId, String name, String scope, String groupId,
                         String groupName, String userId, String ownerEmail, String createdBy, long createdAt,
                         Long lastSeenAt, Long revokedAt, boolean online, int busy, Integer capacity,
                         String hostname, String os, String arch, String version, String claudeVersion,
                         String authKind, Long connectedAt, boolean mine, boolean usable) {

    public static RunnerView of(Runner r, RunnerRegistry.Live live, String groupName, String ownerEmail,
                                boolean mine, boolean usable) {
        RunnerRegistry.Live l = live == null ? RunnerRegistry.Live.OFFLINE : live;
        return new RunnerView(r.id(), r.organizationId(), r.name(), r.scope(), r.groupId(), groupName, r.userId(),
                ownerEmail, r.createdBy(), r.createdAt(), r.lastSeenAt(), r.revokedAt(), l.online(), l.busy(),
                l.capacity(), l.hostname(), l.os(), l.arch(), l.version(), l.claudeVersion(), l.authKind(),
                l.connectedAt(), mine, usable);
    }
}
