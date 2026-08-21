package com.concentus.service;

import com.concentus.auth.AccountStore;
import com.concentus.auth.Accounts;
import com.concentus.auth.ConcentusUserDetails;
import com.concentus.auth.OrgContext;
import com.concentus.model.KnowledgeDef;
import com.concentus.store.KnowledgeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who may read which base.
 *
 * <p>The test that matters most is the one about a run: a restriction enforced only at the API is
 * one flow away from meaningless, because the passages come back inside an answer rather than
 * through an endpoint.
 */
class KnowledgeAccessTest {

    private final KnowledgeStore store = mock(KnowledgeStore.class);
    private final OrgContext orgContext = mock(OrgContext.class);
    private final AccountStore accounts = mock(AccountStore.class);
    private KnowledgeAccess access;

    @BeforeEach
    void setUp() {
        access = new KnowledgeAccess(store, orgContext, accounts);
    }

    private static KnowledgeDef base(String minRole) {
        return new KnowledgeDef("kb1", "Salaries", "what people are paid", minRole);
    }

    private void signedInAs(String role) {
        when(orgContext.currentUser()).thenReturn(Optional.of(
                new ConcentusUserDetails("u1", "org", "someone@example.com", "hash", role, true)));
    }

    @Test
    void aBaseWithNoMinimumIsReadableByAnybody() {
        // Every base created before this field existed has no minimum, and must go on meaning what
        // it meant: an upgrade that locked people out of their own documents would be the worse
        // failure of the two.
        assertThat(access.mayRead(base(null), Accounts.ROLE_VIEWER)).isTrue();
        assertThat(access.mayRead(base("  "), Accounts.ROLE_VIEWER)).isTrue();
    }

    @Test
    void aRestrictedBaseNeedsTheRoleItNames() {
        assertThat(access.mayRead(base(Accounts.ROLE_MEMBER), Accounts.ROLE_VIEWER)).isFalse();
        assertThat(access.mayRead(base(Accounts.ROLE_MEMBER), Accounts.ROLE_OPERATOR)).isFalse();
        assertThat(access.mayRead(base(Accounts.ROLE_MEMBER), Accounts.ROLE_MEMBER)).isTrue();
        // The ladder, not an equality: an admin reads everything below them.
        assertThat(access.mayRead(base(Accounts.ROLE_MEMBER), Accounts.ROLE_ADMIN)).isTrue();
    }

    @Test
    void theRefusalNamesTheRestrictionRatherThanDenyingTheBaseExists() {
        when(store.get("kb1")).thenReturn(Optional.of(base(Accounts.ROLE_ADMIN)));
        signedInAs(Accounts.ROLE_VIEWER);

        assertThatThrownBy(() -> access.requireRead("kb1"))
                .isInstanceOf(KnowledgeAccess.AccessDenied.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    void aRunIsJudgedByWhoeverStartedItNotByWhoDrewTheFlow() {
        when(store.get("kb1")).thenReturn(Optional.of(base(Accounts.ROLE_MEMBER)));
        when(accounts.findByEmail("viewer@example.com")).thenReturn(Optional.of(
                new Accounts.UserAccount("u2", "org", "viewer@example.com", "hash",
                        Accounts.ROLE_VIEWER, true, 0L)));

        // The flow may have been drawn by an admin. The passages would arrive in the answer, which
        // is exactly the leak the API check alone would not catch.
        assertThat(access.mayRunRead("kb1", "viewer@example.com")).isFalse();
    }

    @Test
    void anEmailWithNoAccountBehindItGetsTheLowestRung() {
        when(store.get("kb1")).thenReturn(Optional.of(base(Accounts.ROLE_OPERATOR)));
        when(accounts.findByEmail(anyString())).thenReturn(Optional.empty());

        // Somebody who has left. Their old runs do not inherit reach they no longer have.
        assertThat(access.mayRunRead("kb1", "gone@example.com")).isFalse();
    }

    @Test
    void aRunNobodyStartedIsNotRestricted() {
        when(store.get("kb1")).thenReturn(Optional.of(base(Accounts.ROLE_ADMIN)));

        // A schedule, a webhook delivery, a flow started by another flow: there is no person to
        // restrict, and the run acts as the installation itself.
        assertThat(access.mayRunRead("kb1", null)).isTrue();
        assertThat(access.mayRunRead("kb1", "")).isTrue();
    }

    @Test
    void thePickerOffersOnlyWhatItCouldOpen() {
        when(store.list()).thenReturn(java.util.List.of(
                new KnowledgeDef("open", "Manuals", "", null),
                new KnowledgeDef("shut", "Salaries", "", Accounts.ROLE_ADMIN)));
        signedInAs(Accounts.ROLE_MEMBER);

        // Listing a base and then refusing it teaches people the application is broken rather than
        // that the base is restricted.
        assertThat(access.readable()).extracting(KnowledgeDef::id).containsExactly("open");
    }
}
