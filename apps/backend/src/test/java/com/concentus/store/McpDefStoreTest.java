package com.concentus.store;

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
        store = new McpDefStore(dataDir.toString(), new ObjectMapper());
    }

    @Test
    void savingWithNoIdAssignsOneWithTheMcpPrefix() {
        McpDef saved = store.save(new McpDef(null, "GitHub", "https://mcp.example.com", "GH_TOKEN"));

        assertThat(saved.id()).startsWith("mcp_");
        assertThat(store.get(saved.id())).contains(saved);
    }

    @Test
    void savingWithAnExistingIdUpdatesInPlaceRatherThanCreatingANewRecord() {
        McpDef saved = store.save(new McpDef(null, "GitHub", "https://a.example.com", null));
        McpDef updated = store.save(new McpDef(saved.id(), "GitHub v2", "https://b.example.com", null));

        assertThat(updated.id()).isEqualTo(saved.id());
        assertThat(store.list()).hasSize(1);
        assertThat(store.list().get(0).name()).isEqualTo("GitHub v2");
    }

    @Test
    void listIsSortedByNameCaseInsensitively() {
        store.save(new McpDef(null, "zebra", "https://z.example.com", null));
        store.save(new McpDef(null, "Apple", "https://a.example.com", null));

        List<String> names = store.list().stream().map(McpDef::name).toList();
        assertThat(names).containsExactly("Apple", "zebra");
    }

    @Test
    void deleteRemovesTheRecord() {
        McpDef saved = store.save(new McpDef(null, "GitHub", "https://a.example.com", null));

        assertThat(store.delete(saved.id())).isTrue();
        assertThat(store.get(saved.id())).isEmpty();
        assertThat(store.delete(saved.id())).isFalse(); // already gone
    }

    @Test
    void getForAnUnknownIdIsEmpty() {
        assertThat(store.get("mcp_doesnotexist")).isEmpty();
    }
}
