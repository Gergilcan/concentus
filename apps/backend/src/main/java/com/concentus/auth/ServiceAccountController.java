package com.concentus.auth;

import com.concentus.license.Feature;
import com.concentus.license.LicenseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Minting, listing, renaming and revoking the tokens machines act with.
 *
 * <p>Admin only, every route — reading included. The list is a list of what can act as this
 * organization from outside, which is administration rather than information; and the ceiling on
 * a service account's role (MEMBER) means no token can reach any of this, so a leaked token cannot
 * mint its successors.
 *
 * <p>The token appears exactly once, in the answer to {@link #create}. It is not stored — only its
 * hash is — so there is no endpoint that shows it again; losing it means revoking and minting.
 *
 * <p>The Team cap is enforced here and only here. A Team deployment may hold
 * {@link Feature#TEAM_SERVICE_ACCOUNTS} working tokens; the next is refused with the feature's own
 * refusal sentence and the count, so the answer says both what the limit is and what to do.
 * Revoked tokens do not count. A free installation is never capped — one person on their own
 * machine — and Enterprise is unlimited.
 */
@RestController
@RequestMapping("/api/service-accounts")
public class ServiceAccountController {

    private static final Logger log = LoggerFactory.getLogger(ServiceAccountController.class);

    /** Longest name. A label on a list, not a description. */
    static final int MAX_NAME = 80;

    private final ServiceAccountStore store;
    private final OrgContext orgContext;
    private final LicenseService license;

    public ServiceAccountController(ServiceAccountStore store, OrgContext orgContext, LicenseService license) {
        this.store = store;
        this.orgContext = orgContext;
        this.license = license;
    }

    public record CreateRequest(String name, String role) {
    }

    public record RenameRequest(String name) {
    }

    /**
     * The list, with what the screen needs to draw the create button honestly.
     *
     * @param active   how many tokens still work
     * @param limit    the cap in force, or null where there is none (free, Enterprise)
     * @param refusal  why one more cannot be minted right now, or null while it can
     */
    public record Listing(List<ServiceAccount> accounts, long active, Integer limit, String refusal) {
    }

    /** The one answer that carries the token. */
    public record Created(ServiceAccount account, String token) {
    }

    @GetMapping
    public Listing list() {
        orgContext.requireAdmin();
        String organizationId = orgContext.requireOrganizationId();
        long active = store.countActive(organizationId);
        return new Listing(store.list(organizationId), active, capInForce(), capRefusal(active));
    }

    @PostMapping
    public Created create(@RequestBody CreateRequest body) {
        orgContext.requireAdmin();
        String organizationId = orgContext.requireOrganizationId();
        String name = requireName(body == null ? null : body.name());
        String role = roleFor(body == null ? null : body.role());

        // Counted at the moment of minting, not from a cached list: two admins racing past the cap
        // would otherwise both succeed on a count read minutes earlier.
        String refusal = capRefusal(store.countActive(organizationId));
        if (refusal != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, refusal);
        }

        String token = ServiceAccount.mintToken();
        String createdBy = orgContext.currentUser().map(ConcentusUserDetails::email).orElse(null);
        ServiceAccount account = store.create(organizationId, name, role, ServiceAccount.hash(token), createdBy);
        log.info("Service account '{}' ({}) minted by {}.", account.name(), role, createdBy);
        return new Created(account, token);
    }

    @PutMapping("/{id}")
    public ServiceAccount rename(@PathVariable String id, @RequestBody RenameRequest body) {
        orgContext.requireAdmin();
        String organizationId = orgContext.requireOrganizationId();
        String name = requireName(body == null ? null : body.name());
        if (!store.rename(id, organizationId, name)) throw notFound();
        return store.find(id, organizationId).orElseThrow(ServiceAccountController::notFound);
    }

    /**
     * Stops the token working, from the next request on. The row stays, stamped — "what could
     * act as this organization, and until when" is an answer an audit needs afterwards.
     */
    @PostMapping("/{id}/revoke")
    public ServiceAccount revoke(@PathVariable String id) {
        orgContext.requireAdmin();
        String organizationId = orgContext.requireOrganizationId();
        ServiceAccount account = store.find(id, organizationId).orElseThrow(ServiceAccountController::notFound);
        if (account.revokedAt() == null) {
            store.revoke(id, organizationId, System.currentTimeMillis());
            log.info("Service account '{}' revoked by {}.", account.name(),
                    orgContext.currentUser().map(ConcentusUserDetails::email).orElse("an admin"));
        }
        return store.find(id, organizationId).orElseThrow(ServiceAccountController::notFound);
    }

    // ------------------------------------------------------------------ the cap

    /** The number of working tokens a deployment may hold, or null where nothing caps it. */
    private Integer capInForce() {
        return license.teamTier() ? Feature.TEAM_SERVICE_ACCOUNTS : null;
    }

    /** Why one more token is refused with {@code active} in use, or null while there is room. */
    private String capRefusal(long active) {
        Integer cap = capInForce();
        if (cap == null || active < cap) return null;
        return license.refusal(Feature.SERVICE_ACCOUNTS) + " This deployment has " + active + " of "
                + cap + " service accounts in use; revoke one to mint another.";
    }

    // ------------------------------------------------------------------ validation

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A name is required — what the machine is, e.g. \"nightly-report\".");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME) {
            throw new IllegalArgumentException("The name is limited to " + MAX_NAME + " characters.");
        }
        return trimmed;
    }

    /**
     * The role to store: OPERATOR when unspecified — running flows is what a machine is usually
     * for — and never above {@link ServiceAccount#ROLE_CEILING}. Refused rather than clamped when
     * ADMIN is asked for: an admin who typed it should be told why not, not handed a MEMBER and a
     * puzzle.
     */
    private static String roleFor(String requested) {
        if (requested == null || requested.isBlank()) return Accounts.ROLE_OPERATOR;
        String role = Accounts.normalizeRole(requested);
        if (role == null) {
            throw new IllegalArgumentException("Unknown role '" + requested + "'. A service account can be one of: "
                    + String.join(", ", allowedRoles()) + ".");
        }
        if (!Accounts.atLeast(ServiceAccount.ROLE_CEILING, role)) {
            throw new IllegalArgumentException("A service account cannot be an " + role
                    + ": a token that could administer could mint more tokens. The highest role is "
                    + ServiceAccount.ROLE_CEILING + ".");
        }
        return role;
    }

    /** VIEWER, OPERATOR, MEMBER — the ladder up to the ceiling. */
    static List<String> allowedRoles() {
        return Accounts.ROLES.stream().filter(r -> Accounts.atLeast(ServiceAccount.ROLE_CEILING, r)).toList();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such service account in this organization.");
    }
}
