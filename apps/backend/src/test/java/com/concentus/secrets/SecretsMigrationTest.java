package com.concentus.secrets;

import com.concentus.store.SchemaMigrator;
import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning a database full of encrypted values into one that can simply be read.
 *
 * <p>The half that matters is the half that fails. Some rows were sealed by this installation and
 * convert; others were sealed by whichever machine filled a shared database, and no key here will
 * ever open them. Those must survive untouched — the value may still be recoverable from the
 * machine that wrote it, and a migration that tidied them away would destroy the only copy.
 */
class SecretsMigrationTest {

    private static String randomKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    /** Seals a value the way the version that encrypted did, so the fixture is the real format. */
    private static String sealWith(String base64Key, String plaintext) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES"),
                    new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return LegacySecrets.PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Fixture(JdbcTemplate jdbc, String ours, String theirs) {
    }

    private static Fixture on(String databaseName) {
        DataSource ds = TestDatabase.freshDatabase(databaseName);
        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        return new Fixture(new JdbcTemplate(ds), randomKey(), randomKey());
    }

    private static void credential(JdbcTemplate jdbc, String id, String label, String secret) {
        jdbc.update("""
                insert into credentials (id, organization_id, label, kind, secret, hint,
                  created_at, updated_at) values (?,?,?,?,?,?,?,?)
                """, id, "local", label, "api_token", secret, "hint", 1L, 1L);
    }

    private static String secretOf(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("select secret from credentials where id = ?", String.class, id);
    }

    @Test
    void what_this_installation_can_open_is_rewritten_in_the_clear() {
        Fixture f = on("secrets_convert");
        credential(f.jdbc(), "c1", "Resend", sealWith(f.ours(), "re_live_token"));

        new SecretsMigration(f.jdbc(), new LegacySecrets(f.ours())).convert();

        assertThat(secretOf(f.jdbc(), "c1")).isEqualTo("re_live_token");
    }

    // The row from the other machine. Untouched is the only safe answer: this installation cannot
    // read it, but the one that wrote it can, and that is where the value still lives.
    @Test
    void what_it_cannot_open_is_left_exactly_as_it_was() {
        Fixture f = on("secrets_stranded");
        String sealed = sealWith(f.theirs(), "somebody-elses-token");
        credential(f.jdbc(), "c1", "Google Ads", sealed);

        new SecretsMigration(f.jdbc(), new LegacySecrets(f.ours())).convert();

        assertThat(secretOf(f.jdbc(), "c1")).isEqualTo(sealed);
    }

    @Test
    void a_mixed_database_converts_the_half_it_can() {
        Fixture f = on("secrets_mixed");
        credential(f.jdbc(), "c1", "Resend", sealWith(f.ours(), "mine"));
        credential(f.jdbc(), "c2", "Google Ads", sealWith(f.theirs(), "theirs"));
        credential(f.jdbc(), "c3", "Already plain", "typed-in-yesterday");

        new SecretsMigration(f.jdbc(), new LegacySecrets(f.ours())).convert();

        assertThat(secretOf(f.jdbc(), "c1")).isEqualTo("mine");
        assertThat(secretOf(f.jdbc(), "c2")).startsWith(LegacySecrets.PREFIX);
        assertThat(secretOf(f.jdbc(), "c3")).isEqualTo("typed-in-yesterday");
    }

    // Runs on every start, so the pass over an already-converted database must be a no-op rather
    // than something that mangles values that merely look unusual.
    @Test
    void running_again_changes_nothing() {
        Fixture f = on("secrets_idempotent");
        credential(f.jdbc(), "c1", "Resend", sealWith(f.ours(), "v1:not-really-sealed"));
        SecretsMigration migration = new SecretsMigration(f.jdbc(), new LegacySecrets(f.ours()));

        migration.convert();
        String afterFirst = secretOf(f.jdbc(), "c1");
        migration.convert();

        assertThat(afterFirst).isEqualTo("v1:not-really-sealed");
        assertThat(secretOf(f.jdbc(), "c1")).isEqualTo(afterFirst);
    }

    // No key at all is the normal state from now on: nothing to convert, and nothing damaged.
    @Test
    void an_installation_with_no_old_key_leaves_everything_alone() {
        Fixture f = on("secrets_nokey");
        String sealed = sealWith(f.ours(), "unreachable");
        credential(f.jdbc(), "c1", "Resend", sealed);

        new SecretsMigration(f.jdbc(), new LegacySecrets("")).convert();

        assertThat(secretOf(f.jdbc(), "c1")).isEqualTo(sealed);
    }
}
