package com.concentus.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Puts an account into this browser's session, and records that it may come back to it.
 *
 * <p>One place for the four ways in — a password, a provider, the first-run setup, and the
 * account switcher — because the parts that must not differ between them are exactly the ones
 * easy to forget: a fresh session id so a session fixed beforehand cannot be reused, the cookie
 * that survives a restart, and the device attachment the switcher rests on.
 */
@Component
class BrowserSignIn {

    /** Issues the cookie that survives a restart of this backend. */
    private final PersistentTokenBasedRememberMeServices rememberMe;
    /** The accounts this browser has already signed into, so switching between them costs a click. */
    private final DeviceAccountStore devices;
    private final int rememberMeDays;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    BrowserSignIn(PersistentTokenBasedRememberMeServices rememberMe, DeviceAccountStore devices,
                  @Value("${app.auth.remember-me-days:30}") int rememberMeDays) {
        this.rememberMe = rememberMe;
        this.devices = devices;
        this.rememberMeDays = rememberMeDays;
    }

    /**
     * Signs {@code account} in on a proof this process already holds — a provider's answer, or
     * the setup form seconds ago — and attaches it to the browser.
     *
     * @return what the client is told: who it now is
     */
    Map<String, Object> signIn(Accounts.UserAccount account, HttpServletRequest request,
                               HttpServletResponse response) {
        ConcentusUserDetails principal = ConcentusUserDetails.of(account);
        establish(authenticationFor(principal), request, response);
        attach(account, request, response);
        return identity(principal);
    }

    /** A fresh session holding {@code authentication}, and the cookie that outlives this process. */
    void establish(Authentication authentication, HttpServletRequest request,
                   HttpServletResponse response) {
        // A brand-new session id after authenticating, so a session fixed before sign-in can't be
        // reused afterwards.
        HttpSession existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        request.getSession(true);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        // Signing in once means signing in once: the cookie outlives this process, so an update or
        // a reboot is an invisible reconnection rather than a login screen.
        rememberMe.loginSuccess(request, response, authentication);
    }

    /**
     * Records that this browser has proved it may be {@code account}, so it may come back to
     * them without proving it again — which is the whole of what the switcher is allowed to do.
     */
    void attach(Accounts.UserAccount account, HttpServletRequest request, HttpServletResponse response) {
        devices.attach(DeviceCookie.ensure(request, response, rememberMeDays), account);
    }

    /** An already-proved principal as the authentication a session holds. */
    static Authentication authenticationFor(ConcentusUserDetails principal) {
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    /** What every sign-in answers with. */
    static Map<String, Object> identity(ConcentusUserDetails user) {
        return Map.of("userId", user.userId(), "email", user.email(),
                "organizationId", user.organizationId(), "role", user.role());
    }
}
