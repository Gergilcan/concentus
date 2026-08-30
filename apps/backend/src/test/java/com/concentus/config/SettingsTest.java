package com.concentus.config;

import com.concentus.auth.OrgContext;
import com.concentus.secrets.SecretCipher;
import com.concentus.store.SchemaMigrator;
import com.concentus.store.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a setting is, right now.
 *
 * <p>Three places in a fixed order — what somebody set in the application, what the deployment was
 * started with, what the code does — and the order is the whole feature. A container started by a
 * pipeline has to keep taking its environment; the person running the desktop app has to be able
 * to change the same thing from a form, because there the environment is computed by the shell and
 * there is no file for them to edit.
 */
class SettingsTest {

    /** No key: what a server that never set CONCENTUS_SECRET_KEY runs with. */
    private static SecretCipher cipher() {
        return new SecretCipher("");
    }

    private static SecretCipher keyed() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return new SecretCipher(Base64.getEncoder().encodeToString(raw));
    }

    private record Fixture(Settings settings, SettingsStore store, MockEnvironment environment) {
    }

    private static Fixture on(String databaseName) {
        DataSource ds = TestDatabase.freshDatabase(databaseName);
        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        SettingsStore store = new SettingsStore(new JdbcTemplate(ds), cipher());
        store.load();
        MockEnvironment environment = new MockEnvironment();
        return new Fixture(new Settings(store, environment, new OrgContext("default")), store,
                environment);
    }

    @Test
    void with_nothing_set_anywhere_the_fallback_stands() {
        Fixture f = on("settings_1");

        assertThat(f.settings().number("runs.max-concurrent", 4)).isEqualTo(4);
    }

    @Test
    void the_deployments_configuration_is_used_when_nobody_has_changed_it() {
        Fixture f = on("settings_2");
        f.environment().setProperty("runs.max-concurrent", "9");

        assertThat(f.settings().number("runs.max-concurrent", 4)).isEqualTo(9);
    }

    // The reason this exists: on the desktop the environment is computed by the shell, so a value
    // configured there is a value nobody can change. What is set in the app wins.
    @Test
    void what_somebody_set_in_the_application_wins() {
        Fixture f = on("settings_3");
        f.environment().setProperty("runs.max-concurrent", "9");

        f.store().put("default", "runs.max-concurrent", "20", false, "gerard@tecnovent.com");

        assertThat(f.settings().number("runs.max-concurrent", 4)).isEqualTo(20);
    }

    /**
     * Clearing a field has to mean "go back to what it was", not "set it to empty". Without that
     * there is no way back to the deployment's own value once a field has been touched.
     */
    @Test
    void clearing_a_setting_lets_the_configured_value_stand_again() {
        Fixture f = on("settings_4");
        f.environment().setProperty("runs.max-concurrent", "9");
        f.store().put("default", "runs.max-concurrent", "20", false, null);

        f.store().put("default", "runs.max-concurrent", "", false, null);

        assertThat(f.settings().number("runs.max-concurrent", 4)).isEqualTo(9);
    }

    /**
     * A secret setting without a key is stored as it was typed.
     *
     * <p>Deliberate, and its cost is the assertion's whole point: what protects this value on an
     * installation with no CONCENTUS_SECRET_KEY is who can reach the database, and nothing else
     * — so the test states the exposure rather than leaving somebody to infer it from an absence.
     */
    @Test
    void without_a_key_a_secret_is_stored_as_typed_and_reads_back_here() {
        DataSource ds = TestDatabase.freshDatabase("settings_5");
        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SettingsStore store = new SettingsStore(jdbc, cipher());
        store.load();

        store.put("default", "pricing.input-usd-per-mtok", "sk-not-a-real-key", true, null);

        assertThat(store.get("default", "pricing.input-usd-per-mtok")).contains("sk-not-a-real-key");
        assertThat(jdbc.queryForObject(
                "select value from settings where key = 'pricing.input-usd-per-mtok'", String.class))
                .isEqualTo("sk-not-a-real-key");
        // Still FLAGGED secret, which is what keeps it out of the API's responses and the UI's
        // fields. The flag was never about the encryption.
        assertThat(jdbc.queryForObject(
                "select secret from settings where key = 'pricing.input-usd-per-mtok'", Boolean.class))
                .isTrue();
    }

    @Test
    void with_a_key_a_secret_is_sealed_in_the_table_and_reads_back_whole() {
        DataSource ds = TestDatabase.freshDatabase("settings_9");
        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SettingsStore store = new SettingsStore(jdbc, keyed());
        store.load();

        store.put("default", "approvals.telegram.bot-token", "123:abc", true, null);
        store.put("default", "runs.max-concurrent", "7", false, null);

        assertThat(store.get("default", "approvals.telegram.bot-token")).contains("123:abc");
        assertThat(jdbc.queryForObject(
                "select value from settings where key = 'approvals.telegram.bot-token'", String.class))
                .startsWith(SecretCipher.PREFIX).doesNotContain("123:abc");
        // Only secrets: a number sealed in the table would be a number nobody can query.
        assertThat(jdbc.queryForObject(
                "select value from settings where key = 'runs.max-concurrent'", String.class))
                .isEqualTo("7");
    }

    /**
     * The row from a machine with another key. Unset rather than ciphertext — handing the sealed
     * text to a provider would fail far from here as a wrong token — and flagged locked so the
     * screen can ask for it again. Nothing throws: the settings load, and every other setting is
     * still there.
     */
    @Test
    void a_secret_sealed_under_another_key_reads_as_unset_and_locked_without_throwing() {
        DataSource ds = TestDatabase.freshDatabase("settings_10");
        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SettingsStore elsewhere = new SettingsStore(jdbc, keyed());
        elsewhere.load();
        elsewhere.put("default", "approvals.telegram.bot-token", "123:abc", true, null);
        elsewhere.put("default", "runs.max-concurrent", "7", false, null);

        SettingsStore here = new SettingsStore(jdbc, cipher());
        here.load();

        assertThat(here.isAvailable()).isTrue();
        assertThat(here.get("default", "approvals.telegram.bot-token")).isEmpty();
        assertThat(here.isLocked("default", "approvals.telegram.bot-token")).isTrue();
        assertThat(here.get("default", "runs.max-concurrent")).contains("7");

        // Entering it again is the way out, and the new value goes under this installation's key.
        here.put("default", "approvals.telegram.bot-token", "456:def", true, null);
        assertThat(here.isLocked("default", "approvals.telegram.bot-token")).isFalse();
        assertThat(here.get("default", "approvals.telegram.bot-token")).contains("456:def");
    }

    // A secret written before this installation had a key is sealed the first time it is loaded
    // with one: the value is in hand, so there is nothing to wait for.
    @Test
    void a_clear_secret_is_sealed_on_the_first_load_with_a_key() {
        DataSource ds = TestDatabase.freshDatabase("settings_11");
        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SettingsStore plain = new SettingsStore(jdbc, cipher());
        plain.load();
        plain.put("default", "approvals.telegram.bot-token", "123:abc", true, null);

        SettingsStore keyed = new SettingsStore(jdbc, keyed());
        keyed.load();

        assertThat(jdbc.queryForObject(
                "select value from settings where key = 'approvals.telegram.bot-token'", String.class))
                .startsWith(SecretCipher.PREFIX);
        assertThat(keyed.get("default", "approvals.telegram.bot-token")).contains("123:abc");
    }

    @Test
    void a_setting_that_is_not_a_number_falls_back_rather_than_throwing() {
        Fixture f = on("settings_6");
        f.store().put("default", "runs.max-concurrent", "quite a lot", false, null);

        // Read inside a run somebody is watching, so a parse failure must not take anything down.
        assertThat(f.settings().number("runs.max-concurrent", 4)).isEqualTo(4);
    }

    @Test
    void one_organizations_limits_are_not_anothers() {
        Fixture f = on("settings_7");
        f.store().put("other-company", "runs.max-concurrent", "99", false, null);

        assertThat(f.settings().number("runs.max-concurrent", 4)).isEqualTo(4);
        assertThat(f.store().get("other-company", "runs.max-concurrent")).contains("99");
    }

    /**
     * One constructor, and the reason is a bug that cost an afternoon.
     *
     * <p>There was a private no-arg constructor behind {@link Settings#none()}. With two
     * constructors and neither annotated, Spring falls back to the no-arg one — so the bean it
     * built had a null store, every lookup quietly returned its caller's fallback, and the whole
     * settings feature was inert with no error anywhere: run limits ignored, prices ignored, and a
     * sign-in provider somebody had just registered still reported as unconfigured.
     *
     * <p>Asserted structurally because the symptom is silence. Nothing throws, nothing logs, and
     * the only visible effect is a setting that does not apply — which reads as "my change did not
     * save" and sends you looking in the wrong place.
     */
    @Test
    void settings_has_exactly_one_constructor_so_spring_cannot_pick_the_wrong_one() {
        assertThat(Settings.class.getDeclaredConstructors()).hasSize(1);
    }

    // The same fault, caught from the outside: a Settings built the way Spring builds it must
    // actually read the store rather than answering with the fallback.
    @Test
    void a_wired_settings_reads_what_is_stored_rather_than_the_fallback() {
        Fixture f = on("settings_8");
        f.store().put("default", "runs.max-concurrent", "17", false, null);

        assertThat(f.settings().number("runs.max-concurrent", 4)).isEqualTo(17);
        assertThat(f.settings().installationWide("runs.max-concurrent", "4")).isEqualTo("17");
    }

    // The catalogue is what the settings screen renders and what the API will accept; a key in one
    // and not the other is a field that saves nothing or a row nothing reads.
    @Test
    void every_catalogued_setting_names_a_real_configuration_key() {
        assertThat(SettingsCatalog.all()).isNotEmpty();
        assertThat(SettingsCatalog.all()).allSatisfy(def -> {
            assertThat(def.key()).doesNotStartWith("app.auth.oidc.");
            assertThat(def.label()).isNotBlank();
            assertThat(def.help()).isNotBlank();
        });
        assertThat(SettingsCatalog.isKnown("runs.max-concurrent")).isTrue();
        assertThat(SettingsCatalog.isKnown("app.data-dir")).isFalse();
    }

    // ---- per group: a group's override, else the organization's, else the deployment's ----

    @Test
    void a_groups_override_beats_the_organizations_which_beats_the_deployments() {
        Fixture f = on("settings_groups_1");
        f.environment().setProperty("workers.retries", "1");
        f.store().put("default", "workers.retries", "2", false, null);

        assertThat(f.settings().forGroup("default", "gr_1", "workers.retries")).contains("2");
        f.store().replaceGroupSettings("default", "gr_1", java.util.Map.of("workers.retries", "5"));
        assertThat(f.settings().forGroup("default", "gr_1", "workers.retries")).contains("5");
        assertThat(f.settings().forGroup("default", "gr_2", "workers.retries")).contains("2");
        assertThat(f.settings().forGroup("default", null, "workers.retries")).contains("2");
        // What a run reads, resolved for its own scope.
        assertThat(f.settings().forRun("default", "gr_1").number("workers.retries", 9)).isEqualTo(5);
        assertThat(f.settings().forRun("default", "gr_2").number("workers.retries", 9)).isEqualTo(2);
        assertThat(f.settings().forRun("default", "gr_1").get("workers.timeout-seconds", "900")).isEqualTo("900");
        // And the organization's own reads are unchanged by a group's override.
        assertThat(f.settings().number("workers.retries", 9)).isEqualTo(2);
    }

    // "Replaces": a key absent from the new map is back to inherited, and blanks clear.
    @Test
    void replacing_a_groups_settings_forgets_what_the_new_map_does_not_name() {
        Fixture f = on("settings_groups_2");
        f.store().replaceGroupSettings("default", "gr_1", java.util.Map.of("workers.retries", "5",
                "local.permission-mode", "plan"));
        assertThat(f.store().groupSettings("gr_1")).hasSize(2);

        f.store().replaceGroupSettings("default", "gr_1", java.util.Map.of("workers.timeout-seconds", "60",
                "workers.retries", " "));

        assertThat(f.store().groupSettings("gr_1")).containsExactly(java.util.Map.entry("workers.timeout-seconds", "60"));
        assertThat(f.settings().forGroup("default", "gr_1", "workers.retries")).isEmpty();
        f.store().clearGroup("gr_1");
        assertThat(f.store().groupSettings("gr_1")).isEmpty();
    }

    // A stubbed Settings has no store and answers every scope with the one value it knows — what a
    // test about a limit wants, and why forRun must not silently answer "unset" there.
    @Test
    void a_stubbed_settings_answers_every_scope_with_what_it_knows() {
        Settings stub = Settings.of(java.util.Map.of("workers.retries", "3"));

        assertThat(stub.forRun("org", "gr_1").number("workers.retries", 1)).isEqualTo(3);
        assertThat(stub.forGroup("org", "gr_1", "workers.retries")).contains("3");
        assertThat(Settings.none().forRun("org", "gr_1").number("workers.retries", 1)).isEqualTo(1);
    }

    // The keys a group may override are exactly the ones whose reader takes the run's scope.
    @Test
    void the_group_scoped_keys_are_the_ones_read_per_run() {
        assertThat(SettingsCatalog.groupScoped()).extracting(SettingDef::key).containsExactlyInAnyOrder(
                "usage.weekly-allowance-usd", "workers.timeout-seconds", "workers.retries", "local.permission-mode");
        assertThat(SettingsCatalog.byKey("workers.max-concurrent").orElseThrow().groupScoped()).isFalse();
        assertThat(SettingsCatalog.byKey("workers.retries").orElseThrow().restartRequired()).isFalse();
    }
}
