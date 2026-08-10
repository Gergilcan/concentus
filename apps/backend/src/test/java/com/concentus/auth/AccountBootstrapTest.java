package com.concentus.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provisioning the administrator account.
 *
 * <p>These exist because the first version keyed on "the users table is empty", which meant that
 * setting {@code CONCENTUS_ADMIN_EMAIL} after the first boot did nothing at all — sign-in then
 * failed with "invalid email or password" and no log line anywhere explained why. The tests below
 * pin the corrected rule and the reasons for its edges.
 */
class AccountBootstrapTest {

    private static final String ORG = "default";
    private static final String CONFIGURED_EMAIL = "owner@empresa.example";
    private static final String GOOD_PASSWORD = "a-sufficiently-long-password";

    private AccountStore accounts;
    private PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountStore.class);
        when(accounts.isAvailable()).thenReturn(true);
        when(accounts.findByEmail(anyString())).thenReturn(Optional.empty());
        when(accounts.countUsers()).thenReturn(0L);
        encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenAnswer(i -> "hashed:" + i.getArgument(0));
    }

    private AccountBootstrap bootstrap(String email, String password, boolean reset) {
        return new AccountBootstrap(accounts, encoder, new OrgContext(ORG, true), email, password,
                reset, "Concentus", false);
    }

    private static Accounts.UserAccount existing(String email) {
        return new Accounts.UserAccount("usr_1", ORG, email, "hashed:old", Accounts.ROLE_ADMIN,
                true, 0L);
    }

    // ---- the bug this class exists for ----

    @Test
    void theConfiguredAdminIsCreatedEvenWhenOtherAccountsAlreadyExist() {
        // The original failure: a fallback account created on an earlier boot made the table
        // non-empty, so the real administrator was never provisioned and nothing said so.
        when(accounts.countUsers()).thenReturn(1L);
        when(accounts.findByEmail(CONFIGURED_EMAIL)).thenReturn(Optional.empty());

        bootstrap(CONFIGURED_EMAIL, GOOD_PASSWORD, false).bootstrap();

        verify(accounts).createUser(ORG, CONFIGURED_EMAIL, "hashed:" + GOOD_PASSWORD,
                Accounts.ROLE_ADMIN);
    }

    @Test
    void anAlreadyProvisionedAdminIsNotRecreated() {
        when(accounts.findByEmail(CONFIGURED_EMAIL)).thenReturn(Optional.of(existing(CONFIGURED_EMAIL)));

        bootstrap(CONFIGURED_EMAIL, GOOD_PASSWORD, false).bootstrap();

        verify(accounts, never()).createUser(anyString(), anyString(), anyString(), anyString());
    }

    // ---- password resets are opt-in ----

    @Test
    void anExistingPasswordIsNeverSilentlyOverwrittenFromConfiguration() {
        // Otherwise anyone able to edit an environment variable could take over a live account,
        // and a password changed in the app would be quietly undone on the next restart.
        when(accounts.findByEmail(CONFIGURED_EMAIL)).thenReturn(Optional.of(existing(CONFIGURED_EMAIL)));

        bootstrap(CONFIGURED_EMAIL, "a-different-long-password", false).bootstrap();

        verify(accounts, never()).updatePassword(anyString(), anyString());
    }

    @Test
    void anExplicitResetRewritesThePassword() {
        when(accounts.findByEmail(CONFIGURED_EMAIL)).thenReturn(Optional.of(existing(CONFIGURED_EMAIL)));

        bootstrap(CONFIGURED_EMAIL, GOOD_PASSWORD, true).bootstrap();

        verify(accounts).updatePassword("usr_1", "hashed:" + GOOD_PASSWORD);
    }

    @Test
    void aResetWithNoPasswordConfiguredDoesNothing() {
        // Resetting to a generated password nobody asked for would lock the owner out just as
        // effectively as the problem they were trying to fix.
        when(accounts.findByEmail(CONFIGURED_EMAIL)).thenReturn(Optional.of(existing(CONFIGURED_EMAIL)));

        bootstrap(CONFIGURED_EMAIL, "", true).bootstrap();

        verify(accounts, never()).updatePassword(anyString(), anyString());
    }

    // ---- password policy ----

    @Test
    void aPasswordShorterThanThePolicyCreatesNoAccount() {
        // Creating one anyway would leave an account whose password cannot be changed to anything
        // similar later, with no explanation of why.
        bootstrap(CONFIGURED_EMAIL, "Gerard44489", false).bootstrap();

        verify(accounts, never()).createUser(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void thePolicyIsTheSameOneThatGovernsChangingAPasswordLater() {
        assertThat(Accounts.MIN_PASSWORD_LENGTH).isEqualTo(12);
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> Accounts.requireStrongPassword("short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 12");
    }

    // ---- fallback behaviour ----

    @Test
    void withNoEmailConfiguredAFallbackAdminIsCreatedOnlyOnAnEmptyDatabase() {
        bootstrap("", "", false).bootstrap();

        verify(accounts).createUser(eq(ORG), eq("admin@localhost"), anyString(),
                eq(Accounts.ROLE_ADMIN));
    }

    @Test
    void withNoEmailConfiguredNothingIsRecreatedOnceAccountsExist() {
        // Otherwise every restart would resurrect a fallback account an operator had removed.
        when(accounts.countUsers()).thenReturn(1L);

        bootstrap("", "", false).bootstrap();

        verify(accounts, never()).createUser(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aGeneratedPasswordSatisfiesThePolicyItIsCreatedUnder() {
        bootstrap("", "", false).bootstrap();

        verify(accounts).createUser(eq(ORG), eq("admin@localhost"),
                org.mockito.ArgumentMatchers.argThat(hash ->
                        hash.substring("hashed:".length()).length() >= Accounts.MIN_PASSWORD_LENGTH),
                eq(Accounts.ROLE_ADMIN));
    }

    // ---- environment guards ----

    @Test
    void anUnreachableDatabaseCreatesNothingRatherThanFailingStartup() {
        when(accounts.isAvailable()).thenReturn(false);

        bootstrap(CONFIGURED_EMAIL, GOOD_PASSWORD, false).bootstrap();

        verify(accounts, never()).createOrganization(anyString(), anyString());
        verify(accounts, never()).createUser(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void theDefaultOrganizationIsCreatedEvenWithAuthDisabled() {
        // Integration rows are written against it either way.
        AccountBootstrap open = new AccountBootstrap(accounts, encoder, new OrgContext(ORG, false),
                CONFIGURED_EMAIL, GOOD_PASSWORD, false, "Concentus", false);

        open.bootstrap();

        verify(accounts).createOrganization(ORG, "Concentus");
        verify(accounts, never()).createUser(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void theEmailIsTrimmedBeforeUse() {
        // A trailing space in a .env file is invisible and would otherwise create an account
        // nobody can sign in to.
        bootstrap("  " + CONFIGURED_EMAIL + "  ", GOOD_PASSWORD, false).bootstrap();

        verify(accounts).createUser(ORG, CONFIGURED_EMAIL, "hashed:" + GOOD_PASSWORD,
                Accounts.ROLE_ADMIN);
    }
}
