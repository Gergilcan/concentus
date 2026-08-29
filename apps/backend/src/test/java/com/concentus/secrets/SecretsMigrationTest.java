package com.concentus.secrets;

import com.concentus.store.SchemaMigrator;
import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup pass that brings a database up to what this installation's key can do.
 *
 * <p>The half that matters is the half it leaves alone. Some rows were sealed by this installation
 * and convert; others were sealed by whichever machine filled a shared database, and no key here
 * will ever open them. Those must survive untouched — the value may still be recoverable from the
 * machine that wrote it, and a migration that tidied them away would destroy the only copy.
 */
class SecretsMigrationTest {

    private record Fixture(JdbcTemplate jdbc, String ours, String theirs) {
    }

    private static Fixture on(String databaseName) {
        DataSource ds = TestDatabase.freshDatabase(databaseName);
        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        return new Fixture(new JdbcTemplate(ds), SecretCipherTest.randomKey(),
                SecretCipherTest.randomKey());
    }

    private static void credential(JdbcTemplate jdbc, String id, String label, String secret) {
        jdbc.update("""
                insert into credentials (id, organization_id, label, kind, secret, hint,
                  created_at, updated_at) values (?,?,?,?,?,?,?,?)
                """, id, "local", label, "api_token", secret, "hint", 1L, 1L);
    }

    private static void setting(JdbcTemplate jdbc, String key, String value) {
        jdbc.update("""
                insert into settings (organization_id, key, value, secret, updated_at, updated_by)
                values (?,?,?,?,?,?)
                """, "local", key, value, true, 1L, null);
    }

    private static String secretOf(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("select secret from credentials where id = ?", String.class, id);
    }

    @Test
    void with_a_key_clear_rows_are_sealed_and_still_open() {
        Fixture f = on("secrets_seal_clear");
        credential(f.jdbc(), "c1", "Resend", "typed-in-yesterday");
        setting(f.jdbc(), "approvals.telegram.bot-token", "123:abc");
        SecretCipher cipher = new SecretCipher(f.ours());

        new SecretsMigration(f.jdbc(), cipher).convert();

        String sealed = secretOf(f.jdbc(), "c1");
        assertThat(sealed).startsWith(SecretCipher.PREFIX);
        assertThat(cipher.open(sealed).plaintext()).isEqualTo("typed-in-yesterday");
        String setting = f.jdbc().queryForObject(
                "select value from settings where key = 'approvals.telegram.bot-token'", String.class);
        assertThat(setting).startsWith(SecretCipher.PREFIX);
        assertThat(cipher.open(setting).plaintext()).isEqualTo("123:abc");
    }

    // A database sealed before the change, with the key it was sealed with: carried to the
    // current marker, never written in the clear.
    @Test
    void with_a_key_legacy_rows_it_opens_are_rewritten_under_the_current_marker() {
        Fixture f = on("secrets_convert");
        credential(f.jdbc(), "c1", "Resend", SecretCipherTest.sealLegacy(f.ours(), "re_live_token"));
        SecretCipher cipher = new SecretCipher(f.ours());

        new SecretsMigration(f.jdbc(), cipher).convert();

        String sealed = secretOf(f.jdbc(), "c1");
        assertThat(sealed).startsWith(SecretCipher.PREFIX).doesNotContain("re_live");
        assertThat(cipher.open(sealed).plaintext()).isEqualTo("re_live_token");
    }

    // The row from the other machine. Untouched is the only safe answer: this installation cannot
    // read it, but the one that wrote it can, and that is where the value still lives.
    @Test
    void what_it_cannot_open_is_left_exactly_as_it_was() {
        Fixture f = on("secrets_stranded");
        String sealed = new SecretCipher(f.theirs()).wrap("somebody-elses-token");
        credential(f.jdbc(), "c1", "Google Ads", sealed);

        new SecretsMigration(f.jdbc(), new SecretCipher(f.ours())).convert();

        assertThat(secretOf(f.jdbc(), "c1")).isEqualTo(sealed);
    }

    @Test
    void a_mixed_database_converts_the_half_it_can() {
        Fixture f = on("secrets_mixed");
        String theirs = new SecretCipher(f.theirs()).wrap("theirs");
        credential(f.jdbc(), "c1", "Resend", SecretCipherTest.sealLegacy(f.ours(), "mine"));
        credential(f.jdbc(), "c2", "Google Ads", theirs);
        credential(f.jdbc(), "c3", "Already plain", "typed-in-yesterday");
        SecretCipher cipher = new SecretCipher(f.ours());

        new SecretsMigration(f.jdbc(), cipher).convert();

        assertThat(cipher.open(secretOf(f.jdbc(), "c1")).plaintext()).isEqualTo("mine");
        assertThat(secretOf(f.jdbc(), "c2")).isEqualTo(theirs);
        assertThat(cipher.open(secretOf(f.jdbc(), "c3")).plaintext()).isEqualTo("typed-in-yesterday");
        assertThat(secretOf(f.jdbc(), "c3")).startsWith(SecretCipher.PREFIX);
    }

    // Runs on every start, so the pass over an already-converted database must be a no-op rather
    // than something that re-seals every row with a fresh IV on each launch.
    @Test
    void running_again_changes_nothing() {
        Fixture f = on("secrets_idempotent");
        credential(f.jdbc(), "c1", "Resend", "typed");
        SecretsMigration migration = new SecretsMigration(f.jdbc(), new SecretCipher(f.ours()));

        migration.convert();
        String afterFirst = secretOf(f.jdbc(), "c1");
        migration.convert();

        assertThat(afterFirst).startsWith(SecretCipher.PREFIX);
        assertThat(secretOf(f.jdbc(), "c1")).isEqualTo(afterFirst);
    }

    // No key: a server that never set one. Nothing to seal, nothing damaged, and the sealed rows
    // stay sealed — they are locked, and the log names them.
    @Test
    void an_installation_with_no_key_leaves_everything_alone() {
        Fixture f = on("secrets_nokey");
        String sealed = new SecretCipher(f.ours()).wrap("unreachable");
        credential(f.jdbc(), "c1", "Resend", sealed);
        credential(f.jdbc(), "c2", "Holded", "typed");

        new SecretsMigration(f.jdbc(), new SecretCipher("")).convert();

        assertThat(secretOf(f.jdbc(), "c1")).isEqualTo(sealed);
        assertThat(secretOf(f.jdbc(), "c2")).isEqualTo("typed");
    }
}
