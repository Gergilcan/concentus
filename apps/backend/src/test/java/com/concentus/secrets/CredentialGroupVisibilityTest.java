package com.concentus.secrets;

import com.concentus.auth.Accounts;
import com.concentus.auth.ConcentusUserDetails;
import com.concentus.auth.OrgContext;
import com.concentus.groups.GroupContext;
import com.concentus.groups.GroupStore;
import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A credential scoped to a group: listed and found by the group's members and the admins, by
 * nobody else — and still revealed to a run, which has no principal and must be able to use it.
 */
class CredentialGroupVisibilityTest {

    private static final String ORG = "org_a";

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(String userId, String role) {
        ConcentusUserDetails user = new ConcentusUserDetails(userId, ORG, userId + "@x.test", "hash", role, true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }

    @Test
    void a_groups_credential_is_seen_by_its_members_and_admins_only_and_revealed_to_a_run() {
        JdbcTemplate jdbc = TestDatabase.jdbc();
        jdbc.execute("delete from credentials");
        jdbc.execute("delete from group_memberships");
        jdbc.execute("delete from groups");
        GroupStore groups = new GroupStore(jdbc);
        groups.init();
        String group = groups.create(ORG, "Platform", null, null).id();
        groups.addMember(group, "usr_alice", false);
        CredentialStore credentials = new CredentialStore(jdbc, new SecretCipher(""));
        credentials.init();
        credentials.setGroupContext(new GroupContext(new OrgContext(ORG), groups));

        signIn("usr_admin", Accounts.ROLE_ADMIN);
        CredentialStore.Credential open = credentials.create(ORG, "Mailbox", CredentialStore.Kind.MAIL_PASSWORD, "s3cret");
        CredentialStore.Credential scoped = credentials.create(ORG, "Linear token", CredentialStore.Kind.API_TOKEN, "tok-1234567890");
        assertThat(credentials.assignGroup(ORG, scoped.id(), group)).isTrue();
        assertThat(credentials.list(ORG)).extracting(CredentialStore.Credential::label).containsExactly("Linear token", "Mailbox");
        assertThat(credentials.find(ORG, scoped.id()).orElseThrow().groupId()).isEqualTo(group);
        assertThat(credentials.groupOf(ORG, scoped.id())).contains(group);
        assertThat(credentials.groupOf(ORG, open.id())).isEmpty();

        signIn("usr_alice", Accounts.ROLE_MEMBER);
        assertThat(credentials.list(ORG)).hasSize(2);
        assertThat(credentials.find(ORG, scoped.id())).isPresent();

        signIn("usr_bob", Accounts.ROLE_MEMBER);
        assertThat(credentials.list(ORG)).extracting(CredentialStore.Credential::label).containsExactly("Mailbox");
        assertThat(credentials.find(ORG, scoped.id())).isEmpty();
        assertThat(credentials.find(ORG, open.id())).isPresent();

        // The run-time path is unfiltered: a run of the group's flow, on a thread with no
        // principal, still opens the value.
        SecurityContextHolder.clearContext();
        assertThat(credentials.reveal(ORG, scoped.id())).contains("tok-1234567890");
        assertThat(credentials.list(ORG)).hasSize(2);
    }
}
