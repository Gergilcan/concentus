package com.concentus.secrets;

import com.concentus.store.SchemaMigrator;
import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a stored credential actually is, in the table.
 *
 * <p>Worth pinning down precisely because it reverses what this class used to promise. Values are
 * written as they are typed, so the protection around them is the database's own access control
 * and nothing else — a backup of this table is a backup of every password in it. Asserting the
 * plaintext is how that stays a decision somebody made rather than something that drifted.
 */
class CredentialStoreStorageTest {

    private static CredentialStore storeOn(String databaseName, JdbcTemplate[] out) {
        DataSource ds = TestDatabase.freshDatabase(databaseName);
        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        out[0] = jdbc;
        CredentialStore store = new CredentialStore(jdbc, new LegacySecrets(""));
        store.init();
        return store;
    }

    @Test
    void a_credential_is_written_as_it_was_typed() {
        JdbcTemplate[] jdbc = new JdbcTemplate[1];
        CredentialStore store = storeOn("cred_plain", jdbc);

        var created = store.create("local", "Resend", CredentialStore.Kind.API_TOKEN, "re_live_abc123");

        assertThat(jdbc[0].queryForObject("select secret from credentials where id = ?",
                String.class, created.id())).isEqualTo("re_live_abc123");
        assertThat(store.reveal("local", created.id())).contains("re_live_abc123");
    }

    // No key, no keyring, no environment variable: the store is usable the moment its table is
    // there. A missing key used to make it report itself unavailable, which described the symptom
    // and hid the cause.
    @Test
    void the_store_needs_nothing_but_its_table() {
        JdbcTemplate[] jdbc = new JdbcTemplate[1];
        CredentialStore store = storeOn("cred_nokey", jdbc);

        assertThat(store.isAvailable()).isTrue();
    }

    // Updating goes through the same path, so a value re-entered after a failed decryption
    // replaces the old ciphertext rather than sitting next to it.
    @Test
    void re_entering_a_value_replaces_whatever_was_there() {
        JdbcTemplate[] jdbc = new JdbcTemplate[1];
        CredentialStore store = storeOn("cred_update", jdbc);
        var created = store.create("local", "Resend", CredentialStore.Kind.API_TOKEN, "old");

        store.updateSecret("local", created.id(), "re_live_new");

        assertThat(jdbc[0].queryForObject("select secret from credentials where id = ?",
                String.class, created.id())).isEqualTo("re_live_new");
    }
}
