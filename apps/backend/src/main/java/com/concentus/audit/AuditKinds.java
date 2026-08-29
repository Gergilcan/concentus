package com.concentus.audit;

import java.util.List;

/**
 * The vocabulary of the audit trail — every {@code kind} a row may carry, in one place.
 *
 * <p>Constants rather than free strings at the recording sites, so a typo cannot fork "flow.saved"
 * into two kinds that a filter would never reunite; and one list, so the panel's filter offers
 * exactly the kinds the backend can produce rather than a copy that drifts.
 *
 * <p>Dotted {@code subject.verb}, past tense: it reads as a sentence with the actor in front of
 * it and sorts by subject in any listing.
 */
public final class AuditKinds {

    private AuditKinds() {
    }

    public static final String RUN_STARTED = "run.started";
    public static final String RUN_STOPPED = "run.stopped";
    public static final String RUN_APPROVED = "run.approved";
    public static final String RUN_REJECTED = "run.rejected";
    public static final String RUN_RETRIED = "run.retried";
    public static final String RUN_RESUMED = "run.resumed";
    public static final String RUN_GOLDEN_SET = "run.golden_set";
    public static final String RUN_GOLDEN_UNSET = "run.golden_unset";

    public static final String FLOW_CREATED = "flow.created";
    public static final String FLOW_SAVED = "flow.saved";
    public static final String FLOW_DELETED = "flow.deleted";
    public static final String FLOW_PUBLISHED = "flow.published";
    public static final String FLOW_UNPUBLISHED = "flow.unpublished";
    public static final String FLOW_TOKEN_REGENERATED = "flow.token_regenerated";

    public static final String MEMBER_INVITED = "member.invited";
    public static final String MEMBER_ROLE_CHANGED = "member.role_changed";

    public static final String CREDENTIAL_CREATED = "credential.created";
    public static final String CREDENTIAL_UPDATED = "credential.updated";
    public static final String CREDENTIAL_DELETED = "credential.deleted";

    public static final String SETTING_CHANGED = "setting.changed";
    public static final String LICENSE_INSTALLED = "license.installed";
    public static final String BACKUP_EXPORTED = "backup.exported";
    public static final String RETENTION_PURGED = "retention.purged";

    /** Every kind, in the order the filter lists them. */
    public static final List<String> ALL = List.of(
            RUN_STARTED, RUN_STOPPED, RUN_APPROVED, RUN_REJECTED, RUN_RETRIED, RUN_RESUMED,
            RUN_GOLDEN_SET, RUN_GOLDEN_UNSET,
            FLOW_CREATED, FLOW_SAVED, FLOW_DELETED, FLOW_PUBLISHED, FLOW_UNPUBLISHED,
            FLOW_TOKEN_REGENERATED,
            MEMBER_INVITED, MEMBER_ROLE_CHANGED,
            CREDENTIAL_CREATED, CREDENTIAL_UPDATED, CREDENTIAL_DELETED,
            SETTING_CHANGED, LICENSE_INSTALLED, BACKUP_EXPORTED, RETENTION_PURGED);
}
