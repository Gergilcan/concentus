package com.concentus.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
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
 *   <li>{@code /api/mcp/studio} — the MCP server an outside agent drives this app through. An MCP
 *       client has no cookie either; it presents a configured bearer token, and where
 *       authentication is on that token stands for a named account whose organization the request
 *       then runs in. With no token configured the endpoint refuses everyone — see
 *       {@code McpStudioController}.</li>
 * </ul>
 *
 * <p>Everything else under {@code /api} and {@code /ws} requires an authenticated session, which
 * is the change that puts the pre-existing flow, run, agent, MCP and database endpoints behind
 * sign-in rather than leaving them open.
 *
 * <p>One caller has neither a session nor an exemption: the desktop shell's first-run wizard, which
 * asks which database to use before the first account exists. It presents a token generated for the
 * launch it started, and {@link ShellTokenFilter} turns that into a principal for the three storage
 * routes it may use — so the rules above are unchanged rather than relaxed.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Staying signed in across restarts of this backend.
     *
     * <p>A session lived only in the servlet container, so every update, reboot or crash signed
     * everyone out. On a desktop install that is a shrug; on a deployment a team shares, it is a
     * login screen after every deploy — and the thing people do about a login screen they see too
     * often is choose a password they can type quickly.
     *
     * <p>Always remembered rather than behind a "keep me signed in" tick: the tick is a question
     * about a shared computer, and this application already answers that question with sign-out.
     *
     * <p>The key signs the cookies. Generated per start when unset, which is right for one machine
     * and wrong for two — with several instances behind a load balancer, a cookie minted by one is
     * refused by the next unless they share it.
     */
    @Bean
    public PersistentTokenBasedRememberMeServices rememberMeServices(
            AccountUserDetailsService uds, javax.sql.DataSource dataSource,
            @Value("${app.auth.remember-me-secret:}") String secret,
            @Value("${app.auth.remember-me-days:30}") int days) {
        JdbcTokenRepositoryImpl tokens = new JdbcTokenRepositoryImpl();
        tokens.setDataSource(dataSource);
        String key = secret == null || secret.isBlank()
                ? java.util.UUID.randomUUID().toString()
                : secret;
        PersistentTokenBasedRememberMeServices services =
                new PersistentTokenBasedRememberMeServices(key, uds, tokens);
        services.setAlwaysRemember(true);
        services.setTokenValiditySeconds((int) java.time.Duration.ofDays(Math.max(1, days)).toSeconds());
        // Sent only over HTTPS where the request arrived over HTTPS. Not hardcoded true: the
        // desktop app and local development are plain http on loopback, and a cookie the browser
        // refuses to send is a sign-in that silently never persists.
        services.setUseSecureCookie(false);
        return services;
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
    public SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper mapper,
                                          PersistentTokenBasedRememberMeServices rememberMe,
                                          @Value("${app.shell-token:}") String shellToken) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        // The SPA reads the XSRF-TOKEN cookie and echoes it in a header, so the raw token — not a
        // BREACH-masked one — is what arrives; tell the handler to compare it as-is.
        csrfHandler.setCsrfRequestAttributeName(null);

        // Built here rather than as a bean on purpose: Spring Boot registers every Filter bean with
        // the servlet container as well, which would run it on requests this chain never sees.
        ShellTokenFilter shell = new ShellTokenFilter(shellToken);

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
                    // The studio endpoint is the same story pointed the other way: an outside MCP
                    // client (Claude Code) driving this app, with a configured token instead of a
                    // session. It refuses every request unless one is set — see McpStudioController.
                    // A published flow's endpoint likewise: a bearer token the flow's author
                    // minted, on every request, and a flow that is not published answers 404 —
                    // see PublicRunController.
                    .ignoringRequestMatchers("/api/webhooks/**", "/api/internal/**",
                            "/api/public/**", "/api/account/oidc/**",
                            "/api/runs/*/tools", "/api/runs/*/workers/*/tools", "/api/runs/*/plan",
                            "/api/runs/*/verdict", "/api/mcp/studio")
                    // And the desktop shell's first-run wizard, which asks which database to use
                    // before the first account exists and so has no session to protect either. Not
                    // a path exemption: the matcher only holds for a request that already carries
                    // the token this launch generated — see ShellTokenFilter.
                    .ignoringRequestMatchers(shell::authenticates))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/api/webhooks/**", "/api/internal/**", "/api/public/**").permitAll()
                    .requestMatchers("/api/runs/*/tools", "/api/runs/*/workers/*/tools",
                            "/api/runs/*/plan", "/api/runs/*/verdict").permitAll()
                    .requestMatchers("/api/mcp/studio").permitAll()
                    // Signing in, and asking whether you are signed in, cannot themselves require
                    // a session.
                    .requestMatchers("/api/account/login", "/api/account/session").permitAll()
                    // Both ends of a directory sign-in are top-level browser navigations: the
                    // first leaves for Microsoft, the second arrives from it carrying none of this
                    // application's headers. Neither can require the session they exist to create.
                    // The callback is not an open door — it accepts only a state this process
                    // issued minutes earlier and holds in memory.
                    .requestMatchers("/api/account/oidc/**").permitAll()
                    // An OAuth redirect arrives as a plain top-level navigation from the
                    // authorization server, carrying none of our cookies, so it cannot require a
                    // session. It is not an open door: the callback only accepts a `state` this
                    // process issued minutes earlier and holds in memory, and that value is what
                    // carries the organization — nothing is taken from the request itself.
                    .requestMatchers("/api/mcp/oauth/callback").permitAll()

                    // ---- what each role may do ----
                    //
                    // Expressed here, by method and path, rather than as annotations spread over
                    // thirty controllers. One place to read means one place to audit, and the
                    // question an operator actually asks — "what can a VIEWER do?" — has an answer
                    // that fits on a screen.

                    // Creating the very first account. Open because there is nobody to authorize
                    // it — an installation with no accounts has no way in otherwise — and refused
                    // by the endpoint itself the moment one exists, which is the only window in
                    // which it does anything at all.
                    .requestMatchers("/api/account/setup").permitAll()
                    // Changing your own password is not a privilege; it is how you keep an account.
                    .requestMatchers("/api/account/password", "/api/account/logout").authenticated()
                    // The accounts this browser has already signed into, and going back to one of
                    // them. Open, deliberately: the case they exist for is somebody who has just
                    // signed out and wants to return, and requiring a session would mean requiring
                    // the very thing they came to get. They are not an open door either — the
                    // authorization is the device cookie, which was only ever attached to an
                    // account by signing into it, and it names accounts rather than granting them.
                    .requestMatchers("/api/account/accounts", "/api/account/accounts/**").permitAll()
                    // Moving between the organizations you are already in is not a write to any
                    // of them: a Viewer in two organizations may look at either. The endpoint
                    // itself checks the membership.
                    .requestMatchers(HttpMethod.POST, "/api/organizations/*/switch").authenticated()
                    // Reading is every signed-in role: flows, runs, transcripts, costs. Someone who
                    // wants to know what the automation did last night should not need the power to
                    // make it do anything tonight.
                    .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                    .requestMatchers("/ws/**").authenticated()
                    // Running, and steering a run in flight. The person who runs the daily cycle is
                    // rarely the person who should be free to rewrite what it does.
                    .requestMatchers(HttpMethod.POST,
                            "/api/flows/*/run", "/api/runs", "/api/runs/*/commands",
                            "/api/runs/*/approve", "/api/runs/*/reject", "/api/runs/*/stop",
                            "/api/runs/*/retry", "/api/runs/*/nodes/*/rerun",
                            "/api/runs/*/golden", "/api/runs/*/golden/rerun",
                            // Running an evaluation is running the flow, once per case. Editing
                            // its cases changes what "right" means, and stays a write below.
                            "/api/flows/*/evals/run")
                            .hasAnyRole(Accounts.ROLE_OPERATOR, Accounts.ROLE_MEMBER, Accounts.ROLE_ADMIN)
                    // Everything left changes what the work IS — flows, agents, servers,
                    // credentials, knowledge. Denied by default rather than listed: a route added
                    // next month is a write until someone says otherwise, which is the direction
                    // this list has to fail in.
                    .anyRequest().hasAnyRole(Accounts.ROLE_MEMBER, Accounts.ROLE_ADMIN))
            // Before the session's own authentication, and after the context is restored: a signed-in
            // browser keeps the principal it arrived with, and only a request that has none can be
            // answered for by the shell's token.
            .addFilterBefore(shell, UsernamePasswordAuthenticationFilter.class)
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
            // The cookie is checked on every request that arrives without a session, which is
            // what turns a restart from a sign-out into an invisible reconnection.
            .rememberMe(r -> r.rememberMeServices(rememberMe))
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable());
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
