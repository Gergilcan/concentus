package com.concentus.store;

import com.concentus.model.FlowGraph;
import com.concentus.model.FlowVersionInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
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
    private final boolean enabled;
    private volatile boolean available;

    public FlowVersionStore(JdbcTemplate jdbc, ObjectMapper mapper,
                            @Value("${app.persistence.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @PostConstruct
    void init() {
        if (!enabled) return;
        try {
            jdbc.execute("""
                create table if not exists flow_versions (
                  flow_id text not null,
                  version int not null,
                  name text,
                  flow_json text,
                  created_at bigint,
                  primary key (flow_id, version)
                )
                """);
            available = true;
        } catch (Exception e) {
            log.warn("Flow version history unavailable: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return enabled && available;
    }

    /** Appends the flow's current state as the next version. */
    public void snapshot(FlowGraph flow) {
        if (!isAvailable() || flow == null || flow.id() == null) return;
        try {
            Integer max = jdbc.queryForObject(
                    "select coalesce(max(version), 0) from flow_versions where flow_id = ?",
                    Integer.class, flow.id());
            int next = (max == null ? 0 : max) + 1;
            jdbc.update(
                    "insert into flow_versions (flow_id, version, name, flow_json, created_at) values (?,?,?,?,?)",
                    flow.id(), next, flow.name(), mapper.writeValueAsString(flow),
                    System.currentTimeMillis());
        } catch (Exception e) {
            log.debug("Version snapshot for {} failed: {}", flow.id(), e.getMessage());
        }
    }

    public List<FlowVersionInfo> list(String flowId) {
        if (!isAvailable()) return List.of();
        try {
            return jdbc.query(
                    "select version, name, created_at from flow_versions where flow_id = ? order by version desc",
                    (rs, i) -> new FlowVersionInfo(rs.getInt("version"), rs.getString("name"),
                            rs.getLong("created_at")),
                    flowId);
        } catch (Exception e) {
            log.debug("Listing versions for {} failed: {}", flowId, e.getMessage());
            return List.of();
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
}
