package com.concentus.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Makes sure the configured administrator account exists.
 *
 * <p>Without this, adding authentication would lock every existing install out of its own data:
 * the API now requires a session, and there would be no account to sign in with and no open
 * endpoint to create one. The first admin therefore comes from configuration, not from a public
 * registration endpoint — a self-hosted deployment must never expose "create the owner account"
 * to whoever reaches it first.
 *
 * <p><b>Keyed on the configured email, not on the table being empty.</b> The obvious version of
 * this — "do nothing if any user exists" — silently ignores configuration forever after the first
 * boot: set {@code CONCENTUS_ADMIN_EMAIL} a day late and the account is never created, sign-in
 * fails with "invalid email or password", and nothing anywhere says why. Provisioning the account
 * named in configuration whenever <em>that address</em> has none is both what an operator expects
 * and impossible to misread.
 *
 * <p>An existing account's password is never silently overwritten from configuration. Doing so
 * would mean anyone able to edit an environment variable could take over a live account, and would
 * quietly undo a password changed in the app. {@code CONCENTUS_ADMIN_PASSWORD_RESET=true} is the
 * explicit opt-in for the case where it has genuinely been lost.
 */
@Component
public class AccountBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AccountBootstrap.class);

    /** Used only when no email is configured at all. */
    private static final String FALLBACK_ADMIN_EMAIL = "admin@localhost";

    private final AccountStore accounts;
    private final PasswordEncoder encoder;
    private final OrgContext orgContext;
    private final String adminEmail;
    private final String adminPassword;
    private final boolean resetPassword;
    private final String organizationName;
    private final boolean desktop;

    public AccountBootstrap(AccountStore accounts, PasswordEncoder encoder, OrgContext orgContext,
                            @Value("${app.auth.bootstrap-admin-email:}") String adminEmail,
                            @Value("${app.auth.bootstrap-admin-password:}") String adminPassword,
                            @Value("${app.auth.bootstrap-admin-password-reset:false}") boolean resetPassword,
                            @Value("${app.organization-name:Concentus}") String organizationName,
                            @Value("${app.desktop:false}") boolean desktop) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.orgContext = orgContext;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.resetPassword = resetPassword;
        this.organizationName = organizationName;
        this.desktop = desktop;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        if (!accounts.isAvailable()) {
            log.error("Cannot bootstrap accounts: the database is unreachable. "
                    + "The API will reject every request until it is available.");
            return;
        }
        // The default organization must exist even with auth off, because integration rows are
        // written against it either way.
        accounts.createOrganization(orgContext.defaultOrganizationId(), organizationName);

        if (!orgContext.authEnabled()) {
            // In the desktop build this is the designed state, not a misconfiguration: the socket
            // is bound to loopback and the only user is the person at the machine, so warning on
            // every launch would train them to ignore warnings. On a server it stays loud.
            if (desktop) {
                log.info("Desktop build: the API is bound to loopback and runs without accounts.");
            } else {
                log.warn("app.auth.enabled=false — the API is UNAUTHENTICATED. "
                        + "Only use this for local development.");
            }
            return;
        }

        boolean emailConfigured = !adminEmail.isBlank();
        String email = emailConfigured ? adminEmail.trim() : FALLBACK_ADMIN_EMAIL;

        // With no email configured, only step in on a genuinely empty database — otherwise every
        // restart would recreate a fallback account the operator has deliberately removed.
        if (!emailConfigured && accounts.countUsers() > 0) return;

        if (accounts.findByEmail(email).isPresent()) {
            handleExisting(email);
            return;
        }
        create(email);
    }

    private void create(String email) {
        boolean generated = adminPassword.isBlank();
        String password = generated ? randomPassword() : adminPassword;
        try {
            // The same rule that governs changing a password later, so the account cannot be
            // created in a state from which it could not be re-set.
            Accounts.requireStrongPassword(password);
        } catch (IllegalArgumentException e) {
            log.error("""

                    ────────────────────────────────────────────────────────────────
                     Could not create the administrator account for {}:
                     {}
                     CONCENTUS_ADMIN_PASSWORD is {} character(s) long. Lengthen it and
                     restart; no account has been created, so sign-in will keep failing
                     until you do.
                    ────────────────────────────────────────────────────────────────
                    """, email, e.getMessage(), adminPassword.length());
            return;
        }

        accounts.createUser(orgContext.defaultOrganizationId(), email, encoder.encode(password),
                Accounts.ROLE_ADMIN);

        if (generated) {
            // A password in the startup log of a server you control is recoverable; a server
            // nobody can sign in to is not, and neither is a default password.
            log.warn("""

                    ────────────────────────────────────────────────────────────────
                     No administrator existed, so one was created:
                       email:    {}
                       password: {}
                     This password is shown once and is not stored in plain text.
                     Set CONCENTUS_ADMIN_EMAIL / CONCENTUS_ADMIN_PASSWORD to choose
                     your own, and change this one after signing in.
                    ────────────────────────────────────────────────────────────────
                    """, email, password);
        } else {
            log.info("Created the administrator account for {}.", email);
        }
    }

    private void handleExisting(String email) {
        if (!resetPassword) {
            // Said out loud, because "configuration present, nothing happened" is exactly the
            // silence that makes a failed sign-in impossible to diagnose.
            log.info("Administrator {} already exists; leaving its password unchanged. "
                    + "Set CONCENTUS_ADMIN_PASSWORD_RESET=true for one run to reset it.", email);
            return;
        }
        if (adminPassword.isBlank()) {
            log.error("CONCENTUS_ADMIN_PASSWORD_RESET is set but CONCENTUS_ADMIN_PASSWORD is empty; "
                    + "refusing to reset {} to a generated password nobody asked for.", email);
            return;
        }
        try {
            Accounts.requireStrongPassword(adminPassword);
        } catch (IllegalArgumentException e) {
            log.error("Cannot reset the password for {}: {}", email, e.getMessage());
            return;
        }
        accounts.findByEmail(email).ifPresent(user ->
                accounts.updatePassword(user.id(), encoder.encode(adminPassword)));
        log.warn("Reset the password for {} from configuration. "
                + "Remove CONCENTUS_ADMIN_PASSWORD_RESET so the next restart does not do it again.",
                email);
    }

    private static String randomPassword() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
