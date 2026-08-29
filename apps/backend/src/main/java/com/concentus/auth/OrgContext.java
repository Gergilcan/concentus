package com.concentus.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Resolves which organization the current request belongs to.
 *
 * <p>Always read the organization from here, never from a request parameter or body. Every
 * integration table is partitioned by the value this returns, so taking it from the authenticated
 * principal is what stops one tenant from reading or writing another's mail events, credentials
 * and estimates.
 *
 * <p>Background work (job workers, subscription renewal, delta sync) runs with no authenticated
 * principal, so those call sites pass the organization id explicitly — it comes from the job row
 * or the connection record that scheduled them, not from this class.
 *
 * <p>There is no longer a mode without accounts. It existed for the single-user desktop install,
 * where a password to reach a loopback port bought nothing — and it stopped being true the moment
 * that install could point at a database a team shares, because then every roles screen was
 * describing a policy nothing enforced. One shape, always enforced, is also one shape to reason
 * about: a request either carries a principal or it is refused.
 */
@Component
public class OrgContext {

    private final String defaultOrganizationId;

    public OrgContext(@Value("${app.organization-id:default}") String defaultOrganizationId) {
        this.defaultOrganizationId = (defaultOrganizationId == null || defaultOrganizationId.isBlank())
                ? "default" : defaultOrganizationId.trim();
    }

    /** The organization this installation creates its first account and its records under. */
    public String defaultOrganizationId() {
        return defaultOrganizationId;
    }

    /**
     * The organization this thread is acting for: the signed-in person's current one, or the
     * installation's default when there is no principal.
     *
     * <p>The one to scope a store call by. The default is the right answer for work with no
     * principal — the scheduler, a mail poll, a run's own threads — because on every deployment
     * but a multi-organization one it IS the organization, and a background job that refused to
     * read anything for want of a session would be a scheduler that never fires. Endpoints that
     * must not fall back use {@link #requireOrganizationId()} instead.
     */
    public String currentOrganizationId() {
        return currentUser().map(ConcentusUserDetails::organizationId).orElse(defaultOrganizationId);
    }

    /** The signed-in user, if this thread is serving an authenticated request. */
    public Optional<ConcentusUserDetails> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        return auth.getPrincipal() instanceof ConcentusUserDetails u ? Optional.of(u) : Optional.empty();
    }

    /**
     * The signed-in user, for an endpoint that answers about the caller themselves.
     *
     * @throws ResponseStatusException 401 when there is none. Unlike {@link #requireOrganizationId()}
     *         this is not a coding error: these endpoints are reachable without a session by
     *         design, and "not signed in" is the honest answer to somebody who isn't.
     */
    public ConcentusUserDetails requireUser() {
        return currentUser()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in."));
    }

    /**
     * The current organization id.
     *
     * @throws IllegalStateException when the request has no principal — a coding error (an endpoint
     *         that should have been behind the filter chain), and one that must fail rather than
     *         silently fall back to the default organization.
     */
    public String requireOrganizationId() {
        return currentUser().map(ConcentusUserDetails::organizationId)
                .orElseThrow(() -> new IllegalStateException("No authenticated user on this request."));
    }

    /** True when the caller may change integration settings and credentials-adjacent config. */
    public boolean isAdmin() {
        return currentUser().map(u -> Accounts.ROLE_ADMIN.equalsIgnoreCase(u.role())).orElse(false);
    }

    /** Throws unless the caller is an admin of their organization. */
    public void requireAdmin() {
        if (!isAdmin()) {
            throw new AccessDeniedForOrganization("This action requires an organization administrator.");
        }
    }

    /**
     * Throws unless {@code resourceOrganizationId} is the caller's own organization. Used by every
     * endpoint that loads a record by id, so a guessed or leaked id from another tenant reads as
     * "not yours" rather than returning data.
     */
    public void requireOwnership(String resourceOrganizationId) {
        String mine = requireOrganizationId();
        if (resourceOrganizationId == null || !resourceOrganizationId.equals(mine)) {
            throw new AccessDeniedForOrganization("Not found in this organization.");
        }
    }

    /** Ownership/permission failure, mapped to 403 by {@code ApiExceptionHandler}. */
    public static class AccessDeniedForOrganization extends RuntimeException {
        public AccessDeniedForOrganization(String message) {
            super(message);
        }
    }
}
