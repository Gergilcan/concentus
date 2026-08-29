package com.concentus.store;

import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import com.concentus.model.RunPatch;
import com.concentus.service.AgentRun;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public RunStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostConstruct
    void init() {
        // Created by the migrations. Probing initial_prompt rather than just the table because it
        // is one of the columns that used to be added by an `alter` here — if the migration did
        // not reach this database, an empty count on the table alone would look like success and
        // the failure would surface later, on the first write.
        try {
            jdbc.queryForObject("select count(initial_prompt) from runs", Integer.class);
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
        return available;
    }

    /**
     * What every run on one execution backend has cost since a moment, across all flows — the
     * figure a subscription's allowance is measured against. Backend rather than flow, because
     * the allowance belongs to the login, not to a drawing.
     */
    public double spendUsdOnBackendSince(String backend, long sinceMillis) {
        return sumCostUsd("backend = ? and created_at >= ?", backend, sinceMillis);
    }

    /** What this flow has spent since {@code sinceMillis} — the flow's own budget gate. */
    public double spendUsdSince(String flowId, long sinceMillis) {
        return sumCostUsd("flow_id = ? and created_at >= ?", flowId, sinceMillis);
    }

    /**
     * What every flow together has cost since a moment — the organization's budget gate. Every
     * flow rather than one organization's, because runs are not partitioned by organization (see
     * the README on isolation); on the single-organization deployment this is, the two are the
     * same sum.
     */
    public double spendUsdSince(long sinceMillis) {
        return sumCostUsd("created_at >= ?", sinceMillis);
    }

    /**
     * The cost of the runs matching {@code condition}. Zero when persistence is down: a broken
     * database must not also stop every budgeted flow.
     */
    private double sumCostUsd(String condition, Object... args) {
        if (!isAvailable()) return 0d;
        try {
            Double sum = jdbc.queryForObject(
                    "select coalesce(sum(cost_usd), 0) from runs where " + condition, Double.class, args);
            return sum == null ? 0d : sum;
        } catch (Exception e) {
            log.debug("spend query failed: {}", e.getMessage());
            return 0d;
        }
    }

    /** Queue an upsert of the run's current state. Non-blocking; best-effort. */
    public void persist(AgentRun run) {
        if (!isAvailable()) return;
        String eventsJson = toJson(run.bufferedEvents());
        String execsJson = toJson(run.nodeExecList());
        // Capped here, on the way out, rather than in the run: the run keeps every patch it read
        // while it lives, so a review in the same process is never the trimmed one.
        String patchesJson = toJson(RunPatch.capped(run.patchList(), RunPatch.MAX_STORED_BYTES));
        long now = System.currentTimeMillis();
        writer.submit(() -> {
            try {
                jdbc.update("""
                    insert into runs (id, flow_id, flow_name, mode, backend, status, trigger_type,
                      session_id, local_session_id, local_started, error,
                      total_input_tokens, total_output_tokens, flow_json, events_json, node_execs_json,
                      created_at, updated_at, initial_prompt, notify_webhook, cost_usd, golden,
                      flow_version, started_by, patches_json, organization_id)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    on conflict (id) do update set
                      flow_id=excluded.flow_id, flow_name=excluded.flow_name, mode=excluded.mode,
                      backend=excluded.backend, status=excluded.status, trigger_type=excluded.trigger_type,
                      session_id=excluded.session_id, local_session_id=excluded.local_session_id,
                      local_started=excluded.local_started, error=excluded.error,
                      total_input_tokens=excluded.total_input_tokens,
                      total_output_tokens=excluded.total_output_tokens, flow_json=excluded.flow_json,
                      events_json=excluded.events_json, node_execs_json=excluded.node_execs_json,
                      updated_at=excluded.updated_at, initial_prompt=excluded.initial_prompt,
                      notify_webhook=excluded.notify_webhook,
                      cost_usd=excluded.cost_usd,
                      golden=excluded.golden,
                      flow_version=excluded.flow_version,
                      started_by=excluded.started_by,
                      patches_json=excluded.patches_json,
                      organization_id=excluded.organization_id
                    """,
                    run.id, run.flowId, run.flowName, run.mode, run.backend, run.status, run.trigger,
                    run.sessionId, run.localSessionId, run.localStarted, run.error,
                    run.totalInputTokens, run.totalOutputTokens, run.flowJson, eventsJson, execsJson,
                    run.createdAt, now, run.initialPrompt, run.notifyWebhook, run.estimatedCostUsd(),
                    run.golden, run.flowVersion, run.startedBy, patchesJson, run.organizationId);
            } catch (Exception e) {
                log.debug("persist run {} failed: {}", run.id, e.getMessage());
            }
        });
    }

    /**
     * Loads the most recent runs (metadata + events + node execs + flow snapshot) — plus every
     * golden run, however old. A reference is a reference until unmarked; letting it silently age
     * out of the restore window would make "compare against golden" fail exactly on the flows
     * that run most often.
     */
    public List<RunRow> loadAll(int limit) {
        if (!isAvailable()) return List.of();
        try {
            return jdbc.query(
                """
                select * from (select * from runs order by created_at desc limit ?) recent
                union
                select * from runs where golden
                order by created_at desc
                """,
                (rs, i) -> new RunRow(
                    rs.getString("id"), rs.getString("flow_id"), rs.getString("flow_name"),
                    rs.getString("mode"), rs.getString("backend"), rs.getString("status"),
                    rs.getString("trigger_type"), rs.getString("session_id"),
                    rs.getString("local_session_id"), rs.getBoolean("local_started"),
                    rs.getString("error"), rs.getLong("total_input_tokens"),
                    rs.getLong("total_output_tokens"), rs.getString("flow_json"),
                    parseList(rs.getString("events_json"), new TypeReference<List<RunEvent>>() {}),
                    parseList(rs.getString("node_execs_json"), new TypeReference<List<NodeExec>>() {}),
                    rs.getLong("created_at"), rs.getString("initial_prompt"),
                    rs.getString("notify_webhook"), rs.getBoolean("golden"),
                    rs.getInt("flow_version"), rs.getString("started_by"),
                    parseList(rs.getString("patches_json"), new TypeReference<List<RunPatch>>() {}),
                    rs.getString("organization_id")),
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

    /**
     * A JSON column back into a list, empty when it is absent or unreadable. A run row that has
     * outlived a change to one of these shapes still loads, minus the part that no longer parses —
     * losing the events of an old run beats losing the run.
     */
    private <E> List<E> parseList(String json, TypeReference<List<E>> type) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, type);
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
                         String notifyWebhook, boolean golden, int flowVersion,
                         /** The person who pressed Run; null for a schedule, a webhook or a sub-flow. */
                         String startedBy,
                         /** What the run did to its repositories — see {@link RunPatch}. */
                         List<RunPatch> patches,
                         /** Whose run this is; null only on a row written before runs were stamped. */
                         String organizationId) {

        /**
         * A row with no author, no patches and no organization — the shape tests that predate
         * those three build.
         */
        public RunRow(String id, String flowId, String flowName, String mode, String backend,
                      String status, String trigger, String sessionId, String localSessionId,
                      boolean localStarted, String error, long totalInputTokens,
                      long totalOutputTokens, String flowJson, List<RunEvent> events,
                      List<NodeExec> nodeExecs, long createdAt, String initialPrompt,
                      String notifyWebhook, boolean golden, int flowVersion) {
            this(id, flowId, flowName, mode, backend, status, trigger, sessionId, localSessionId,
                    localStarted, error, totalInputTokens, totalOutputTokens, flowJson, events,
                    nodeExecs, createdAt, initialPrompt, notifyWebhook, golden, flowVersion,
                    null, List.of(), null);
        }
    }
}
