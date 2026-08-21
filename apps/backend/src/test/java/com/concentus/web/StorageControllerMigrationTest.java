package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.model.StorageSettings;
import com.concentus.secrets.LegacySecrets;
import com.concentus.store.SchemaMigrator;
import com.concentus.store.StorageSettingsStore;
import com.concentus.store.TestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which database a migration reads, and which one it writes.
 *
 * <p>The copy itself is covered by {@code StorageMigratorTest} against two real databases; what is
 * decided here is the direction — the part somebody can get wrong in a way that would open a second
 * PostgreSQL on a data directory another one is already serving.
 */
class StorageControllerMigrationTest {

    /**
     * An organization context that answers "yes" to requireAdmin.
     *
     * <p>These tests are about the direction of a copy, not about who may ask for one — which is
     * covered where it belongs, by the filter chain and by requireAdmin's own tests.
     */
    private static OrgContext adminContext() {
        return new OrgContext("default") {
            @Override
            public boolean isAdmin() {
                return true;
            }

            @Override
            public String requireOrganizationId() {
                return "default";
            }
        };
    }

    /** A key of no consequence: nothing in this test reads a sealed value back. */
    private static LegacySecrets cipher() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) raw[i] = (byte) i;
        return new LegacySecrets(java.util.Base64.getEncoder().encodeToString(raw));
    }

    private static StorageController controllerOn(DataSource live, String mode, Path dataDir) {
        StorageSettingsStore settings = new StorageSettingsStore(
                dataDir.toString(), new ObjectMapper(), cipher());
        StorageSettings active = "external".equals(mode)
                ? new StorageSettings("external", "jdbc:postgresql://elsewhere/db", "someone", null)
                : StorageSettings.embedded();
        ObjectProvider<StorageSettings> provider = new ObjectProvider<>() {
            @Override
            public StorageSettings getObject() {
                return active;
            }

            @Override
            public StorageSettings getIfAvailable() {
                return active;
            }
        };
        return new StorageController(settings, provider, live, adminContext(),
                dataDir.toString(), 17);
    }

    /**
     * Asking for "the embedded database" while the application is running on it is the same
     * database, and must reuse the live connection — starting a second server on a data directory
     * PostgreSQL is already serving is the one genuinely destructive mistake available here.
     */
    @Test
    @SuppressWarnings("unchecked")
    void the_embedded_side_is_the_live_connection_when_the_app_runs_on_it(@TempDir Path dataDir) {
        DataSource live = TestDatabase.freshDatabase("direction_1");
        assertThat(SchemaMigrator.migrate(live)).isTrue();
        new JdbcTemplate(live).update(
                "insert into resources (kind, id, json, updated_at) values (?,?,?,?)",
                "flow", "flow_1", "{\"name\":\"Here already\"}", 1L);

        Map<String, Object> contents = controllerOn(live, "embedded", dataDir).contents("embedded");

        // Answered instantly from the open connection: no second server, no data directory opened.
        assertThat((List<Map<String, Object>>) contents.get("tables"))
                .anySatisfy(t -> assertThat(t).containsEntry("table", "resources").containsEntry("rows", 1L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_active_side_is_whatever_this_process_is_running_on(@TempDir Path dataDir) {
        DataSource live = TestDatabase.freshDatabase("direction_2");
        assertThat(SchemaMigrator.migrate(live)).isTrue();

        Map<String, Object> contents = controllerOn(live, "external", dataDir).contents("active");

        assertThat((List<Map<String, Object>>) contents.get("tables")).isNotEmpty();
    }

    // A server build has no embedded database to go back to, and says so rather than trying to
    // start PostgreSQL on a directory that was never one.
    @Test
    void a_deployment_with_no_embedded_database_says_so(@TempDir Path dataDir) {
        DataSource live = TestDatabase.freshDatabase("direction_3");
        assertThat(SchemaMigrator.migrate(live)).isTrue();
        StorageSettingsStore settings = new StorageSettingsStore(
                dataDir.toString(), new ObjectMapper(), cipher());
        StorageSettings external = new StorageSettings(
                "external", "jdbc:postgresql://elsewhere/db", "someone", null);
        StorageController controller = new StorageController(settings, new ObjectProvider<>() {
            @Override
            public StorageSettings getObject() {
                return external;
            }

            @Override
            public StorageSettings getIfAvailable() {
                return external;
            }
        }, live, adminContext(), "", 17);

        assertThatThrownBy(() -> controller.contents("embedded"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no embedded database");
    }
}
