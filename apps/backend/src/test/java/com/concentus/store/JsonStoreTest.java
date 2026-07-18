package com.concentus.store;

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
        store = new FlowStore(dataDir.toString(), new ObjectMapper());
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
    void listSkipsCorruptFilesButStillReturnsValidRecords() throws Exception {
        FlowGraph saved = store.save(flow(null, "Good Flow"));

        // Bypass save() and drop a malformed JSON file directly into the store dir.
        Path badFile = dataDir.resolve("flows").resolve("corrupt.json");
        Files.writeString(badFile, "{ not valid json ][");

        assertThatCode(store::list).doesNotThrowAnyException();

        List<FlowGraph> all = store.list();

        assertThat(all).extracting(FlowGraph::id).containsExactly(saved.id());
        assertThat(all).extracting(FlowGraph::name).containsExactly("Good Flow");
    }
}
