package com.concentus.auth;

import com.concentus.license.Feature;
import com.concentus.license.LicenseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Several organizations on one deployment: which ones you are in, making another, and moving
 * between them.
 *
 * <p>An organization is the isolation boundary — every store filters on it — so "another
 * organization" is a second, separate workspace on the same server: its own flows, credentials,
 * runs and settings, invisible from the first. A person can be in several, with a different role
 * in each, and works in one at a time; the switch is what changes which.
 *
 * <p>Creating a second one is {@link Feature#MULTI_ORG}, the Enterprise gate. Renaming the one
 * you have, and everything about memberships, is every tier's: a free installation has one
 * organization and may call it what it likes.
 *
 * <p>Authorization is by membership, never by the organization id in the path being the caller's
 * own: an ADMIN of organization A invites people into organization B only if they are an ADMIN of
 * B too. The one exception is creating: that takes an admin of the organization the caller is
 * currently in, because there is no B yet to be an admin of — and whoever creates it administers
 * it, so there is somebody to invite the rest.
 */
@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    private final AccountStore accounts;
    private final OrgContext orgContext;
    private final LicenseService licenseService;
    private final PasswordEncoder encoder;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public OrganizationController(AccountStore accounts, OrgContext orgContext,
                                  LicenseService licenseService, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.orgContext = orgContext;
        this.licenseService = licenseService;
        this.encoder = encoder;
    }

    /** One organization as the caller sees it: with their own role there, and whether they are in it now. */
    public record OrganizationView(String id, String name, String role, boolean current, long createdAt) {
    }

    public record NameRequest(String name) {
    }

    /**
     * @param password for an address that has no account yet; blank means "the account exists,
     *                 add it here" and is refused when it does not
     */
    public record InviteRequest(String email, String password, String role) {
    }

    /** The organizations the caller is in. Every role: a Viewer in two of them switches too. */
    @GetMapping
    public List<OrganizationView> mine() {
        ConcentusUserDetails me = requireMe();
        return accounts.organizationsOf(me.userId()).stream()
                .map(org -> view(org, me))
                .toList();
    }

    /**
     * Makes a second organization, with the caller as its first administrator.
     *
     * <p>The gate is here and nowhere else: an organization that exists keeps working whatever
     * happens to the license, because the alternative is a deployment whose second team loses
     * its flows the day a renewal is late.
     */
    @PostMapping
    public OrganizationView create(@RequestBody NameRequest body) {
        orgContext.requireAdmin();
        ConcentusUserDetails me = requireMe();
        if (!licenseService.allows(Feature.MULTI_ORG)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, licenseService.refusal(Feature.MULTI_ORG));
        }
        String name = requireName(body);
        Accounts.Organization created = accounts.createOrganization(null, name);
        accounts.addMembership(me.userId(), created.id(), Accounts.ROLE_ADMIN);
        return view(created, me);
    }

    /** Renames an organization the caller administers. Allowed on every tier. */
    @PutMapping("/{id}")
    public OrganizationView rename(@PathVariable String id, @RequestBody NameRequest body) {
        ConcentusUserDetails me = requireMe();
        requireAdminOf(me, id);
        accounts.renameOrganization(id, requireName(body));
        return view(accounts.findOrganization(id).orElseThrow(), me);
    }

    /** Everyone in one organization the caller administers, with the role each holds there. */
    @GetMapping("/{id}/members")
    public List<Accounts.UserAccount> members(@PathVariable String id) {
        requireAdminOf(requireMe(), id);
        return accounts.listUsers(id).stream().map(Accounts.UserAccount::redacted).toList();
    }

    /**
     * Puts an account into an organization, creating the account when the address has none.
     *
     * <p>An existing account costs no seat — seats are distinct people on the deployment, and this
     * person is already one of them. A new one is checked against the license exactly as the
     * Members screen checks it.
     */
    @PostMapping("/{id}/members")
    public Accounts.UserAccount invite(@PathVariable String id, @RequestBody InviteRequest body) {
        requireAdminOf(requireMe(), id);
        if (body == null || body.email() == null || body.email().isBlank()) {
            throw new IllegalArgumentException("An email address is required.");
        }
        String role = body.role() == null || body.role().isBlank()
                ? Accounts.ROLE_MEMBER
                : Accounts.normalizeRole(body.role());
        if (role == null) {
            throw new IllegalArgumentException("Unknown role '" + body.role() + "'. Use one of: "
                    + String.join(", ", Accounts.ROLES) + ".");
        }
        Accounts.UserAccount existing = accounts.findByEmail(body.email()).orElse(null);
        if (existing != null) {
            accounts.addMembership(existing.id(), id, role);
        } else {
            if (body.password() == null || body.password().isBlank()) {
                throw new IllegalArgumentException("No account has that address yet. Give it a "
                        + "temporary password to create one, or invite an existing address.");
            }
            int limit = licenseService.seatLimit();
            if (accounts.countUsers() >= limit) {
                throw new IllegalArgumentException(licenseService.seatLimitReachedMessage(limit));
            }
            Accounts.requireStrongPassword(body.password());
            existing = accounts.createUser(id, body.email(), encoder.encode(body.password()), role);
        }
        String userId = existing.id();
        return accounts.listUsers(id).stream()
                .filter(u -> u.id().equals(userId))
                .findFirst().orElseThrow().redacted();
    }

    /**
     * Starts working in another of the caller's organizations.
     *
     * <p>Rewrites the account row's current organization and role, then replaces the principal in
     * this session with one read back from it — so the next request, and every store keyed by
     * the principal's organization, sees the new one. Nothing else has to know a switch happened,
     * which is the whole reason the principal carries one organization rather than a list.
     */
    @PostMapping("/{id}/switch")
    public Map<String, Object> switchTo(@PathVariable String id, HttpServletRequest request,
                                        HttpServletResponse response) {
        ConcentusUserDetails me = requireMe();
        if (!accounts.switchOrganization(me.userId(), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "You are not a member of that organization.");
        }
        Accounts.UserAccount account = accounts.findById(me.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That account no longer exists."));
        ConcentusUserDetails principal = ConcentusUserDetails.of(account);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return Map.of("userId", account.id(), "email", account.email(),
                "organizationId", account.organizationId(),
                "organizationName", accounts.findOrganization(id).map(Accounts.Organization::name).orElse(id),
                "role", account.role());
    }

    private ConcentusUserDetails requireMe() {
        return orgContext.currentUser()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in."));
    }

    /** Admin of THAT organization, by membership — not of whichever one the caller is working in. */
    private void requireAdminOf(ConcentusUserDetails me, String organizationId) {
        boolean admin = accounts.membership(me.userId(), organizationId)
                .map(m -> Accounts.ROLE_ADMIN.equalsIgnoreCase(m.role()))
                .orElse(false);
        if (!admin) {
            throw new OrgContext.AccessDeniedForOrganization(
                    "This action requires an administrator of that organization.");
        }
    }

    private static String requireName(NameRequest body) {
        if (body == null || body.name() == null || body.name().isBlank()) {
            throw new IllegalArgumentException("An organization needs a name.");
        }
        return body.name().trim();
    }

    private OrganizationView view(Accounts.Organization org, ConcentusUserDetails me) {
        String role = accounts.membership(me.userId(), org.id()).map(Accounts.Membership::role).orElse(null);
        return new OrganizationView(org.id(), org.name(), role, org.id().equals(me.organizationId()),
                org.createdAt());
    }
}
