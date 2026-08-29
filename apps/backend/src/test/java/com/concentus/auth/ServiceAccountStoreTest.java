package com.concentus.auth;

import com.concentus.store.SchemaMigrator;
import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service_accounts table, against a real PostgreSQL through the real migration.
 *
 * <p>What matters here is the scoping — every write takes the organization as well as the id —
 * and that the one unscoped read, by token hash, resolves to exactly the row it should.
 */
class ServiceAccountStoreTest {

    private static ServiceAccountStore storeOn(DataSource ds) {
        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        return new ServiceAccountStore(new JdbcTemplate(ds));
    }

    @Test
    void a_row_is_found_by_its_hash_and_listed_for_its_organization_only() {
        ServiceAccountStore store = storeOn(TestDatabase.freshDatabase("sa_1"));
        String token = ServiceAccount.mintToken();

        ServiceAccount created = store.create("org_a", "  nightly-report ", "OPERATOR", ServiceAccount.hash(token),
                "gerard@tecnovent.com");
        store.create("org_b", "other", "VIEWER", ServiceAccount.hash(ServiceAccount.mintToken()), null);

        assertThat(store.findByTokenHash(ServiceAccount.hash(token))).get()
                .extracting(ServiceAccount::id, ServiceAccount::name, ServiceAccount::organizationId,
                        ServiceAccount::createdBy, ServiceAccount::lastUsedAt, ServiceAccount::revokedAt)
                .containsExactly(created.id(), "nightly-report", "org_a", "gerard@tecnovent.com", null, null);
        assertThat(store.findByTokenHash(ServiceAccount.hash(ServiceAccount.mintToken()))).isEmpty();
        assertThat(store.list("org_a")).extracting(ServiceAccount::name).containsExactly("nightly-report");
        assertThat(store.list("org_b")).extracting(ServiceAccount::name).containsExactly("other");
        assertThat(store.find(created.id(), "org_b")).isEmpty();
    }

    @Test
    void revoking_keeps_the_row_and_stops_counting_it() {
        ServiceAccountStore store = storeOn(TestDatabase.freshDatabase("sa_2"));
        ServiceAccount a = store.create("org_a", "a", "VIEWER", ServiceAccount.hash(ServiceAccount.mintToken()), null);
        store.create("org_a", "b", "VIEWER", ServiceAccount.hash(ServiceAccount.mintToken()), null);
        assertThat(store.countActive("org_a")).isEqualTo(2);

        assertThat(store.revoke(a.id(), "org_a", 42L)).isTrue();
        assertThat(store.revoke(a.id(), "org_a", 43L)).isFalse();   // already revoked: nothing to do

        assertThat(store.countActive("org_a")).isEqualTo(1);
        assertThat(store.list("org_a")).hasSize(2);
        assertThat(store.find(a.id(), "org_a")).get().extracting(ServiceAccount::revokedAt).isEqualTo(42L);
        // The hash still resolves — the filter reads revoked_at and refuses; the row is the audit.
        assertThat(store.findByTokenHash(a.tokenHash())).get().extracting(ServiceAccount::revokedAt).isEqualTo(42L);
    }

    // The boundary every write rests on: the organization is part of the address.
    @Test
    void another_organization_cannot_rename_or_revoke_a_row_by_guessing_its_id() {
        ServiceAccountStore store = storeOn(TestDatabase.freshDatabase("sa_3"));
        ServiceAccount a = store.create("org_a", "a", "VIEWER", ServiceAccount.hash(ServiceAccount.mintToken()), null);

        assertThat(store.rename(a.id(), "org_b", "stolen")).isFalse();
        assertThat(store.revoke(a.id(), "org_b", 1L)).isFalse();
        assertThat(store.rename(a.id(), "org_a", "renamed")).isTrue();

        assertThat(store.find(a.id(), "org_a")).get()
                .extracting(ServiceAccount::name, ServiceAccount::revokedAt).containsExactly("renamed", null);
    }

    @Test
    void last_used_is_recorded() {
        ServiceAccountStore store = storeOn(TestDatabase.freshDatabase("sa_4"));
        ServiceAccount a = store.create("org_a", "a", "VIEWER", ServiceAccount.hash(ServiceAccount.mintToken()), null);

        store.touchLastUsed(a.id(), 1_234L);

        assertThat(store.find(a.id(), "org_a")).get().extracting(ServiceAccount::lastUsedAt).isEqualTo(1_234L);
    }

    @Test
    void two_rows_cannot_share_a_hash() {
        ServiceAccountStore store = storeOn(TestDatabase.freshDatabase("sa_5"));
        String hash = ServiceAccount.hash(ServiceAccount.mintToken());
        store.create("org_a", "a", "VIEWER", hash, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.create("org_a", "b", "VIEWER", hash, null))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
}
