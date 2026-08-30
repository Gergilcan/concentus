package com.concentus.groups;

/**
 * One group inside an organization, as the roster shows it.
 *
 * <p>A group is a subset of an organization's members that two things follow: which resources
 * are visible (a row scoped to the group is seen by its members and the organization's admins,
 * and by nobody else), and which settings and policy a flow of the group runs under. It is never
 * a second organization — a group-scoped row is still the organization's row, and an ADMIN sees
 * and administers every group.
 *
 * @param members   how many accounts are in it
 * @param resources how many rows are scoped to it — resources and credentials together, which is
 *                  what "delete this group" un-scopes
 * @param manager   whether the caller manages it: may add and remove members and edit its
 *                  settings and policy. An organization ADMIN is not marked a manager of anything
 *                  and may do all of that for every group; the flag is about membership, and the
 *                  screen asks {@code isAdmin} separately.
 */
public record Group(String id, String organizationId, String name, String description, long createdAt,
                    String createdBy, int members, int resources, boolean manager) {

    /** The prefix of a group id: {@code gr_} and twelve hex characters. */
    public static final String ID_PREFIX = "gr_";

    /** A short reference — what the session and the status endpoint carry per group. */
    public record Ref(String id, String name, boolean manager) {
    }

    public Ref ref() {
        return new Ref(id, name, manager);
    }

    /** This group as one caller sees it. */
    public Group asSeenBy(boolean managesIt) {
        return new Group(id, organizationId, name, description, createdAt, createdBy, members, resources, managesIt);
    }
}
