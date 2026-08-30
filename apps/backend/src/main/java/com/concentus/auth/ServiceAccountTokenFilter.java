package com.concentus.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Turns {@code Authorization: Bearer csa_…} into a signed-in service account, on any API route.
 *
 * <p>Runs before the session's own authentication and after the context is restored, exactly as
 * {@link ShellTokenFilter} does: a browser that arrives signed in keeps its principal, and only a
 * request that has none is answered for by the token. Unlike the shell's filter this one grants a
 * real principal — {@link ConcentusUserDetails} with the account's organization and role — because
 * that is the point: the request is then scoped and authorized like anyone else's, by the same
 * rules in {@code SecurityConfig}, with no route list of its own to keep in step.
 *
 * <p>Three properties worth stating:
 * <ul>
 *   <li>A presented token that resolves to nothing, or to a revoked row, is refused here with a
 *       401 rather than passed on as anonymous. Anonymous would reach the same answer on most
 *       routes, but not on {@code permitAll} ones — and a revoked token quietly working on those
 *       is a surprise nobody should have to reason about.</li>
 *   <li>The stored hash and the presented token's hash are compared in constant time after the
 *       lookup. The lookup is by equality already; the compare is what keeps the answer's timing
 *       from depending on which bytes differ.</li>
 *   <li>{@code last_used_at} is written at most once a minute per account. A pipeline polling a
 *       run every second is one row write a minute, not sixty.</li>
 * </ul>
 */
public class ServiceAccountTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ServiceAccountTokenFilter.class);

    /** How often {@code last_used_at} may be written per account. */
    static final long TOUCH_INTERVAL_MILLIS = 60_000;

    private final ServiceAccountStore store;
    private final ObjectMapper mapper;
    private final LongSupplier clock;
    /** Account id → the last time its {@code last_used_at} was written, for the throttle. */
    private final Map<String, Long> touched = new ConcurrentHashMap<>();

    public ServiceAccountTokenFilter(ServiceAccountStore store, ObjectMapper mapper) {
        this(store, mapper, System::currentTimeMillis);
    }

    /** With the clock injectable, so the once-a-minute rule is a test rather than a wait. */
    ServiceAccountTokenFilter(ServiceAccountStore store, ObjectMapper mapper, LongSupplier clock) {
        this.store = store;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Whether this request carries something shaped like one of our tokens.
     *
     * <p>Public because CSRF asks the same question: a bearer token is what stands in for the CSRF
     * token on these requests. A page in a browser cannot put an {@code Authorization} header on a
     * cross-site request without a preflight the server never approves, so the header itself is
     * the proof of intent the CSRF token exists to supply. Shape only, no lookup — the CSRF filter
     * runs before this one and must stay cheap; a token that merely looks right and resolves to
     * nothing is refused a moment later, by {@link #doFilterInternal}.
     */
    public boolean presentsToken(HttpServletRequest request) {
        return ServiceAccount.looksLikeToken(bearer(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String presented = bearer(request);
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null || !ServiceAccount.looksLikeToken(presented)) {
            chain.doFilter(request, response);
            return;
        }

        String hash = ServiceAccount.hash(presented);
        ServiceAccount account = store.findByTokenHash(hash).orElse(null);
        if (account == null || !MessageDigest.isEqual(
                account.tokenHash().getBytes(StandardCharsets.UTF_8), hash.getBytes(StandardCharsets.UTF_8))) {
            refuse(response, "Unknown service account token.");
            return;
        }
        if (account.revokedAt() != null) {
            log.info("Refused a revoked service account token ({}).", account.name());
            refuse(response, "This service account token has been revoked.");
            return;
        }

        touch(account);
        ConcentusUserDetails principal = account.principal();
        UsernamePasswordAuthenticationToken auth =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            // Not saved to the session and not left on the thread: a token authenticates one request.
            SecurityContextHolder.clearContext();
            // Nor may the request leave a session behind: anything downstream created during it
            // (a CSRF token store, say) would hand the caller a cookie that outlives the token.
            jakarta.servlet.http.HttpSession session = request.getSession(false);
            if (session != null && session.isNew()) session.invalidate();
        }
    }

    /** Writes {@code last_used_at} unless it was written for this account less than a minute ago. */
    private void touch(ServiceAccount account) {
        long now = clock.getAsLong();
        Long last = touched.get(account.id());
        if (last != null && now - last < TOUCH_INTERVAL_MILLIS) return;
        touched.put(account.id(), now);
        try {
            store.touchLastUsed(account.id(), now);
        } catch (RuntimeException e) {
            // A bookkeeping write must never refuse a request that authenticated.
            log.debug("Could not record last use of service account {}: {}", account.id(), e.getMessage());
        }
    }

    private void refuse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), Map.of("error", message));
    }

    /** The bearer token on the request, or null when there is none. */
    static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) return null;
        String trimmed = header.trim();
        if (trimmed.length() < 7 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = trimmed.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
