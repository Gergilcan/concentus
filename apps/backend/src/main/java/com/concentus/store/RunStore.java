package com.concentus.store;

import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import com.concentus.service.AgentRun;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Persists runs/executions to PostgreSQL so they survive a restart and can be continued. Each run
 * is one row holding its metadata plus JSON snapshots of its events and per-node execution state,
 * and a FlowGraph snapshot used to recompile and resume it. Writes are async and best-effort: if
 * the database is unavailable the app keeps working in memory.
 */
@Component
public class RunStore {

    private static final Logger log = LoggerFactory.getLogger(RunStore.class);
    /** How often changed runs are written out while they stream. */
    private static final long FLUSH_INTERVAL_MS = 2_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "run-store-writer");
        t.setDaemon(true);
        return t;
    });
    /**
     * Runs whose in-memory state has changed since their last write. A turn can stream for
     * minutes, so waiting until it ends would lose every block's input/output if the process
     * restarts mid-run — instead dirty runs are flushed on a short interval.
     */
    private final Map<String, AgentRun> dirty = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "run-store-flusher");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean available;

    public RunStore(JdbcTemplate jdbc, ObjectMapper mapper,
                    @Value("${app.persistence.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("Run persistence disabled (app.persistence.enabled=false).");
            return;
        }
        try {
            jdbc.execute("""
                create table if not exists runs (
                  id text primary key,
                  flow_id text,
                  flow_name text,
                  mode text,
                  backend text,
                  status text,
                  trigger_type text,
                  session_id text,
                  local_session_id text,
                  local_started boolean default false,
                  error text,
                  total_input_tokens bigint default 0,
                  total_output_tokens bigint default 0,
                  flow_json text,
                  events_json text,
                  node_execs_json text,
                  created_at bigint,
                  updated_at bigint
                )
                """);
            // Added after the first release — safe to run every start.
            jdbc.execute("alter table runs add column if not exists initial_prompt text");
            jdbc.execute("alter table runs add column if not exists notify_webhook text");
            available = true;
            log.info("Run persistence ready (PostgreSQL).");
        } catch (Exception e) {
            available = false;
            log.warn("Run persistence unavailable — continuing in memory only: {}", e.getMessage());
        }
        flusher.scheduleWithFixedDelay(this::flushDirty, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Marks a run as changed. Coalesced: many events during a turn produce at most one write per
     * flush interval, so streaming output is durable without hammering the database.
     */
    public void markDirty(AgentRun run) {
        if (!isAvailable() || run == null) return;
        dirty.put(run.id, run);
    }

    private void flushDirty() {
        if (dirty.isEmpty()) return;
        for (String id : List.copyOf(dirty.keySet())) {
            AgentRun run = dirty.remove(id);
            if (run != null) persist(run);
        }
    }

    public boolean isAvailable() {
        return enabled && available;
    }

    /** Queue an upsert of the run's current state. Non-blocking; best-effort. */
    public void persist(AgentRun run) {
        if (!isAvailable()) return;
        String eventsJson = toJson(run.bufferedEvents());
        String execsJson = toJson(run.nodeExecList());
        long now = System.currentTimeMillis();
        writer.submit(() -> {
            try {
                jdbc.update("""
                    insert into runs (id, flow_id, flow_name, mode, backend, status, trigger_type,
                      session_id, local_session_id, local_started, error,
                      total_input_tokens, total_output_tokens, flow_json, events_json, node_execs_json,
                      created_at, updated_at, initial_prompt, notify_webhook)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    on conflict (id) do update set
                      flow_id=excluded.flow_id, flow_name=excluded.flow_name, mode=excluded.mode,
                      backend=excluded.backend, status=excluded.status, trigger_type=excluded.trigger_type,
                      session_id=excluded.session_id, local_session_id=excluded.local_session_id,
                      local_started=excluded.local_started, error=excluded.error,
                      total_input_tokens=excluded.total_input_tokens,
                      total_output_tokens=excluded.total_output_tokens, flow_json=excluded.flow_json,
                      events_json=excluded.events_json, node_execs_json=excluded.node_execs_json,
                      updated_at=excluded.updated_at, initial_prompt=excluded.initial_prompt,
                      notify_webhook=excluded.notify_webhook
                    """,
                    run.id, run.flowId, run.flowName, run.mode, run.backend, run.status, run.trigger,
                    run.sessionId, run.localSessionId, run.localStarted, run.error,
                    run.totalInputTokens, run.totalOutputTokens, run.flowJson, eventsJson, execsJson,
                    run.createdAt, now, run.initialPrompt, run.notifyWebhook);
            } catch (Exception e) {
                log.debug("persist run {} failed: {}", run.id, e.getMessage());
            }
        });
    }

    /** Loads the most recent runs (metadata + events + node execs + flow snapshot). */
    public List<RunRow> loadAll(int limit) {
        if (!isAvailable()) return List.of();
        try {
            return jdbc.query(
                "select * from runs order by created_at desc limit ?",
                (rs, i) -> new RunRow(
                    rs.getString("id"), rs.getString("flow_id"), rs.getString("flow_name"),
                    rs.getString("mode"), rs.getString("backend"), rs.getString("status"),
                    rs.getString("trigger_type"), rs.getString("session_id"),
                    rs.getString("local_session_id"), rs.getBoolean("local_started"),
                    rs.getString("error"), rs.getLong("total_input_tokens"),
                    rs.getLong("total_output_tokens"), rs.getString("flow_json"),
                    parseEvents(rs.getString("events_json")), parseExecs(rs.getString("node_execs_json")),
                    rs.getLong("created_at"), rs.getString("initial_prompt"),
                    rs.getString("notify_webhook")),
                limit);
        } catch (Exception e) {
            log.warn("Loading persisted runs failed: {}", e.getMessage());
            return List.of();
        }
    }

    public void delete(String id) {
        if (!isAvailable()) return;
        writer.submit(() -> {
            try {
                jdbc.update("delete from runs where id = ?", id);
            } catch (Exception e) {
                log.debug("delete run {} failed: {}", id, e.getMessage());
            }
        });
    }

    @PreDestroy
    void shutdown() {
        // Write out anything still pending before the process goes away, so a restart (including
        // a devtools reload mid-run) doesn't drop the last few seconds of block output.
        flusher.shutdownNow();
        flushDirty();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Run persistence writer did not drain in time; some updates may be lost.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<RunEvent> parseEvents(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<RunEvent>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<NodeExec> parseExecs(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, new TypeReference<List<NodeExec>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /** A persisted run row, ready for reconstruction into an in-memory {@link AgentRun}. */
    public record RunRow(String id, String flowId, String flowName, String mode, String backend,
                         String status, String trigger, String sessionId, String localSessionId,
                         boolean localStarted, String error, long totalInputTokens,
                         long totalOutputTokens, String flowJson, List<RunEvent> events,
                         List<NodeExec> nodeExecs, long createdAt, String initialPrompt,
                         String notifyWebhook) {
    }
}
