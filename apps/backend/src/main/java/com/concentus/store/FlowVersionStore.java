package com.concentus.store;

import com.concentus.model.FlowGraph;
import com.concentus.model.FlowVersionInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps a version history for every flow: each save snapshots the flow's JSON so an earlier
 * revision can be inspected and restored. Best-effort — if the database is unavailable, saving
 * still works, just without history.
 */
@Component
public class FlowVersionStore {

    private static final Logger log = LoggerFactory.getLogger(FlowVersionStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private volatile boolean available;

    public FlowVersionStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostConstruct
    void init() {
        // Created by the migrations; this only checks it arrived.
        try {
            jdbc.queryForObject("select count(*) from flow_versions", Integer.class);
            available = true;
        } catch (Exception e) {
            log.warn("Flow version history unavailable: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Appends the flow's current state as the next version, credited to {@code author} (null when
     * nobody is signed in and authentication is on — the revision is then honestly unsigned).
     */
    public void snapshot(FlowGraph flow, String author) {
        if (!isAvailable() || flow == null || flow.id() == null) return;
        try {
            int next = maxVersion(flow.id()) + 1;
            jdbc.update(
                    "insert into flow_versions (flow_id, version, name, flow_json, created_at, author)"
                            + " values (?,?,?,?,?,?)",
                    flow.id(), next, flow.name(), mapper.writeValueAsString(flow),
                    System.currentTimeMillis(), author);
        } catch (Exception e) {
            log.debug("Version snapshot for {} failed: {}", flow.id(), e.getMessage());
        }
    }

    /**
     * The flow's latest version number, or 0 when it has no history yet (or history is off).
     * Runs stamp this at launch so an execution can be named by the revision it ran.
     */
    public int currentVersion(String flowId) {
        if (!isAvailable() || flowId == null) return 0;
        try {
            return maxVersion(flowId);
        } catch (Exception e) {
            log.debug("Current version for {} unavailable: {}", flowId, e.getMessage());
            return 0;
        }
    }

    /**
     * The highest version number on record, propagating a database failure to the caller. Snapshot
     * needs the failure: a query error swallowed into "0" would number the next revision 1 and
     * collide with the history that is actually there.
     */
    private int maxVersion(String flowId) {
        Integer max = jdbc.queryForObject(
                "select coalesce(max(version), 0) from flow_versions where flow_id = ?",
                Integer.class, flowId);
        return max == null ? 0 : max;
    }

    public List<FlowVersionInfo> list(String flowId) {
        if (!isAvailable()) return List.of();
        try {
            // The stored JSON comes back with each row so the shape of every revision can be
            // measured here. Counting on read rather than storing counts is what makes the size
            // and the diff hint work for revisions saved long before either existed — the cost is
            // parsing one flow per row, which is a local list of tens, not a report over millions.
            List<Row> rows = jdbc.query(
                    "select version, name, created_at, author, flow_json from flow_versions"
                            + " where flow_id = ? order by version desc",
                    (rs, i) -> new Row(rs.getInt("version"), rs.getString("name"),
                            rs.getLong("created_at"), rs.getString("author"),
                            rs.getString("flow_json")),
                    flowId);
            return withDiffs(rows);
        } catch (Exception e) {
            log.debug("Listing versions for {} failed: {}", flowId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Turns newest-first rows into the public shape, each carrying what changed against the row
     * after it — which, in a descending list, is the revision it replaced.
     */
    private List<FlowVersionInfo> withDiffs(List<Row> rows) {
        List<FlowVersionInfo> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            Row prev = i + 1 < rows.size() ? rows.get(i + 1) : null;
            int nodes = count(row.flowJson(), "nodes");
            int edges = count(row.flowJson(), "edges");
            int nodesDelta = prev == null ? 0 : nodes - count(prev.flowJson(), "nodes");
            int edgesDelta = prev == null ? 0 : edges - count(prev.flowJson(), "edges");
            boolean renamed = prev != null && !Objects.equals(row.name(), prev.name());
            out.add(new FlowVersionInfo(row.version(), row.name(), row.createdAt(), row.author(),
                    nodes, edges, nodesDelta, edgesDelta, renamed));
        }
        return out;
    }

    /**
     * Size of a top-level array in a stored snapshot. Read as a tree rather than as a FlowGraph so
     * a revision written by an older (or newer) shape of the model still reports its size instead
     * of failing the whole listing.
     */
    private int count(String flowJson, String field) {
        if (flowJson == null || flowJson.isBlank()) return 0;
        try {
            JsonNode node = mapper.readTree(flowJson).path(field);
            return node.isArray() ? node.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** The flow as it was at that version. */
    public Optional<FlowGraph> get(String flowId, int version) {
        if (!isAvailable()) return Optional.empty();
        try {
            List<String> json = jdbc.query(
                    "select flow_json from flow_versions where flow_id = ? and version = ?",
                    (rs, i) -> rs.getString("flow_json"), flowId, version);
            if (json.isEmpty() || json.get(0) == null) return Optional.empty();
            return Optional.of(mapper.readValue(json.get(0), FlowGraph.class));
        } catch (Exception e) {
            log.debug("Loading version {} of {} failed: {}", version, flowId, e.getMessage());
            return Optional.empty();
        }
    }

    /** One history row as stored, before the listing works out what changed between them. */
    record Row(int version, String name, long createdAt, String author, String flowJson) {
    }
}
