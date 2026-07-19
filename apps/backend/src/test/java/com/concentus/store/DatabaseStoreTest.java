package com.concentus.store;

import com.concentus.model.DatabaseDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DatabaseStore}: round-trips a {@link DatabaseDef} through a per-test temp
 * dir, verifying id assignment (the {@code db_} prefix) and that its {@code label} drives sorting.
 */
class DatabaseStoreTest {

    @TempDir
    Path dataDir;

    private DatabaseStore store;

    @BeforeEach
    void setUp() {
        store = new DatabaseStore(dataDir.toString(), new ObjectMapper());
    }

    @Test
    void savingWithNoIdAssignsOneWithTheDbPrefixAndPersistsAllFields() {
        DatabaseDef saved = store.save(
                new DatabaseDef(null, "Orders DB", "jdbc:postgresql://host/orders", "user", "WIREJ_DB_ORDERS"));

        assertThat(saved.id()).startsWith("db_");
        DatabaseDef reloaded = store.get(saved.id()).orElseThrow();
        assertThat(reloaded.label()).isEqualTo("Orders DB");
        assertThat(reloaded.jdbcUrl()).isEqualTo("jdbc:postgresql://host/orders");
        assertThat(reloaded.username()).isEqualTo("user");
        assertThat(reloaded.passwordEnv()).isEqualTo("WIREJ_DB_ORDERS");
    }

    @Test
    void listIsSortedByLabel() {
        store.save(new DatabaseDef(null, "Zeta", "jdbc:postgresql://z/db", null, null));
        store.save(new DatabaseDef(null, "Alpha", "jdbc:postgresql://a/db", null, null));

        assertThat(store.list()).extracting(DatabaseDef::label).containsExactly("Alpha", "Zeta");
    }

    @Test
    void deleteRemovesTheRecordAndIsIdempotent() {
        DatabaseDef saved = store.save(new DatabaseDef(null, "Orders DB", "jdbc:postgresql://host/orders", null, null));

        assertThat(store.delete(saved.id())).isTrue();
        assertThat(store.list()).isEmpty();
        assertThat(store.delete(saved.id())).isFalse();
    }
}
