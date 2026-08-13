package com.concentus.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.util.Map;

/**
 * Sign-in, session handling and which endpoints are reachable without one.
 *
 * <p>Three groups of endpoints are deliberately outside the session check, because each carries
 * its own credential that a browser session could not supply:
 *
 * <ul>
 *   <li>{@code /api/webhooks/**} — inbound provider callbacks. The generic webhook authenticates
 *       with an HMAC over the raw body; the Microsoft Graph one validates {@code clientState}
 *       against the subscription record.</li>
 *   <li>{@code /api/internal/**} — subscription renewal and delta sync, called by a scheduler or
 *       an external cron. Signed with a shared secret (see {@code InternalEndpointGuard}).</li>
 *   <li>{@code /actuator/health/**} — liveness/readiness probes.</li>
 * </ul>
 *
 * <p>Everything else under {@code /api} and {@code /ws} requires an authenticated session, which
 * is the change that puts the pre-existing flow, run, agent, MCP and database endpoints behind
 * sign-in rather than leaving them open.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AccountUserDetailsService uds, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(uds);
        provider.setPasswordEncoder(encoder);
        // Run the hash comparison even when no user matched, so response time doesn't reveal
        // whether an email address exists.
        provider.setHideUserNotFoundExceptions(true);
        return provider::authenticate;
    }

    /**
     * The normal chain. Sessions are the servlet container's own — no Spring Session dependency —
     * which is enough for the single-process deployment this project ships.
     */
    @Bean
    @ConditionalOnProperty(name = "app.auth.enabled", havingValue = "true", matchIfMissing = true)
    public SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper mapper) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        // The SPA reads the XSRF-TOKEN cookie and echoes it in a header, so the raw token — not a
        // BREACH-masked one — is what arrives; tell the handler to compare it as-is.
        csrfHandler.setCsrfRequestAttributeName(null);

        http
            .securityMatcher("/api/**", "/ws/**")
            .csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(csrfHandler)
                    // These authenticate with a provider signature or a shared secret rather than
                    // a session cookie, so there is no session for CSRF to protect and no browser
                    // able to supply a token.
                    // The run tools endpoints are called by local claude CLI processes, which have
                    // no session and no cookie; each authenticates every request with its own
                    // bearer token instead — per run for /tools, per WORKER for the fan-out's
                    // facade, the run's token again for the planner's /plan, and the verifier's
                    // own token for /verdict.
                    .ignoringRequestMatchers("/api/webhooks/**", "/api/internal/**",
                            "/api/runs/*/tools", "/api/runs/*/workers/*/tools", "/api/runs/*/plan",
                            "/api/runs/*/verdict"))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/webhooks/**", "/api/internal/**").permitAll()
                    .requestMatchers("/api/runs/*/tools", "/api/runs/*/workers/*/tools",
                            "/api/runs/*/plan", "/api/runs/*/verdict").permitAll()
                    // Signing in, and asking whether you are signed in, cannot themselves require
                    // a session.
                    .requestMatchers("/api/account/login", "/api/account/session").permitAll()
                    // An OAuth redirect arrives as a plain top-level navigation from the
                    // authorization server, carrying none of our cookies, so it cannot require a
                    // session. It is not an open door: the callback only accepts a `state` this
                    // process issued minutes earlier and holds in memory, and that value is what
                    // carries the organization — nothing is taken from the request itself.
                    .requestMatchers("/api/mcp/oauth/callback").permitAll()
                    .anyRequest().authenticated())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation(f -> f.migrateSession()))
            .exceptionHandling(e -> e
                    // A SPA wants a 401 to redirect on, not a login page redirect.
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                    .accessDeniedHandler((req, res, ex) -> writeError(res, mapper, HttpStatus.FORBIDDEN,
                            "You do not have permission to do that.")))
            .logout(l -> l.logoutUrl("/api/account/logout")
                    .logoutSuccessHandler((req, res, a) -> res.setStatus(HttpStatus.NO_CONTENT.value()))
                    .deleteCookies("JSESSIONID"))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable());
        return http.build();
    }

    /**
     * Escape hatch for local development and for the existing single-user installs this feature
     * lands on: {@code app.auth.enabled=false} leaves the API open exactly as it was before, and
     * {@link OrgContext} then resolves everything to the configured default organization.
     * Never use it on a reachable deployment.
     */
    @Bean
    @ConditionalOnProperty(name = "app.auth.enabled", havingValue = "false")
    public SecurityFilterChain openSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**", "/ws/**")
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Probes and static SPA assets stay reachable in both modes.
     *
     * <p>The desktop build additionally opens {@code POST /actuator/shutdown}, because there the
     * application is a child process of a window rather than a service: closing the window has to
     * stop it, and Windows offers no SIGTERM to do that with. The alternative is killing the
     * process, which skips Spring's shutdown hooks and loses in-flight run state instead of
     * flushing it. It stays denied everywhere else — on a reachable deployment this endpoint is a
     * one-request outage. What makes it defensible on the desktop is not the property below but
     * the loopback bind that comes with it ({@code server.address=127.0.0.1}).
     */
    @Bean
    public SecurityFilterChain publicSecurity(HttpSecurity http,
                                              @Value("${app.desktop:false}") boolean desktop) throws Exception {
        http.securityMatcher("/actuator/**", "/", "/index.html", "/assets/**", "/favicon.ico")
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/actuator/health/**", "/actuator/info").permitAll();
                if (desktop) {
                    // POST only: the endpoint answers nothing useful to a GET, and narrowing the
                    // grant to the verb that does something keeps it from being reachable by a
                    // stray navigation.
                    auth.requestMatchers(HttpMethod.POST, "/actuator/shutdown").permitAll();
                }
                auth.requestMatchers("/actuator/**").denyAll();
                auth.anyRequest().permitAll();
            });
        return http.build();
    }

    private static void writeError(HttpServletResponse res, ObjectMapper mapper, HttpStatus status,
                                   String message) {
        try {
            res.setStatus(status.value());
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(res.getOutputStream(), Map.of("error", message));
        } catch (Exception ignored) {
            res.setStatus(status.value());
        }
    }
}
