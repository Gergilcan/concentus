package com.concentus.secrets;

import com.concentus.store.SchemaMigrator;
import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a stored credential actually is, in the table, and what happens to one this installation
 * cannot read.
 *
 * <p>Two things are pinned here on purpose. Without a key the value is written as typed — the
 * protection is then the database's own access control and nothing else, and asserting the
 * plaintext keeps that a decision somebody made rather than something that drifted. And a row
 * sealed under another key is <em>locked</em>: listed, named, and repairable by typing the value
 * again — never an exception, and never "not configured", which is how the same row used to
 * present and what cost a morning.
 */
class CredentialStoreStorageTest {

    private record Fixture(JdbcTemplate jdbc, DataSource ds) {
    }

    private static Fixture on(String databaseName) {
        DataSource ds = TestDatabase.freshDatabase(databaseName);
        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        return new Fixture(new JdbcTemplate(ds), ds);
    }

    private static CredentialStore store(Fixture f, String key) {
        CredentialStore store = new CredentialStore(f.jdbc(), new SecretCipher(key));
        store.init();
        return store;
    }

    private static String secretOf(Fixture f, String id) {
        return f.jdbc().queryForObject("select secret from credentials where id = ?", String.class, id);
    }

    @Test
    void without_a_key_a_credential_is_written_as_it_was_typed() {
        Fixture f = on("cred_plain");
        CredentialStore store = store(f, "");

        var created = store.create("local", "Resend", CredentialStore.Kind.API_TOKEN, "re_live_abc123");

        assertThat(secretOf(f, created.id())).isEqualTo("re_live_abc123");
        assertThat(store.isEncrypting()).isFalse();
        assertThat(store.reveal("local", created.id())).contains("re_live_abc123");
    }

    @Test
    void with_a_key_a_credential_is_sealed_in_the_table_and_reveals_whole() {
        Fixture f = on("cred_sealed");
        CredentialStore store = store(f, SecretCipherTest.randomKey());

        var created = store.create("local", "Resend", CredentialStore.Kind.API_TOKEN, "re_live_abc123");

        assertThat(secretOf(f, created.id())).startsWith(SecretCipher.PREFIX).doesNotContain("re_live");
        assertThat(store.isEncrypting()).isTrue();
        assertThat(store.reveal("local", created.id())).contains("re_live_abc123");
        assertThat(store.find("local", created.id())).get()
                .extracting(CredentialStore.Credential::locked).isEqualTo(false);
    }

    // No key, no keyring, no environment variable: the store is usable the moment its table is
    // there. A missing key used to make it report itself unavailable, which described the symptom
    // and hid the cause.
    @Test
    void the_store_needs_nothing_but_its_table() {
        Fixture f = on("cred_nokey");

        assertThat(store(f, "").isAvailable()).isTrue();
    }

    /**
     * The row from the other machine — or from this one, before a reinstall lost its keyring.
     *
     * <p>Nothing throws: the list still comes back, the credential is in it under its name with
     * {@code locked} set, and revealing it answers empty. The database and the flows that point
     * at this id are untouched; what is asked of the person is one value, not a rebuild.
     */
    @Test
    void a_credential_sealed_under_another_key_is_listed_as_locked_and_never_throws() {
        Fixture f = on("cred_locked");
        var created = store(f, SecretCipherTest.randomKey())
                .create("local", "Google Ads", CredentialStore.Kind.API_TOKEN, "somebody-elses-token");

        CredentialStore here = store(f, "");

        assertThat(here.list("local")).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo(created.id());
            assertThat(c.label()).isEqualTo("Google Ads");
            assertThat(c.locked()).isTrue();
        });
        assertThat(here.reveal("local", created.id())).isEmpty();
        assertThat(here.revealForExport("local", created.id())).isEmpty();
        // Untouched: the value may still be recoverable from the machine that wrote it.
        assertThat(secretOf(f, created.id())).startsWith(SecretCipher.PREFIX);
    }

    // The way out of locked: a new value goes under this installation's key, whatever the old row
    // was sealed with.
    @Test
    void re_entering_a_locked_value_unlocks_it() {
        Fixture f = on("cred_unlock");
        var created = store(f, SecretCipherTest.randomKey())
                .create("local", "Google Ads", CredentialStore.Kind.API_TOKEN, "old-machine");
        CredentialStore here = store(f, SecretCipherTest.randomKey());
        assertThat(here.find("local", created.id())).get()
                .extracting(CredentialStore.Credential::locked).isEqualTo(true);

        here.updateSecret("local", created.id(), "typed-again");

        assertThat(here.find("local", created.id())).get()
                .extracting(CredentialStore.Credential::locked).isEqualTo(false);
        assertThat(here.reveal("local", created.id())).contains("typed-again");
    }

    /**
     * A row written before this installation had a key: sealed the next time it is touched.
     *
     * <p>On reveal, because that is the moment the plaintext is in hand anyway; on rename, because
     * a save is a moment the row is being written. Either way the table converges on sealed
     * without anybody re-typing anything.
     */
    @Test
    void a_clear_row_is_sealed_lazily_when_a_key_has_since_appeared() {
        Fixture f = on("cred_lazy");
        var a = store(f, "").create("local", "Resend", CredentialStore.Kind.API_TOKEN, "re_live_aaaaaaa");
        var b = store(f, "").create("local", "Holded", CredentialStore.Kind.API_TOKEN, "hd_live_bbbbbbb");
        assertThat(secretOf(f, a.id())).isEqualTo("re_live_aaaaaaa");
        CredentialStore keyed = store(f, SecretCipherTest.randomKey());

        assertThat(keyed.reveal("local", a.id())).contains("re_live_aaaaaaa");
        keyed.rename("local", b.id(), "Holded (prod)");

        assertThat(secretOf(f, a.id())).startsWith(SecretCipher.PREFIX);
        assertThat(secretOf(f, b.id())).startsWith(SecretCipher.PREFIX);
        assertThat(keyed.reveal("local", a.id())).contains("re_live_aaaaaaa");
        assertThat(keyed.reveal("local", b.id())).contains("hd_live_bbbbbbb");
    }

    // Updating goes through the same path, so a value re-entered replaces the old one rather than
    // sitting next to it.
    @Test
    void re_entering_a_value_replaces_whatever_was_there() {
        Fixture f = on("cred_update");
        CredentialStore store = store(f, "");
        var created = store.create("local", "Resend", CredentialStore.Kind.API_TOKEN, "old");

        store.updateSecret("local", created.id(), "re_live_new");

        assertThat(secretOf(f, created.id())).isEqualTo("re_live_new");
    }

    /**
     * Restoring a with-secrets export: the file's value fills what is unusable here — a locked row
     * or a placeholder — and steps aside for a value this machine can already open.
     */
    @Test
    void an_imported_secret_replaces_a_locked_row_or_a_placeholder_but_not_a_real_value() {
        Fixture f = on("cred_import");
        var locked = store(f, SecretCipherTest.randomKey())
                .create("local", "Locked one", CredentialStore.Kind.API_TOKEN, "sealed-elsewhere");
        CredentialStore here = store(f, SecretCipherTest.randomKey());
        here.importPlaceholder("local", "cred_ph", "Placeholder one", CredentialStore.Kind.API_TOKEN);
        var real = here.create("local", "Real one", CredentialStore.Kind.API_TOKEN, "mine-and-usable");

        assertThat(here.importSecret("local", locked.id(), "Locked one", "api-token", "from-the-file"))
                .isEqualTo(CredentialStore.ImportOutcome.REPLACED);
        assertThat(here.importSecret("local", "cred_ph", "Placeholder one", "api-token", "filled-in"))
                .isEqualTo(CredentialStore.ImportOutcome.REPLACED);
        assertThat(here.importSecret("local", real.id(), "Real one", "api-token", "older-copy"))
                .isEqualTo(CredentialStore.ImportOutcome.KEPT);
        assertThat(here.importSecret("local", "cred_new", "New one", "api-token", "brand-new"))
                .isEqualTo(CredentialStore.ImportOutcome.CREATED);

        assertThat(here.reveal("local", locked.id())).contains("from-the-file");
        assertThat(here.reveal("local", "cred_ph")).contains("filled-in");
        assertThat(here.reveal("local", real.id())).contains("mine-and-usable");
        assertThat(here.reveal("local", "cred_new")).contains("brand-new");
    }
}
