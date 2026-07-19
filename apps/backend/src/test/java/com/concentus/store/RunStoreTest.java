package com.concentus.store;

import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import com.concentus.service.AgentRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RunStore}'s availability gating (best-effort persistence: the app must
 * keep working when the database is unreachable) and its row <-> {@link AgentRun}/{@link
 * RunStore.RunRow} JSON marshaling, including tolerance of corrupt JSON columns. Uses a mocked
 * {@link JdbcTemplate} throughout — no real database, no network.
 */
class RunStoreTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private static AgentRun run(String id) {
        AgentRun r = new AgentRun(id, "flow1", "Flow", "managed");
        r.status = "RUNNING";
        r.backend = "local";
        r.trigger = "manual";
        r.totalInputTokens = 10;
        r.totalOutputTokens = 20;
        r.initialPrompt = "hello";
        return r;
    }

    // ---------------------------------------------------------------- disabled / unavailable gating

    @Test
    void whenPersistenceIsDisabledInitNeverTouchesTheDatabase() {
        RunStore store = new RunStore(jdbc, mapper, false);

        store.init();

        assertThat(store.isAvailable()).isFalse();
        verifyNoInteractions(jdbc);
    }

    @Test
    void whenDisabledAllWriteAndReadOperationsAreNoOps() {
        RunStore store = new RunStore(jdbc, mapper, false);
        store.init();

        store.persist(run("r1"));
        store.markDirty(run("r1"));
        store.delete("r1");
        List<RunStore.RunRow> rows = store.loadAll(10);

        assertThat(rows).isEmpty();
        verifyNoInteractions(jdbc);
    }

    @Test
    void whenTableCreationFailsTheStoreStaysUnavailableAndWritesAreNoOps() {
        doThrow(new RuntimeException("db unreachable")).when(jdbc).execute(anyString());
        RunStore store = new RunStore(jdbc, mapper, true);

        store.init();

        assertThat(store.isAvailable()).isFalse();

        store.persist(run("r1"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void loadAllReturnsEmptyWhenUnavailableRatherThanQuerying() {
        doThrow(new RuntimeException("db unreachable")).when(jdbc).execute(anyString());
        RunStore store = new RunStore(jdbc, mapper, true);
        store.init();

        assertThat(store.loadAll(5)).isEmpty();
    }

    // ---------------------------------------------------------------- persist() when available

    @Test
    void persistWritesTheRunsCurrentStateAsynchronously() {
        RunStore store = new RunStore(jdbc, mapper, true); // jdbc.execute(...) succeeds (default mock)
        store.init();
        assertThat(store.isAvailable()).isTrue();

        AgentRun r = run("r1");
        store.persist(r);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, timeout(2000)).update(anyString(), captor.capture());
        Object[] args = captor.getValue();
        assertThat(args[0]).isEqualTo("r1");         // id
        assertThat(args[1]).isEqualTo("flow1");      // flow_id
        assertThat(args[5]).isEqualTo("RUNNING");    // status
        assertThat(args[11]).isEqualTo(10L);         // total_input_tokens
        assertThat(args[12]).isEqualTo(20L);         // total_output_tokens
        assertThat(args[18]).isEqualTo("hello");     // initial_prompt
    }

    @Test
    void markDirtyIsANoOpWhenTheRunIsNull() {
        RunStore store = new RunStore(jdbc, mapper, true);
        store.init();

        assertThatCode(() -> store.markDirty(null)).doesNotThrowAnyException();
    }

    @Test
    void markDirtyIsANoOpWhenUnavailable() {
        doThrow(new RuntimeException("db unreachable")).when(jdbc).execute(anyString());
        RunStore store = new RunStore(jdbc, mapper, true);
        store.init();

        // Would normally queue the run for the next flush; unavailable means it must not.
        assertThatCode(() -> store.markDirty(run("r1"))).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- delete()

    @Test
    void deleteIsAsynchronousWhenAvailable() {
        RunStore store = new RunStore(jdbc, mapper, true);
        store.init();

        store.delete("r1");

        verify(jdbc, timeout(2000)).update(eq("delete from runs where id = ?"), eq("r1"));
    }

    // ---------------------------------------------------------------- loadAll() row mapping

    @Test
    @SuppressWarnings("unchecked")
    void loadAllParsesEventsAndNodeExecsAndToleratesCorruptJsonColumns() throws Exception {
        RunStore store = new RunStore(jdbc, mapper, true);
        store.init();

        ArgumentCaptor<RowMapper<RunStore.RunRow>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.query(anyString(), mapperCaptor.capture(), any(Object[].class))).thenReturn(List.of());

        store.loadAll(5);

        RowMapper<RunStore.RunRow> rowMapper = mapperCaptor.getValue();

        // A row with valid events/node-execs JSON.
        String validEvents = mapper.writeValueAsString(List.of(RunEvent.of("system", "hi")));
        NodeExec ne = new NodeExec();
        ne.nodeId = "n1";
        String validExecs = mapper.writeValueAsString(List.of(ne));
        ResultSet goodRs = mockResultSet("run_a", "flow1", "Flow", "managed", "local", "IDLE", "manual",
                "sess1", null, false, null, 1L, 2L, null, validEvents, validExecs, 100L, null, null);

        RunStore.RunRow goodRow = rowMapper.mapRow(goodRs, 0);
        assertThat(goodRow.events()).hasSize(1);
        assertThat(goodRow.events().get(0).text()).isEqualTo("hi");
        assertThat(goodRow.nodeExecs()).hasSize(1);
        assertThat(goodRow.nodeExecs().get(0).nodeId).isEqualTo("n1");

        // A row with corrupt JSON in both columns must still map, with empty lists instead of
        // throwing (parseEvents/parseExecs fail closed to List.of()).
        ResultSet badRs = mockResultSet("run_b", "flow1", "Flow", "managed", "local", "ERROR", "manual",
                null, null, false, "boom", 0L, 0L, null, "{ not valid json", "[ also broken",
                200L, null, null);

        RunStore.RunRow badRow = rowMapper.mapRow(badRs, 1);
        assertThat(badRow.events()).isEmpty();
        assertThat(badRow.nodeExecs()).isEmpty();
        assertThat(badRow.id()).isEqualTo("run_b");
        assertThat(badRow.error()).isEqualTo("boom");
    }

    private static ResultSet mockResultSet(String id, String flowId, String flowName, String mode,
                                           String backend, String status, String trigger, String sessionId,
                                           String localSessionId, boolean localStarted, String error,
                                           long totalInputTokens, long totalOutputTokens, String flowJson,
                                           String eventsJson, String nodeExecsJson, long createdAt,
                                           Long unusedUpdatedAt, String notifyWebhook) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(id);
        when(rs.getString("flow_id")).thenReturn(flowId);
        when(rs.getString("flow_name")).thenReturn(flowName);
        when(rs.getString("mode")).thenReturn(mode);
        when(rs.getString("backend")).thenReturn(backend);
        when(rs.getString("status")).thenReturn(status);
        when(rs.getString("trigger_type")).thenReturn(trigger);
        when(rs.getString("session_id")).thenReturn(sessionId);
        when(rs.getString("local_session_id")).thenReturn(localSessionId);
        when(rs.getBoolean("local_started")).thenReturn(localStarted);
        when(rs.getString("error")).thenReturn(error);
        when(rs.getLong("total_input_tokens")).thenReturn(totalInputTokens);
        when(rs.getLong("total_output_tokens")).thenReturn(totalOutputTokens);
        when(rs.getString("flow_json")).thenReturn(flowJson);
        when(rs.getString("events_json")).thenReturn(eventsJson);
        when(rs.getString("node_execs_json")).thenReturn(nodeExecsJson);
        when(rs.getLong("created_at")).thenReturn(createdAt);
        when(rs.getString("initial_prompt")).thenReturn(null);
        when(rs.getString("notify_webhook")).thenReturn(notifyWebhook);
        return rs;
    }
}
