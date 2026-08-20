package com.concentus.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The two ends of signing in with a Microsoft work or school account.
 *
 * <p>Both are plain browser navigations rather than API calls the SPA makes: the first is a
 * redirect out to Microsoft, and the second arrives back from Microsoft carrying none of this
 * application's headers. Neither can require a session, which is why they are permitted in
 * {@link SecurityConfig} — and why the callback trusts nothing in the request except a {@code
 * state} value this process issued minutes earlier.
 */
@RestController
@RequestMapping("/api/account/oidc/microsoft")
public class MicrosoftSignInController {

    private final MicrosoftSignIn signIn;
    private final PersistentTokenBasedRememberMeServices rememberMe;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public MicrosoftSignInController(MicrosoftSignIn signIn,
                                     PersistentTokenBasedRememberMeServices rememberMe) {
        this.signIn = signIn;
        this.rememberMe = rememberMe;
    }

    /** Sends the browser to Microsoft. A link, not a fetch: the destination is another origin. */
    @GetMapping("/start")
    public void start(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!signIn.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Microsoft sign-in is not configured on this deployment.");
        }
        response.sendRedirect(signIn.authorizationUrl(baseOf(request)));
    }

    /**
     * Where Microsoft sends the person back.
     *
     * <p>Ends in a redirect to the application either way, because this is a top-level navigation
     * and there is nobody to read a JSON body. A refusal travels as a query parameter the sign-in
     * screen renders — the reason usually being a domain this deployment does not admit, which is
     * a sentence the person needs to see rather than a silent bounce back to the form.
     */
    @GetMapping("/callback")
    public void callback(@RequestParam(required = false) String code,
                         @RequestParam(required = false) String state,
                         @RequestParam(required = false) String error,
                         HttpServletRequest request, HttpServletResponse response) throws IOException {
        MicrosoftSignIn.Outcome outcome = signIn.complete(code, state, error);
        if (!outcome.ok()) {
            response.sendRedirect("/?signin_error="
                    + URLEncoder.encode(outcome.error(), StandardCharsets.UTF_8));
            return;
        }

        Accounts.UserAccount account = outcome.account();
        ConcentusUserDetails principal = ConcentusUserDetails.of(account);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());

        // A brand-new session id after authenticating, so a session fixed before sign-in cannot be
        // reused afterwards — the same rule the password path follows.
        HttpSession existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        request.getSession(true);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        // The cookie that survives a restart of this backend. Without it, signing in through a
        // directory would still mean signing in again after every deploy.
        rememberMe.loginSuccess(request, response, authentication);

        response.sendRedirect("/");
    }

    /**
     * The origin this request arrived on, which is what the redirect URI has to match.
     *
     * <p>Read from the request rather than configured, so the same build works on localhost, on a
     * LAN address and behind a domain without a property to keep in step with the Entra
     * registration. Forwarded headers are honoured because a reverse proxy is the normal shape of
     * a deployment that has a domain at all.
     */
    private static String baseOf(HttpServletRequest request) {
        String proto = header(request, "X-Forwarded-Proto", request.getScheme());
        String host = header(request, "X-Forwarded-Host", request.getServerName());
        String port = header(request, "X-Forwarded-Port", String.valueOf(request.getServerPort()));
        boolean defaultPort = ("https".equals(proto) && "443".equals(port))
                || ("http".equals(proto) && "80".equals(port));
        // A forwarded host may already carry the port; adding a second one produces a URI that
        // matches nothing in the registration and fails with a message about redirect_uri.
        boolean hostHasPort = host.contains(":");
        return proto + "://" + host + (defaultPort || hostHasPort ? "" : ":" + port);
    }

    private static String header(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) return fallback;
        // A proxy chain sends a list; the first entry is the client-facing one.
        int comma = value.indexOf(',');
        return (comma < 0 ? value : value.substring(0, comma)).trim();
    }
}
