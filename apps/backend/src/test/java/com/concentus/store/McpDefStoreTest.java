package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.model.McpDef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link McpDefStore}: round-trips an {@link McpDef} through a per-test temp dir
 * (never the real data dir) and checks its id assignment, sort order and delete.
 */
class McpDefStoreTest {

    @TempDir
    Path dataDir;

    private McpDefStore store;

    @BeforeEach
    void setUp() {
        TestDatabase.reset(TestDatabase.jdbc());
        store = new McpDefStore(TestDatabase.jdbc(), dataDir.toString(), new ObjectMapper(), new OrgContext("default"));
        store.init();
    }

    @Test
    void savingWithNoIdAssignsOneWithTheMcpPrefix() {
        McpDef saved = store.save(McpDef.http(null, "GitHub", "https://mcp.example.com", "GH_TOKEN", null));

        assertThat(saved.id()).startsWith("mcp_");
        assertThat(store.get(saved.id())).contains(saved);
    }

    @Test
    void savingWithAnExistingIdUpdatesInPlaceRatherThanCreatingANewRecord() {
        McpDef saved = store.save(McpDef.http(null, "GitHub", "https://a.example.com", null, null));
        McpDef updated = store.save(McpDef.http(saved.id(), "GitHub v2", "https://b.example.com", null, null));

        assertThat(updated.id()).isEqualTo(saved.id());
        assertThat(store.list()).hasSize(1);
        assertThat(store.list().get(0).name()).isEqualTo("GitHub v2");
    }

    @Test
    void listIsSortedByNameCaseInsensitively() {
        store.save(McpDef.http(null, "zebra", "https://z.example.com", null, null));
        store.save(McpDef.http(null, "Apple", "https://a.example.com", null, null));

        List<String> names = store.list().stream().map(McpDef::name).toList();
        assertThat(names).containsExactly("Apple", "zebra");
    }

    @Test
    void deleteRemovesTheRecord() {
        McpDef saved = store.save(McpDef.http(null, "GitHub", "https://a.example.com", null, null));

        assertThat(store.delete(saved.id())).isTrue();
        assertThat(store.get(saved.id())).isEmpty();
        assertThat(store.delete(saved.id())).isFalse(); // already gone
    }

    @Test
    void getForAnUnknownIdIsEmpty() {
        assertThat(store.get("mcp_doesnotexist")).isEmpty();
    }
}
