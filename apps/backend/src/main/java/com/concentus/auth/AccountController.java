package com.concentus.auth;

import com.concentus.audit.AuditKinds;
import com.concentus.audit.AuditService;
import com.concentus.license.LicenseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Sign-in, sign-out, "who am I", and organization membership.
 *
 * <p>There is no open registration endpoint by design: on a self-hosted install, anyone who can
 * reach the API could otherwise create themselves an organization. New members are invited by an
 * existing administrator through {@link #createMember}, and the very first admin comes from
 * configuration ({@link AccountBootstrap}).
 */
/*
 * Mapped under /api/account, not /api/auth: the pre-existing AuthController owns /api/auth and
 * answers a different question — which Claude credentials the backend is running on. Sign-in and
 * model-provider auth are unrelated concerns and keeping them on separate paths avoids implying
 * otherwise.
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final AuthenticationManager authManager;
    private final AccountStore accounts;
    private final PasswordEncoder encoder;
    private final OrgContext orgContext;
    private final OidcSignIn oidc;
    private final BrowserSignIn browser;
    /** The accounts this browser has already signed into, so switching between them costs a click. */
    private final DeviceAccountStore devices;
    /** How many members this organization's license allows — {@link #createMember} enforces it. */
    private final LicenseService licenseService;
    /** Who was let in and who was promoted — the two membership facts an audit asks about. */
    private final AuditService audit;
    /** What the organization is called, for the row the first account is created under. */
    private final String organizationName;
    /** The caller's groups, for the session; null on a controller built without groups. */
    private final com.concentus.groups.GroupService groups;

    /** Without groups: what the tests that predate them build. */
    public AccountController(AuthenticationManager authManager, AccountStore accounts,
                             PasswordEncoder encoder, OrgContext orgContext,
                             OidcSignIn oidc, BrowserSignIn browser, DeviceAccountStore devices,
                             LicenseService licenseService, AuditService audit, String organizationName) {
        this(authManager, accounts, encoder, orgContext, oidc, browser, devices, licenseService, audit,
                organizationName, null);
    }

    // @Autowired is load-bearing: with two constructors and neither annotated, Spring picks none.
    @org.springframework.beans.factory.annotation.Autowired
    public AccountController(AuthenticationManager authManager, AccountStore accounts,
                             PasswordEncoder encoder, OrgContext orgContext,
                             OidcSignIn oidc, BrowserSignIn browser, DeviceAccountStore devices,
                             LicenseService licenseService, AuditService audit,
                             @Value("${app.organization-name:Concentus}") String organizationName,
                             com.concentus.groups.GroupService groups) {
        this.groups = groups;
        this.authManager = authManager;
        this.accounts = accounts;
        this.encoder = encoder;
        this.orgContext = orgContext;
        this.oidc = oidc;
        this.browser = browser;
        this.devices = devices;
        this.licenseService = licenseService;
        this.audit = audit;
        this.organizationName = organizationName;
    }

    public record LoginRequest(String email, String password) {
    }

    public record NewMemberRequest(String email, String password, String role) {
    }

    public record PasswordChangeRequest(String currentPassword, String newPassword) {
    }

    /**
     * Who is signed in, and what this browser should show if nobody is.
     *
     * <p>{@code setupRequired} is the one state that is neither "signed in" nor "sign in": an
     * installation with no accounts at all, which cannot ask anybody to sign in because there is
     * nobody to be. It answers with the setup screen instead.
     */
    @GetMapping("/session")
    public Map<String, Object> session() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("storeAvailable", accounts.isAvailable());
        // Only meaningful while it is true, and it stops being true the moment the first account
        // exists — which is also the moment /setup starts refusing.
        out.put("setupRequired", accounts.isAvailable() && accounts.countUsers() == 0);
        Optional<ConcentusUserDetails> me = orgContext.currentUser();
        me.ifPresent(u -> {
            out.put("userId", u.userId());
            out.put("email", u.email());
            out.put("organizationId", u.organizationId());
            out.put("role", u.role());
            // What the organization is called, and whether there is more than one to be in —
            // the header names the current one only when the name disambiguates something.
            // Best effort: this endpoint is what decides between the sign-in screen and the
            // workspace, and a database that went away must not turn that into a 500.
            try {
                accounts.findOrganization(u.organizationId())
                        .ifPresent(org -> out.put("organizationName", org.name()));
                out.put("organizationCount", accounts.membershipsOf(u.userId()).size());
                // The groups this person is in, and which they manage — what the Visible-to
                // select offers and the Groups screen opens with, without a second round trip.
                out.put("groups", groups == null ? List.of() : groups.mine());
            } catch (DataAccessException e) {
                log.debug("Could not read the organizations of {}: {}", u.email(), e.getMessage());
            }
        });
        // Every way in this application knows about, and whether each is ready.
        //
        // All of them, not only the configured ones. Hiding the rest answered "can I sign in with
        // my work account here?" with silence, which reads as "no" — when the true answer is
        // usually "yes, once somebody spends two minutes registering it". A button that is there
        // and explains what it needs is a better answer than a button that is not there, as long
        // as it never fails at the redirect: see the sign-in screen, which turns an unregistered
        // one into an explanation rather than a link.
        out.put("providers", oidc.offerable());
        // The single-provider shape this endpoint has always answered with, kept so a client that
        // only knows about one keeps working.
        out.put("ssoEnabled", oidc.isConfigured());
        out.put("ssoName", oidc.displayName());
        out.put("signedIn", me.isPresent());
        return out;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest body,
                                     HttpServletRequest request, HttpServletResponse response) {
        if (body == null || body.email() == null || body.password() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email and password are required.");
        }
        Authentication authentication;
        try {
            authentication = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(body.email(), body.password()));
        } catch (AuthenticationException e) {
            // One message for every failure mode (no such user, wrong password, disabled account)
            // so the endpoint can't be used to discover which addresses are registered.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        }
        browser.establish(authentication, request, response);
        ConcentusUserDetails user = (ConcentusUserDetails) authentication.getPrincipal();
        browser.attach(accounts.findById(user.userId()).orElse(null), request, response);
        return BrowserSignIn.identity(user);
    }

    /** One account this browser can switch to without signing in again. */
    public record SwitchableAccount(String userId, String email, String role, boolean current) {
    }

    /**
     * The accounts this browser has signed into.
     *
     * <p>Answered for the device rather than for the session, so it survives signing out — coming
     * back to an account you left is the case this exists for.
     */
    @GetMapping("/accounts")
    public List<SwitchableAccount> switchableAccounts(HttpServletRequest request) {
        String device = DeviceCookie.of(request);
        String currentId = orgContext.currentUser().map(ConcentusUserDetails::userId).orElse(null);
        return devices.forDevice(device).stream()
                // Read through the account, so a role changed since the last sign-in is the role
                // shown — and an account deleted since then disappears rather than being offered.
                .map(a -> accounts.findById(a.userId()).orElse(null))
                .filter(Objects::nonNull)
                .map(a -> new SwitchableAccount(a.id(), a.email(), a.role(), a.id().equals(currentId)))
                .toList();
    }

    /**
     * Becomes one of the accounts this browser has already signed into.
     *
     * <p>The authorization is the attachment and nothing else: the account has to be one this
     * device signed into, which it could only have done with that account's own password or its
     * own provider. The id in the path is checked against the device's list, never trusted —
     * naming somebody else's account answers "not one of yours".
     *
     * <p>Deliberately not impersonation. An admin cannot become a colleague from here; they can
     * only return to an account they themselves signed into on this machine.
     */
    @PostMapping("/accounts/{userId}/use")
    public Map<String, Object> switchTo(@PathVariable String userId,
                                        HttpServletRequest request, HttpServletResponse response) {
        String device = DeviceCookie.of(request);
        if (device == null || !devices.isAttached(device, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This browser has not signed in to that account.");
        }
        Accounts.UserAccount account = accounts.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "That account no longer exists."));

        // The same fresh session every sign-in gets: whatever the previous account's session held
        // must not be carried into this one.
        ConcentusUserDetails principal = ConcentusUserDetails.of(account);
        browser.establish(BrowserSignIn.authenticationFor(principal), request, response);
        devices.touch(device, userId);
        return BrowserSignIn.identity(principal);
    }

    /**
     * Forgets an account on this browser.
     *
     * <p>The way out of "this machine can become four people". Signing into it again brings it
     * back, so nothing is lost by using it.
     */
    @DeleteMapping("/accounts/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgetAccount(@PathVariable String userId, HttpServletRequest request) {
        String device = DeviceCookie.of(request);
        if (device != null) devices.detach(device, userId);
    }

    public record SetupRequest(String email, String password) {
    }

    /**
     * Creates the first account, and signs it in.
     *
     * <p>The one endpoint that needs no session, and the only window in which it does anything:
     * it refuses the moment an account exists. That is what makes it safe to leave open — an
     * installation with accounts cannot be claimed through it, and one without them has no other
     * way in.
     *
     * <p>Signed in immediately rather than bounced to the login screen. Somebody who has just
     * chosen an address and a password, on this machine, seconds ago, has proved everything a
     * login form would ask them to prove again.
     *
     * <p>An identity provider works too, and is handled where that flow already is: the first
     * account to arrive through one is an administrator for the same reason this one is — it is
     * the account that claimed an empty installation.
     */
    @PostMapping("/setup")
    public Map<String, Object> setup(@RequestBody SetupRequest body,
                                     HttpServletRequest request, HttpServletResponse response) {
        if (!accounts.isAvailable()) {
            throw new IllegalStateException("The database is not reachable, so no account can be "
                    + "created yet.");
        }
        if (accounts.countUsers() > 0) {
            // Not "forbidden": nothing is wrong with the request, it has simply arrived after the
            // question was settled — most likely a second tab that was open during the first.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This installation already has an account. Sign in with it instead.");
        }
        if (body == null || body.email() == null || !body.email().contains("@")) {
            throw new IllegalArgumentException("An email address is required.");
        }
        Accounts.requireStrongPassword(body.password());

        String organizationId = orgContext.defaultOrganizationId();
        accounts.createOrganization(organizationId, organizationName);
        Accounts.UserAccount created = accounts.createUser(organizationId, body.email().trim(),
                encoder.encode(body.password()), Accounts.ROLE_ADMIN);
        log.info("First account created: {} administers this installation.", created.email());

        return browser.signIn(created, request, response);
    }

    /** Members of the caller's own organization. Never returns password hashes. */
    @GetMapping("/members")
    public List<Accounts.UserAccount> members() {
        return accounts.listUsers(orgContext.requireOrganizationId()).stream()
                .map(Accounts.UserAccount::redacted)
                .toList();
    }

    /** Adds a member to the caller's organization. Admin only; the organization is never a parameter. */
    @PostMapping("/members")
    public Accounts.UserAccount createMember(@RequestBody NewMemberRequest body) {
        orgContext.requireAdmin();
        String organizationId = orgContext.requireOrganizationId();
        // Existing members are never touched — this only refuses to add one more. A free
        // installation is licensed for exactly one seat, so the admin who is signed in is already
        // using it; anyone beyond them needs an enterprise license. Seats are counted across the
        // deployment, as distinct accounts: a person in two organizations is one seat.
        int limit = licenseService.seatLimit();
        if (accounts.countUsers() >= limit) {
            throw new IllegalArgumentException(licenseService.seatLimitReachedMessage(limit));
        }
        if (body == null || body.email() == null || body.email().isBlank()) {
            throw new IllegalArgumentException("An email address is required.");
        }
        Accounts.requireStrongPassword(body.password());
        String role = Accounts.roleOrMember(body.role());
        Accounts.UserAccount created = accounts.createUser(organizationId, body.email(),
                encoder.encode(body.password()), role);
        audit.record(AuditKinds.MEMBER_INVITED, "member", created.id(), created.email(),
                Map.of("role", role));
        return created.redacted();
    }

    /**
     * Changes what a member of this organization may do.
     *
     * <p>Admin only, and the organization is never a parameter — it comes from the caller's own
     * session, so a mistyped id cannot reach another tenant's account.
     *
     * <p>The last admin cannot be demoted. Not a courtesy: the alternative is an organization
     * nobody can administer, whose only fix is editing the database by hand.
     */
    @PostMapping("/members/{userId}/role")
    public Accounts.UserAccount changeRole(@PathVariable String userId,
                                           @RequestBody NewMemberRequest body) {
        orgContext.requireAdmin();
        String organizationId = orgContext.requireOrganizationId();
        String role = Accounts.normalizeRole(body == null ? null : body.role());
        if (role == null) {
            throw new IllegalArgumentException("Unknown role. Use one of: "
                    + String.join(", ", Accounts.ROLES) + ".");
        }
        // The membership, not the account row: the account may be working in another
        // organization right now, and the role being changed is the one held HERE.
        Accounts.Membership target = accounts.membership(userId, organizationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No such member of this organization."));
        boolean demotingAnAdmin = Accounts.ROLE_ADMIN.equalsIgnoreCase(target.role())
                && !Accounts.ROLE_ADMIN.equals(role);
        if (demotingAnAdmin && accounts.countByRole(organizationId, Accounts.ROLE_ADMIN) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This is the organization's only admin. Promote someone else first.");
        }
        accounts.updateRole(userId, organizationId, role);
        audit.record(AuditKinds.MEMBER_ROLE_CHANGED, "member", userId,
                accounts.findById(userId).map(Accounts.UserAccount::email).orElse(userId),
                Map.of("from", target.role(), "to", role));
        return accounts.findMember(userId, organizationId).orElseThrow().redacted();
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@RequestBody PasswordChangeRequest body) {
        ConcentusUserDetails me = orgContext.requireUser();
        if (body == null || body.currentPassword() == null
                || !encoder.matches(body.currentPassword(), me.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect.");
        }
        Accounts.requireStrongPassword(body.newPassword());
        accounts.updatePassword(me.userId(), encoder.encode(body.newPassword()));
    }
}
