package com.concentus.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
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

    private final AuthenticationManager authManager;
    private final AccountStore accounts;
    private final PasswordEncoder encoder;
    private final OrgContext orgContext;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AccountController(AuthenticationManager authManager, AccountStore accounts,
                             PasswordEncoder encoder, OrgContext orgContext) {
        this.authManager = authManager;
        this.accounts = accounts;
        this.encoder = encoder;
        this.orgContext = orgContext;
    }

    public record LoginRequest(String email, String password) {
    }

    public record NewMemberRequest(String email, String password, String role) {
    }

    public record PasswordChangeRequest(String currentPassword, String newPassword) {
    }

    /** Whether sign-in is required at all, plus the current session's identity if there is one. */
    @GetMapping("/session")
    public Map<String, Object> session() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("authEnabled", orgContext.authEnabled());
        out.put("storeAvailable", accounts.isAvailable());
        Optional<ConcentusUserDetails> me = orgContext.currentUser();
        me.ifPresent(u -> {
            out.put("userId", u.userId());
            out.put("email", u.email());
            out.put("organizationId", u.organizationId());
            out.put("role", u.role());
        });
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
        // A brand-new session id after authenticating, so a session fixed before sign-in can't be
        // reused afterwards.
        HttpSession existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        request.getSession(true);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        ConcentusUserDetails user = (ConcentusUserDetails) authentication.getPrincipal();
        return Map.of("userId", user.userId(), "email", user.email(),
                "organizationId", user.organizationId(), "role", user.role());
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
        if (body == null || body.email() == null || body.email().isBlank()) {
            throw new IllegalArgumentException("An email address is required.");
        }
        Accounts.requireStrongPassword(body.password());
        String role = Accounts.ROLE_ADMIN.equalsIgnoreCase(body.role())
                ? Accounts.ROLE_ADMIN : Accounts.ROLE_MEMBER;
        return accounts.createUser(orgContext.requireOrganizationId(), body.email(),
                encoder.encode(body.password()), role).redacted();
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@RequestBody PasswordChangeRequest body) {
        ConcentusUserDetails me = orgContext.currentUser()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in."));
        if (body == null || body.currentPassword() == null
                || !encoder.matches(body.currentPassword(), me.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect.");
        }
        Accounts.requireStrongPassword(body.newPassword());
        accounts.updatePassword(me.userId(), encoder.encode(body.newPassword()));
    }
}
