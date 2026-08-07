package com.concentus.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

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
 */
@Component
public class OrgContext {

    private final String defaultOrganizationId;
    private final boolean authEnabled;

    public OrgContext(@Value("${app.organization-id:default}") String defaultOrganizationId,
                      @Value("${app.auth.enabled:true}") boolean authEnabled) {
        this.defaultOrganizationId = (defaultOrganizationId == null || defaultOrganizationId.isBlank())
                ? "default" : defaultOrganizationId.trim();
        this.authEnabled = authEnabled;
    }

    /** The organization id used when authentication is switched off, and for bootstrap. */
    public String defaultOrganizationId() {
        return defaultOrganizationId;
    }

    public boolean authEnabled() {
        return authEnabled;
    }

    /** The signed-in user, if this thread is serving an authenticated request. */
    public Optional<ConcentusUserDetails> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return Optional.empty();
        return auth.getPrincipal() instanceof ConcentusUserDetails u ? Optional.of(u) : Optional.empty();
    }

    /**
     * The current organization id.
     *
     * @throws IllegalStateException when authentication is on but the request has no principal —
     *         a coding error (an endpoint that should have been behind the filter chain), and one
     *         that must fail rather than silently fall back to the default organization.
     */
    public String requireOrganizationId() {
        Optional<ConcentusUserDetails> user = currentUser();
        if (user.isPresent()) return user.get().organizationId();
        if (!authEnabled) return defaultOrganizationId;
        throw new IllegalStateException("No authenticated user on this request.");
    }

    /** True when the caller may change integration settings and credentials-adjacent config. */
    public boolean isAdmin() {
        return !authEnabled || currentUser().map(u -> Accounts.ROLE_ADMIN.equalsIgnoreCase(u.role())).orElse(false);
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
