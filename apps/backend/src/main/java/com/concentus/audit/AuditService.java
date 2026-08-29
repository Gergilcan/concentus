package com.concentus.audit;

import com.concentus.auth.ConcentusUserDetails;
import com.concentus.auth.OrgContext;
import com.concentus.store.AuditStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Writes the audit trail: one call at each place something worth remembering happens.
 *
 * <p><b>Never in the way.</b> A run that could not be recorded still starts; a member whose
 * invitation could not be logged is still invited. The trail exists to answer questions later,
 * and a trail that turns a database hiccup into a refused action would be answering them by
 * stopping the work — every {@code record} here catches, logs, and returns. What it does NOT do
 * is swallow silently: the warning names the kind and the subject, so a trail with a hole in it
 * is a trail whose hole is in the log.
 *
 * <p><b>Who.</b> The actor is read from the request's principal, here, rather than passed in by
 * each caller: a caller that had to say who it was could say it wrong, and the security context
 * is the one place that cannot. When there is no principal — a cron tick, a webhook delivery, a
 * mail trigger, the retention job — the caller names the trigger and the row is credited to
 * {@code system:<trigger>}. A run a person pressed and a run a schedule fired look identical to
 * the run service; they must not look identical here.
 *
 * <p><b>What.</b> Callers pass a detail map and this class turns it into JSON. The one rule that
 * cannot be enforced by a type is enforced by convention at every site: labels, keys, ids,
 * counts, flags — never a credential's value, never a secret setting's value.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /** The actor written when nobody is signed in and the caller named no trigger. */
    public static final String SYSTEM = "system";

    private final AuditStore store;
    private final OrgContext orgContext;
    private final ObjectMapper mapper;

    public AuditService(AuditStore store, OrgContext orgContext, ObjectMapper mapper) {
        this.store = store;
        this.orgContext = orgContext;
        this.mapper = mapper;
    }

    /**
     * Records an action by whoever is on the request — or by {@link #SYSTEM} when nobody is.
     *
     * @param kind         one of {@link AuditKinds}
     * @param subjectType  "run", "flow", "member", … — what {@code subjectId} names
     * @param subjectId    the subject's id; null when it has none (a setting, a backup)
     * @param subjectLabel the subject's name for a person; survives the subject's deletion
     * @param detail       anything else worth keeping, as a JSON object; may be null or empty
     */
    public void record(String kind, String subjectType, String subjectId, String subjectLabel,
                       Map<String, ?> detail) {
        recordAs(null, kind, subjectType, subjectId, subjectLabel, detail);
    }

    /**
     * As {@link #record}, for an action a machine may have taken: a signed-in person is still
     * credited when there is one, and otherwise the row says {@code system:<trigger>} — "cron",
     * "webhook", "mail", "retention" — so a run's row reads as what fired it rather than as a blank.
     */
    public void recordSystem(String trigger, String kind, String subjectType, String subjectId,
                             String subjectLabel, Map<String, ?> detail) {
        String label = trigger == null || trigger.isBlank() ? SYSTEM : SYSTEM + ":" + trigger.trim();
        recordAs(label, kind, subjectType, subjectId, subjectLabel, detail);
    }

    private void recordAs(String systemActor, String kind, String subjectType, String subjectId,
                          String subjectLabel, Map<String, ?> detail) {
        try {
            ConcentusUserDetails user = orgContext.currentUser().orElse(null);
            String actor = user != null ? user.email() : (systemActor == null ? SYSTEM : systemActor);
            String role = user != null ? user.role() : null;
            String organizationId = user != null ? user.organizationId()
                    : orgContext.defaultOrganizationId();
            store.append(System.currentTimeMillis(), organizationId, actor, role, kind,
                    subjectType, subjectId, subjectLabel, toJson(detail));
        } catch (Exception e) {
            // See the class comment: the action already happened, and refusing it now would be
            // worse than a trail with a gap the log explains.
            log.warn("Audit event {} on {} {} was not recorded: {}", kind, subjectType, subjectId,
                    e.getMessage());
        }
    }

    private String toJson(Map<String, ?> detail) {
        if (detail == null || detail.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(detail);
        } catch (Exception e) {
            return null;
        }
    }
}
