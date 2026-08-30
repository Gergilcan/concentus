package com.concentus.runners;

import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The runners table against the real migration. */
class RunnerStoreTest {

    private JdbcTemplate jdbc;
    private RunnerStore store;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.jdbc();
        jdbc.update("delete from runners");
        store = new RunnerStore(jdbc);
        assertThat(store.isAvailable()).isTrue();
    }

    @Test
    void a_runner_is_created_listed_by_name_and_found_in_its_organization_only() {
        String hash = RunnerTokens.hash(RunnerTokens.mint());
        Runner nas = store.create("org_a", "nas", Runner.SCOPE_ORGANIZATION, null, null, hash, "admin@a.test");
        store.create("org_a", "Alice laptop", Runner.SCOPE_USER, null, "usr_alice", RunnerTokens.hash("crn_b"), "alice@a.test");
        store.create("org_b", "nas", Runner.SCOPE_ORGANIZATION, null, null, RunnerTokens.hash("crn_c"), null);

        assertThat(nas.id()).startsWith(Runner.ID_PREFIX).hasSize(15);
        assertThat(nas.createdAt()).isPositive();
        assertThat(nas.lastSeenAt()).isNull();
        assertThat(nas.revoked()).isFalse();
        assertThat(store.list("org_a")).extracting(Runner::name).containsExactly("Alice laptop", "nas");
        assertThat(store.find("org_a", nas.id())).isPresent();
        assertThat(store.find("org_b", nas.id())).isEmpty();
        assertThat(store.findById(nas.id())).isPresent();
        assertThat(store.findByTokenHash(hash)).get().extracting(Runner::id).isEqualTo(nas.id());
        assertThat(store.findByTokenHash(RunnerTokens.hash("crn_nope"))).isEmpty();
    }

    @Test
    void a_name_is_unique_per_organization_case_blind() {
        store.create("org_a", "NAS", Runner.SCOPE_ORGANIZATION, null, null, RunnerTokens.hash("crn_1"), null);

        assertThatThrownBy(() -> store.create("org_a", "nas", Runner.SCOPE_ORGANIZATION, null, null,
                RunnerTokens.hash("crn_2"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void rename_revoke_touch_and_delete() {
        Runner r = store.create("org_a", "nas", Runner.SCOPE_GROUP, "gr_1", null, RunnerTokens.hash("crn_1"), null);
        store.create("org_a", "other", Runner.SCOPE_ORGANIZATION, null, null, RunnerTokens.hash("crn_2"), null);

        assertThat(store.rename("org_a", r.id(), "storage box")).isTrue();
        assertThat(store.rename("org_b", r.id(), "hijacked")).isFalse();
        assertThatThrownBy(() -> store.rename("org_a", r.id(), "OTHER")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.find("org_a", r.id())).get().extracting(Runner::name, Runner::groupId)
                .containsExactly("storage box", "gr_1");

        store.touchLastSeen(r.id(), 1234L);
        assertThat(store.find("org_a", r.id())).get().extracting(Runner::lastSeenAt).isEqualTo(1234L);

        assertThat(store.revoke("org_a", r.id(), 5000L)).isTrue();
        // Revoking twice changes nothing: the first timestamp is the fact.
        assertThat(store.revoke("org_a", r.id(), 6000L)).isFalse();
        assertThat(store.find("org_a", r.id())).get().extracting(Runner::revokedAt).isEqualTo(5000L);
        // The token still resolves to the row, so the handshake can say "revoked" rather than "unknown".
        assertThat(store.findByTokenHash(RunnerTokens.hash("crn_1"))).isPresent();

        assertThat(store.delete("org_b", r.id())).isFalse();
        assertThat(store.delete("org_a", r.id())).isTrue();
        assertThat(store.list("org_a")).extracting(Runner::name).containsExactly("other");
    }

    @Test
    void tokens_have_the_shape_the_handshake_recognises() {
        String token = RunnerTokens.mint();
        assertThat(token).startsWith("crn_").hasSize(44);
        assertThat(RunnerTokens.looksLike(token)).isTrue();
        assertThat(RunnerTokens.looksLike("csa_" + token.substring(4))).isFalse();
        assertThat(RunnerTokens.looksLike(token + "x")).isFalse();
        assertThat(RunnerTokens.hash(token)).hasSize(64).isEqualTo(RunnerTokens.hash(token));
        assertThat(RunnerTokens.mint()).isNotEqualTo(token);
    }
}
