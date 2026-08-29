package com.concentus.secrets;

import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two organizations, one credentials table: what A stored, B cannot list, find or reveal.
 *
 * <p>In this package rather than beside the other isolation tests because the store's
 * {@code init()} is package-private, as the other credential tests already rely on.
 */
class CredentialOrganizationIsolationTest {

    @Test
    void credentials_of_one_organization_are_not_listed_found_or_revealed_from_the_other() {
        JdbcTemplate jdbc = TestDatabase.jdbc();
        jdbc.execute("delete from credentials");
        CredentialStore credentials = new CredentialStore(jdbc, new SecretCipher(""));
        credentials.init();
        CredentialStore.Credential ours = credentials.create("org_a", "Mailbox",
                CredentialStore.Kind.MAIL_PASSWORD, "s3cret");

        assertThat(credentials.list("org_b")).isEmpty();
        assertThat(credentials.find("org_b", ours.id())).isEmpty();
        assertThat(credentials.reveal("org_b", ours.id())).isEmpty();
        assertThat(credentials.reveal("org_a", ours.id())).contains("s3cret");
        // The same label is free in the other organization: uniqueness is per organization too.
        assertThat(credentials.create("org_b", "Mailbox", CredentialStore.Kind.MAIL_PASSWORD, "other").id())
                .isNotEqualTo(ours.id());
    }
}
