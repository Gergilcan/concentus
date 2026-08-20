package com.concentus.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Makes sure the administrator account named in configuration exists.
 *
 * <p>For deployments nobody sits in front of: a container started by a pipeline cannot be asked
 * anything, so the first account comes from {@code CONCENTUS_ADMIN_EMAIL} and
 * {@code CONCENTUS_ADMIN_PASSWORD}. Where somebody <em>is</em> in front of it, the first launch
 * asks instead — an installation with no accounts offers a setup screen, and what it creates is
 * an account whose password its owner chose and nobody else has seen.
 *
 * <p><b>Nothing is generated here.</b> An account created with a password printed into a log file
 * is an account whose password is in a log file, and on a desktop install nobody reads that log —
 * so the "recoverable" part of the trade never happened, and what remained was a live
 * administrator credential sitting in plain text.
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

        if (adminEmail.isBlank()) {
            // Nothing configured, so nothing to provision. On an installation with no accounts the
            // first launch asks for one; on one that has them, this is simply not its business.
            if (accounts.countUsers() == 0) {
                log.info("No accounts yet — the first launch will ask for one. Set "
                        + "CONCENTUS_ADMIN_EMAIL and CONCENTUS_ADMIN_PASSWORD instead where "
                        + "nobody is there to be asked.");
            }
            return;
        }

        String email = adminEmail.trim();
        if (accounts.findByEmail(email).isPresent()) {
            handleExisting(email);
            return;
        }
        create(email);
    }

    private void create(String email) {
        if (adminPassword.isBlank()) {
            log.error("CONCENTUS_ADMIN_EMAIL is set to {} but CONCENTUS_ADMIN_PASSWORD is empty. "
                    + "No account was created — set a password, or leave both unset and let the "
                    + "first launch ask for one.", email);
            return;
        }
        String password = adminPassword;
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
        log.info("Created the administrator account for {}.", email);
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
}
