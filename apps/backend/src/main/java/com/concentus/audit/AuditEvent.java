package com.concentus.audit;

/**
 * One row of the audit trail, as read back.
 *
 * @param id          the row's serial number — the paging cursor, newest largest
 * @param at          epoch milliseconds
 * @param actorEmail  who: a signed-in address, or {@code system:<trigger>} for a run nobody started
 * @param actorRole   the role the actor held at the time; null for the system
 * @param kind        what happened — {@link AuditKinds}
 * @param subjectType what it happened to: "run", "flow", "member", "credential", "setting",
 *                    "license", "backup", "retention"
 * @param subjectId   its id, when it has one
 * @param subjectLabel its name as a person knows it, kept so the row still reads after the
 *                    subject is gone
 * @param detail      whatever else the action wanted remembered, as a JSON object; never a secret
 */
public record AuditEvent(long id, long at, String actorEmail, String actorRole, String kind,
                         String subjectType, String subjectId, String subjectLabel, String detail) {
}
