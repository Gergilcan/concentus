package com.concentus.service;

import com.concentus.auth.Accounts;
import com.concentus.auth.ConcentusUserDetails;
import com.concentus.auth.OrgContext;
import com.concentus.model.KnowledgeDef;
import com.concentus.store.KnowledgeStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Who may read which knowledge base.
 *
 * <p>Every other resource in this application is reachable by anybody who may edit flows, and for
 * an agent definition or a webhook that is the right answer — they are configuration. A knowledge
 * base is not configuration; it is <em>the documents themselves</em>. Salary bands, an incident
 * post-mortem, a contract: material where "anybody who can edit a flow" is plainly the wrong
 * audience, and where the leak does not look like a leak. It looks like an agent answering a
 * question well.
 *
 * <p>So a base can name the lowest role that may read it, on the same ladder everything else uses.
 * <b>Absent means everybody</b>, which is what every base created before this existed meant and
 * must go on meaning — an upgrade that silently locked people out of their own documents would be
 * a worse failure than the one this prevents.
 *
 * <p><b>Enforced twice, deliberately.</b> Once at the API, so a person cannot read a base they may
 * not read. And once when a run injects a base, against the role of whoever started the run —
 * because otherwise the restriction is one flow away from meaningless: a Viewer who may run a flow
 * an Admin drew would receive, in the answer, exactly the passages they were not allowed to open.
 */
@Component
public class KnowledgeAccess {

    private final KnowledgeStore store;
    private final OrgContext orgContext;
    private final com.concentus.auth.AccountStore accounts;

    public KnowledgeAccess(KnowledgeStore store, OrgContext orgContext,
                           com.concentus.auth.AccountStore accounts) {
        this.store = store;
        this.orgContext = orgContext;
        this.accounts = accounts;
    }

    /** Whether a role may read this base. A base with no minimum stated may be read by anybody. */
    public boolean mayRead(KnowledgeDef base, String role) {
        String minimum = base.minRole();
        if (minimum == null || minimum.isBlank()) return true;
        return Accounts.atLeast(role, minimum);
    }

    public boolean mayRead(String baseId, String role) {
        return store.get(baseId).map(base -> mayRead(base, role)).orElse(false);
    }

    /**
     * Refuses a base the current request may not read.
     *
     * <p>The message says the base is restricted rather than that it does not exist. Hiding the
     * existence of a base from somebody who can see it named on a canvas they are looking at would
     * be a confusion, not a protection — and the name is not the secret; the documents are.
     */
    public void requireRead(String baseId) {
        String role = currentRole();
        if (!mayRead(baseId, role)) {
            throw new AccessDenied("That knowledge base is restricted to "
                    + store.get(baseId).map(KnowledgeDef::minRole).orElse("a higher role")
                    + " and above.");
        }
    }

    /** The bases this request may see, for a picker that should not offer what it cannot open. */
    public List<KnowledgeDef> readable() {
        String role = currentRole();
        return store.list().stream().filter(base -> mayRead(base, role)).toList();
    }

    /**
     * The role a run should be judged by: whoever started it.
     *
     * <p>Not the role of whoever drew the flow. A flow is a document that outlives its author's
     * session, and inheriting the author's reach would make every restricted base readable by
     * anybody who could press Run on the right flow.
     */
    public boolean mayRunRead(String baseId, String startedByEmail) {
        return mayRunRead(orgContext.currentOrganizationId(), baseId, startedByEmail);
    }

    /**
     * As {@link #mayRunRead(String, String)}, for a run that knows its organization: a run's
     * threads carry no principal, so the base is looked up in the organization the run was
     * stamped with rather than in whatever the calling thread would resolve to. A base of another
     * organization is simply not there, whatever the role.
     */
    public boolean mayRunRead(String organizationId, String baseId, String startedByEmail) {
        Optional<KnowledgeDef> base = store.getIn(organizationId, baseId);
        if (base.isEmpty()) return false;
        if (startedByEmail == null || startedByEmail.isBlank()) {
            // Nobody started it: a schedule, a webhook delivery, a flow started by another flow.
            // There is no person to restrict, and the run acts as the installation itself.
            return true;
        }
        String role = accounts.findByEmail(startedByEmail)
                .map(Accounts.UserAccount::role)
                // An email with no account behind it any more — somebody who has left. Their runs
                // do not inherit the reach they no longer have.
                .orElse(Accounts.ROLE_VIEWER);
        return mayRead(base.get(), role);
    }

    private String currentRole() {
        return orgContext.currentUser()
                .map(ConcentusUserDetails::role)
                // No principal at all is background work — a schedule, a mail poll — which has no
                // person to restrict and runs as the installation itself.
                .orElse(Accounts.ROLE_ADMIN);
    }

    /** Refused for a reason the caller is allowed to know. Mapped to 403 by the error handler. */
    public static class AccessDenied extends RuntimeException {
        public AccessDenied(String message) {
            super(message);
        }
    }
}
