package com.concentus.store;

import com.concentus.auth.OrgContext;
import com.concentus.model.FlowGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link JsonStore}'s atomic-write and fault-tolerant-read behavior, exercised
 * through {@link FlowStore} (a plain, non-Spring instantiation pointed at a per-test temp dir).
 */
class JsonStoreTest {

    @TempDir
    Path dataDir;

    private FlowStore store;

    @BeforeEach
    void setUp() {
        // @TempDir field injection happens after construction, so the store (which needs the
        // resolved temp dir) must be built here rather than as an inline field initializer.
        TestDatabase.reset(TestDatabase.jdbc());
        store = new FlowStore(TestDatabase.jdbc(), dataDir.toString(), new ObjectMapper(), new OrgContext("default"));
        store.init();
    }

    private static FlowGraph flow(String id, String name) {
        return new FlowGraph(id, name, "managed", List.of(), List.of(), null, List.of(), null, null);
    }

    @Test
    void savedFlowIsReturnedByListWithMatchingFields() {
        FlowGraph saved = store.save(flow(null, "My Flow"));

        assertThat(saved.id()).isNotBlank();

        List<FlowGraph> all = store.list();

        assertThat(all).hasSize(1);
        assertThat(all.get(0).id()).isEqualTo(saved.id());
        assertThat(all.get(0).name()).isEqualTo("My Flow");
        assertThat(all.get(0).mode()).isEqualTo("managed");
    }

    @Test
    void savedFlowIsReturnedByGet() {
        FlowGraph saved = store.save(flow(null, "Gettable"));

        var found = store.get(saved.id());

        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("Gettable");
    }

    @Test
    void listSkipsUnreadableRecordsButStillReturnsValidOnes() {
        FlowGraph saved = store.save(flow(null, "Good Flow"));

        // Bypass save() and write a malformed row straight into the table — the row-level
        // equivalent of the corrupt file this used to check for. One bad record must not be able
        // to hide every other flow the user has.
        TestDatabase.jdbc().update(
                "insert into resources (kind, id, sort_key, json, updated_at, organization_id) values (?,?,?,?,?,'default')",
                "flow", "flow_corrupt", "Corrupt", "{ not valid json ][", 0L);

        assertThatCode(store::list).doesNotThrowAnyException();

        List<FlowGraph> all = store.list();

        assertThat(all).extracting(FlowGraph::id).containsExactly(saved.id());
        assertThat(all).extracting(FlowGraph::name).containsExactly("Good Flow");
    }

    @Test
    void flowsLeftOnDiskByAnOlderBuildAreImportedOnce() throws Exception {
        // A file-backed install being opened by this build for the first time.
        Path flowsDir = Files.createDirectories(dataDir.resolve("flows"));
        Files.writeString(flowsDir.resolve("flow_legacy.json"),
                new ObjectMapper().writeValueAsString(flow("flow_legacy", "From Files")));

        FlowStore fresh = new FlowStore(TestDatabase.jdbc(), dataDir.toString(), new ObjectMapper(), new OrgContext("default"));
        fresh.init();

        assertThat(fresh.get("flow_legacy")).isPresent();
        assertThat(fresh.get("flow_legacy").orElseThrow().name()).isEqualTo("From Files");
        // Set aside rather than deleted, and renamed so a second start does not import it again —
        // which would resurrect flows the user has since deleted.
        assertThat(Files.exists(flowsDir)).isFalse();
        assertThat(Files.isDirectory(dataDir.resolve("flows.migrated"))).isTrue();
    }
}
