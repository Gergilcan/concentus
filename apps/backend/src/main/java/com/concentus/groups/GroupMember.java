package com.concentus.groups;

/**
 * One account's place in one group.
 *
 * @param role      the role the account holds in the ORGANIZATION the group belongs to — what a
 *                  member may do is still decided there; a group has no roles of its own beyond
 *                  {@code manager}
 * @param manager   may add and remove the group's members and edit its settings and policy
 * @param createdAt when they were put into the group
 */
public record GroupMember(String userId, String email, String role, boolean manager, long createdAt) {
}
