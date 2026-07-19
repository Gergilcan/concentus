package com.concentus.store;

import com.concentus.model.FlowGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FlowVersionStore}: availability gating when the database is unreachable,
 * the version-numbering logic in {@link FlowVersionStore#snapshot}, and tolerance of a corrupt
 * {@code flow_json} column in {@link FlowVersionStore#get}. Uses a mocked {@link JdbcTemplate} —
 * no real database, no network.
 */
class FlowVersionStoreTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private static FlowGraph flow(String id) {
        return new FlowGraph(id, "My Flow", "managed", List.of(), List.of(), null, List.of(), null, null);
    }

    // ---------------------------------------------------------------- disabled / unavailable gating

    @Test
    void whenDisabledInitNeverTouchesTheDatabase() {
        FlowVersionStore store = new FlowVersionStore(jdbc, mapper, false);

        store.init();

        assertThat(store.isAvailable()).isFalse();
        verifyNoInteractions(jdbc);
    }

    @Test
    void whenDisabledSnapshotListAndGetAreAllNoOps() {
        FlowVersionStore store = new FlowVersionStore(jdbc, mapper, false);
        store.init();

        store.snapshot(flow("f1"));
        List<?> versions = store.list("f1");
        Optional<FlowGraph> got = store.get("f1", 1);

        assertThat(versions).isEmpty();
        assertThat(got).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    void whenTableCreationFailsTheStoreStaysUnavailable() {
        doThrow(new RuntimeException("db unreachable")).when(jdbc).execute(anyString());
        FlowVersionStore store = new FlowVersionStore(jdbc, mapper, true);

        store.init();

        assertThat(store.isAvailable()).isFalse();
        store.snapshot(flow("f1"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void snapshotIsANoOpForANullFlowOrAFlowWithoutAnId() {
        FlowVersionStore store = new FlowVersionStore(jdbc, mapper, true); // init() succeeds
        store.init();

        store.snapshot(null);
        store.snapshot(flow(null));

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    // ---------------------------------------------------------------- snapshot() versioning

    @Test
    void snapshotInsertsVersionOneWhenNoPriorVersionExists() {
        FlowVersionStore store = new FlowVersionStore(jdbc, mapper, true);
        store.init();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("f1"))).thenReturn(0);

        store.snapshot(flow("f1"));

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), captor.capture());
        Object[] args = captor.getValue();
        assertThat(args[0]).isEqualTo("f1");   // flow_id
        assertThat(args[1]).isEqualTo(1);      // version
        assertThat(args[2]).isEqualTo("My Flow"); // name
    }

    @Test
    void snapshotIncrementsFromTheCurrentMaxVersion() {
        FlowVersionStore store = new FlowVersionStore(jdbc, mapper, true);
        store.init();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("f1"))).thenReturn(4);

        store.snapshot(flow("f1"));

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), captor.capture());
        assertThat(captor.getValue()[1]).isEqualTo(5); // next version after max=4
    }

    @Test
    void snapshotSwallowsDatabaseErrorsRatherThanPropagating() {
        FlowVersionStore store = new FlowVersionStore(jdbc, mapper, true);
        store.init();
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("f1")))
                .thenThrow(new RuntimeException("boom"));

        store.snapshot(flow("f1")); // must not throw
    }

    // ---------------------------------------------------------------- list() row mapping

    @Test
    @SuppressWarnings("unchecked")
    void listMapsVersionNameAndCreatedAt() throws Exception {
        FlowVersionStore store = new FlowVersionStore(jdbc, mapper, true);
        store.init();
        ArgumentCaptor<RowMapper<Object>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.query(anyString(), mapperCaptor.capture(), eq("f1"))).thenReturn(List.of());

        store.list("f1");

        RowMapper<Object> rowMapper = mapperCaptor.getValue();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getInt("version")).thenReturn(3);
        when(rs.getString("name")).thenReturn("v3 name");
        when(rs.getLong("created_at")).thenReturn(999L);

        Object mapped = rowMapper.mapRow(rs, 0);

        assertThat(mapped).hasFieldOrPropertyWithValue("version", 3);
        assertThat(mapped).hasFieldOrPropertyWithValue("name", "v3 name");
        assertThat(mapped).hasFieldOrPropertyWithValue("createdAt", 999L);
    }

    // ---------------------------------------------------------------- get() corrupt-JSON handling

    @Test
    void getReturnsEmptyWhenNoRowMatches() {
        FlowVersionStore store = new FlowVersionStore(jdbc, mapper, true);
        store.init();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq("f1"), eq(1)))
                .thenReturn(List.of());

        assertThat(store.get("f1", 1)).isEmpty();
    }

    @Test
    void getReturnsEmptyWhenTheStoredFlowJsonIsCorrupt() {
        FlowVersionStore store = new FlowVersionStore(jdbc, mapper, true);
        store.init();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq("f1"), eq(1)))
                .thenReturn(List.of("{ not valid json ]["));

        assertThat(store.get("f1", 1)).isEmpty();
    }

    @Test
    void getReturnsTheFlowWhenTheStoredJsonIsValid() throws Exception {
        FlowVersionStore store = new FlowVersionStore(jdbc, mapper, true);
        store.init();
        String json = mapper.writeValueAsString(flow("f1"));
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq("f1"), eq(2)))
                .thenReturn(List.of(json));

        Optional<FlowGraph> result = store.get("f1", 2);

        // Not a full equals(flow("f1")): FlowGraph's isEnabled()/isFavorite() derived accessors
        // clash with Jackson's bean-property introspection of the record's own enabled/favorite
        // components, so a null enabled/favorite round-trips as true/false rather than null.
        assertThat(result).isPresent();
        assertThat(result.orElseThrow().id()).isEqualTo("f1");
        assertThat(result.orElseThrow().name()).isEqualTo("My Flow");
    }
}
