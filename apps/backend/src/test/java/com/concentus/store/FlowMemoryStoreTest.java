package com.concentus.store;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The SQL behind flow memory, against a real PostgreSQL: insertion order is reading order, flows
 * do not see each other's notes, and the validation limits hold. Plus the availability gating
 * every store here shares (mocked, since it is about NOT touching the database).
 */
class FlowMemoryStoreTest {

    private static FlowMemoryStore realStore(JdbcTemplate jdbc) {
        jdbc.update("delete from flow_memory");
        FlowMemoryStore store = new FlowMemoryStore(jdbc);
        store.init();
        return store;
    }

    @Test
    void notesComeBackInInsertionOrderAndPerFlow() {
        FlowMemoryStore store = realStore(TestDatabase.jdbc());

        store.append("f1", "r1", "first");
        store.append("f1", "r2", "second");
        store.append("other-flow", "r3", "not yours");

        assertThat(store.count("f1")).isEqualTo(2);
        assertThat(store.recent("f1", 10)).extracting(FlowMemoryStore.Note::note)
                .containsExactly("first", "second");
        assertThat(store.latest("f1", 10)).extracting(FlowMemoryStore.Note::note)
                .containsExactly("second", "first");
        assertThat(store.recent("f1", 10)).extracting(FlowMemoryStore.Note::runId)
                .containsExactly("r1", "r2");
    }

    @Test
    void recentKeepsTheNewestNotesButStillReadsChronologically() {
        FlowMemoryStore store = realStore(TestDatabase.jdbc());
        store.append("f1", "r1", "oldest — should fall off");
        store.append("f1", "r1", "middle");
        store.append("f1", "r1", "newest");

        // The window drops the oldest, not the newest: an agent reading two notes must get the
        // two most recent — in the order they were written, so the story still reads forward.
        assertThat(store.recent("f1", 2)).extracting(FlowMemoryStore.Note::note)
                .containsExactly("middle", "newest");
    }

    @Test
    void appendRefusesBlankOversizedAndFlowlessNotes() {
        FlowMemoryStore store = realStore(TestDatabase.jdbc());

        assertThatThrownBy(() -> store.append("f1", "r1", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
        assertThatThrownBy(() -> store.append("f1", "r1", "x".repeat(FlowMemoryStore.MAX_NOTE_CHARS + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Shorten");
        assertThatThrownBy(() -> store.append(null, "r1", "note"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("saved flows");
        assertThat(store.count("f1")).isZero();
    }

    @Test
    void clearWipesOneFlowAndLeavesTheOthersAlone() {
        FlowMemoryStore store = realStore(TestDatabase.jdbc());
        store.append("f1", "r1", "gone soon");
        store.append("f2", "r2", "still here");

        store.clear("f1");

        assertThat(store.count("f1")).isZero();
        assertThat(store.count("f2")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- unavailable gating

    @Test
    void whenTheSchemaIsMissingReadsReturnNothingAndClearDoesNotTouchTheDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new RuntimeException("db unreachable"))
                .when(jdbc).queryForObject(anyString(), eq(Integer.class));
        FlowMemoryStore store = new FlowMemoryStore(jdbc);
        store.init();

        assertThat(store.isAvailable()).isFalse();
        assertThat(store.recent("f1", 10)).isEmpty();
        assertThat(store.count("f1")).isZero();
        store.clear("f1");
        verify(jdbc, never()).update(anyString(), (Object[]) org.mockito.ArgumentMatchers.any());
    }
}
