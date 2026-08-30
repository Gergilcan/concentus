package com.concentus.store;

import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import com.concentus.model.RunPatch;
import com.concentus.service.AgentRun;
import com.fasterxml.jackson.core.type.TypeReference;
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
        AgentRun r = new AgentRun(id, "flow1", "Flow");
        r.status = "RUNNING";
        r.backend = "local";
        r.trigger = "manual";
        r.totalInputTokens = 10;
        r.totalOutputTokens = 20;
        r.initialPrompt = "hello";
        return r;
    }

    // ---------------------------------------------------------------- unavailable gating

    @Test
    void whenTheSchemaIsMissingTheStoreStaysUnavailableAndWritesAreNoOps() {
        doThrow(new RuntimeException("db unreachable")).when(jdbc).queryForObject(anyString(), eq(Integer.class));
        RunStore store = new RunStore(jdbc, mapper);

        store.init();

        assertThat(store.isAvailable()).isFalse();

        store.persist(run("r1"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void loadAllReturnsEmptyWhenUnavailableRatherThanQuerying() {
        doThrow(new RuntimeException("db unreachable")).when(jdbc).queryForObject(anyString(), eq(Integer.class));
        RunStore store = new RunStore(jdbc, mapper);
        store.init();

        assertThat(store.loadAll(5)).isEmpty();
    }

    // ---------------------------------------------------------------- persist() when available

    @Test
    void persistWritesTheRunsCurrentStateAsynchronously() {
        RunStore store = new RunStore(jdbc, mapper); // jdbc.execute(...) succeeds (default mock)
        store.init();
        assertThat(store.isAvailable()).isTrue();

        AgentRun r = run("r1");
        r.recordPatch(RunPatch.registered("w1", "Worker", "repo", "https://x/repo.git", null, null)
                .taken("diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n", 7));
        store.persist(r);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, timeout(2000)).update(anyString(), captor.capture());
        Object[] args = captor.getValue();
        assertThat(args[0]).isEqualTo("r1");         // id
        assertThat(args[1]).isEqualTo("flow1");      // flow_id
        assertThat(args[4]).isEqualTo("RUNNING");    // status
        assertThat(args[10]).isEqualTo(10L);         // total_input_tokens
        assertThat(args[11]).isEqualTo(20L);         // total_output_tokens
        assertThat(args[17]).isEqualTo("hello");     // initial_prompt
        // patches_json: the review ledger goes with the run, patch text included.
        assertThat((String) args[23]).contains("\"nodeId\":\"w1\"").contains("+b");
    }

    @Test
    void persistCapsThePatchTextItStoresAndSaysSo() throws Exception {
        RunStore store = new RunStore(jdbc, mapper);
        store.init();

        AgentRun r = run("r1");
        String line = "+" + "x".repeat(1000) + "\n";
        String huge = "diff --git a/big b/big\n--- a/big\n+++ b/big\n@@ -0,0 +1 @@\n"
                + line.repeat(RunPatch.MAX_STORED_BYTES / 1000 + 10);
        r.recordPatch(RunPatch.registered("w1", "Worker", "repo", null, null, null).taken(huge, 7));
        store.persist(r);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, timeout(2000)).update(anyString(), captor.capture());
        List<RunPatch> stored = mapper.readValue((String) captor.getValue()[23],
                new TypeReference<List<RunPatch>>() {});
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).patch()).isNull();
        assertThat(stored.get(0).note()).isEqualTo(RunPatch.CAPPED_NOTE);
        // The numbers survive the cap: a reviewer still learns how big the change was.
        assertThat(stored.get(0).stats().additions()).isGreaterThan(2000);
        // The run itself keeps the full text — the cap is on the row, not on the review.
        assertThat(r.patchOf("w1", "repo").patch()).isEqualTo(huge);
    }

    @Test
    void markDirtyIsANoOpWhenTheRunIsNull() {
        RunStore store = new RunStore(jdbc, mapper);
        store.init();

        assertThatCode(() -> store.markDirty(null)).doesNotThrowAnyException();
    }

    @Test
    void markDirtyIsANoOpWhenUnavailable() {
        doThrow(new RuntimeException("db unreachable")).when(jdbc).queryForObject(anyString(), eq(Integer.class));
        RunStore store = new RunStore(jdbc, mapper);
        store.init();

        // Would normally queue the run for the next flush; unavailable means it must not.
        assertThatCode(() -> store.markDirty(run("r1"))).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- delete()

    @Test
    void deleteIsAsynchronousWhenAvailable() {
        RunStore store = new RunStore(jdbc, mapper);
        store.init();

        store.delete("r1");

        verify(jdbc, timeout(2000)).update(eq("delete from runs where id = ?"), eq("r1"));
    }

    // ---------------------------------------------------------------- loadAll() row mapping

    @Test
    @SuppressWarnings("unchecked")
    void loadAllParsesEventsAndNodeExecsAndToleratesCorruptJsonColumns() throws Exception {
        RunStore store = new RunStore(jdbc, mapper);
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
        ResultSet goodRs = mockResultSet("run_a", "flow1", "Flow", "local", "IDLE", "manual",
                "sess1", null, false, null, 1L, 2L, null, validEvents, validExecs, 100L, null, null);
        RunPatch patch = RunPatch.registered("w1", "Worker", "repo", "https://x/repo.git",
                null, "abc").taken("diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n", 7);
        when(goodRs.getString("patches_json")).thenReturn(mapper.writeValueAsString(List.of(patch)));

        RunStore.RunRow goodRow = rowMapper.mapRow(goodRs, 0);
        assertThat(goodRow.events()).hasSize(1);
        assertThat(goodRow.events().get(0).text()).isEqualTo("hi");
        assertThat(goodRow.nodeExecs()).hasSize(1);
        assertThat(goodRow.nodeExecs().get(0).nodeId).isEqualTo("n1");
        // The review ledger comes back whole — a restart must not cost the diff.
        assertThat(goodRow.patches()).containsExactly(patch);

        // A row with corrupt JSON in both columns must still map, with empty lists instead of
        // throwing (parseList fails closed to List.of()).
        ResultSet badRs = mockResultSet("run_b", "flow1", "Flow", "local", "ERROR", "manual",
                null, null, false, "boom", 0L, 0L, null, "{ not valid json", "[ also broken",
                200L, null, null);

        RunStore.RunRow badRow = rowMapper.mapRow(badRs, 1);
        assertThat(badRow.events()).isEmpty();
        assertThat(badRow.nodeExecs()).isEmpty();
        assertThat(badRow.id()).isEqualTo("run_b");
        assertThat(badRow.error()).isEqualTo("boom");
    }

    private static ResultSet mockResultSet(String id, String flowId, String flowName,
                                           String backend, String status, String trigger, String sessionId,
                                           String localSessionId, boolean localStarted, String error,
                                           long totalInputTokens, long totalOutputTokens, String flowJson,
                                           String eventsJson, String nodeExecsJson, long createdAt,
                                           Long unusedUpdatedAt, String notifyWebhook) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn(id);
        when(rs.getString("flow_id")).thenReturn(flowId);
        when(rs.getString("flow_name")).thenReturn(flowName);
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
