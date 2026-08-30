package com.concentus.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The desktop shell's own credential, for the three calls it has to make before anyone is signed in.
 *
 * <p>The first-run wizard asks which database to use, and it asks <em>before</em> the first account
 * exists — that is the whole point of a first run. So its three calls (read the setting, test a
 * connection, save it) cannot present a session cookie, and until this existed they arrived
 * anonymous: the CSRF filter refused them first, which is why choosing an external database
 * answered "You do not have permission to do that."
 *
 * <p>The alternative was to open those endpoints on the desktop build, resting on the loopback bind
 * the way {@code /actuator/shutdown} does. That was rejected: a desktop install can point at a
 * database a team shares, and there the roles screen means something — opening the endpoints would
 * have let a VIEWER repoint everyone's storage. A token the shell generates per launch and passes
 * to the backend it starts keeps the browser-facing rules exactly as they were, and grants the one
 * caller that provably <em>is</em> the person at the machine: it started the process.
 *
 * <p>Deliberately narrow in three ways. It is off unless a token is configured, which is only in
 * the desktop profile. It answers for three routes and no others, so a leaked token does not become
 * an API key. And the principal it authenticates is a bare name rather than an account, so
 * {@link OrgContext#currentUser()} stays empty and everything that asks who is calling — including
 * {@code /api/storage/contents} and {@code /api/storage/migrate}, which read and copy the data
 * itself — still refuses it.
 */
public class ShellTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ShellTokenFilter.class);

    /** Named in the log and nowhere else; it is not an account and cannot be granted one. */
    private static final String PRINCIPAL = "desktop-shell";

    /**
     * Exactly what the wizard and the tray call. Anything else is not the shell's business. The
     * runner status is the one addition since the storage trio: the tray shows whether this
     * install's own runner agent is connected to its server, and the tray has no session either.
     */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "/api/storage", Set.of("GET", "PUT"),
            "/api/storage/test", Set.of("POST"),
            "/api/runners/self", Set.of("GET"));

    /** MEMBER rather than ADMIN: it is the least that satisfies the rule guarding these routes. */
    private static final List<SimpleGrantedAuthority> AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_" + Accounts.ROLE_MEMBER));

    /** Empty when no token is configured, which disables this filter entirely. */
    private final byte[] expected;

    public ShellTokenFilter(String token) {
        String trimmed = token == null ? "" : token.trim();
        this.expected = trimmed.getBytes(StandardCharsets.UTF_8);
        if (trimmed.isEmpty()) return;
        log.info("Desktop shell authentication is on for {} — the first-run wizard's storage calls.",
                ALLOWED.keySet());
    }

    /**
     * Whether this request carries the shell's token for a route the shell may use.
     *
     * <p>Public because CSRF has to ask the same question: the CSRF filter runs before this one, so
     * without an exemption it would refuse the wizard's POST and PUT before authentication ever
     * happened. Exempting them is not a hole — the token is what stands in for the CSRF token, and
     * a page in a browser can never have it (it is generated per launch and handed only to the
     * process the shell started).
     */
    public boolean authenticates(HttpServletRequest request) {
        if (expected.length == 0) return false;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Set<String> methods = ALLOWED.get(path);
        return methods != null && methods.contains(request.getMethod()) && matches(presented(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // A request that already carries a principal is a signed-in browser on the same route, and
        // it keeps its own identity: the shell's token adds a caller, it never replaces one.
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null || !authenticates(request)) {
            chain.doFilter(request, response);
            return;
        }

        UsernamePasswordAuthenticationToken auth =
                UsernamePasswordAuthenticationToken.authenticated(PRINCIPAL, null, AUTHORITIES);
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            // Not saved to the session and not left on the thread: this authenticates one request.
            SecurityContextHolder.clearContext();
        }
    }

    /** The token from either accepted header, matching the style of the MCP endpoints. */
    private static String presented(HttpServletRequest request) {
        String bearer = ServiceAccountTokenFilter.bearer(request);
        if (bearer != null) return bearer;
        String header = request.getHeader("X-Concentus-Shell-Token");
        return header == null || header.isBlank() ? null : header.trim();
    }

    /** Constant time, so a wrong token cannot be narrowed down by how long it took to refuse. */
    private boolean matches(String candidate) {
        if (candidate == null) return false;
        return MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.UTF_8));
    }
}
