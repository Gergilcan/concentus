package com.concentus.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                    .ignoringRequestMatchers("/api/webhooks/**", "/api/internal/**"))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/webhooks/**", "/api/internal/**").permitAll()
                    // Signing in, and asking whether you are signed in, cannot themselves require
                    // a session.
                    .requestMatchers("/api/account/login", "/api/account/session").permitAll()
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

    /** Probes and static SPA assets stay reachable in both modes. */
    @Bean
    public SecurityFilterChain publicSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher("/actuator/**", "/", "/index.html", "/assets/**", "/favicon.ico")
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                    .requestMatchers("/actuator/**").denyAll()
                    .anyRequest().permitAll());
        return http.build();
    }

    static void writeError(HttpServletResponse res, ObjectMapper mapper, HttpStatus status, String message) {
        try {
            res.setStatus(status.value());
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(res.getOutputStream(), Map.of("error", message));
        } catch (Exception ignored) {
            res.setStatus(status.value());
        }
    }
}
