package com.concentus.store;

import com.concentus.audit.AuditEvent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The {@code audit_events} table: append a row, read a page of them, stream a window, purge.
 *
 * <p>Unlike the run store this one is synchronous. An audit row is a hundred bytes written once
 * per action, not a streaming transcript flushed every two seconds; a writer thread would buy
 * nothing and would cost the one property an audit trail needs — that when the action's request
 * has returned, the row is there. What it shares with the other stores is the posture towards a
 * missing database: unavailable is reported, never thrown, and the service in front of this
 * decides what that means (it means: log, and carry on).
 */
@Component
public class AuditStore {

    private static final Logger log = LoggerFactory.getLogger(AuditStore.class);

    /** How the trail is filtered: every field optional, null meaning "any". */
    public record Filter(String actor, String kind, Long fromMillis, Long toMillis) {
        public static final Filter NONE = new Filter(null, null, null, null);
    }

    private final JdbcTemplate jdbc;
    private volatile boolean available;

    public AuditStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Public for the tests that wire this outside a container; the probe decides availability. */
    @PostConstruct
    public void init() {
        // Created by the migrations; this only checks it arrived.
        try {
            jdbc.queryForObject("select count(*) from audit_events", Integer.class);
            available = true;
        } catch (Exception e) {
            available = false;
            log.warn("Audit trail unavailable — actions will not be recorded: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** Appends one row. Throws on a database failure; the service decides what to do with that. */
    public void append(long at, String organizationId, String actorEmail, String actorRole,
                       String kind, String subjectType, String subjectId, String subjectLabel,
                       String detailJson) {
        jdbc.update("""
                insert into audit_events (at, organization_id, actor_email, actor_role, kind,
                  subject_type, subject_id, subject_label, detail_json)
                values (?,?,?,?,?,?,?,?,?)
                """, at, organizationId, actorEmail, actorRole, kind, subjectType, subjectId,
                subjectLabel, detailJson);
    }

    /**
     * The newest {@code limit} rows matching the filter, newest first, all with an id below
     * {@code beforeId} when one is given — "the next page" is this call again with the last id
     * of the page just read.
     */
    public List<AuditEvent> list(String organizationId, Filter filter, Long beforeId, int limit) {
        if (!isAvailable()) return List.of();
        StringBuilder sql = new StringBuilder("select * from audit_events where organization_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(organizationId);
        appendFilter(sql, args, filter);
        if (beforeId != null) {
            sql.append(" and id < ?");
            args.add(beforeId);
        }
        sql.append(" order by id desc limit ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> row(rs), args.toArray());
    }

    /**
     * Every row in the filter, oldest first, handed to {@code sink} one at a time — the export
     * reads a year of trail without holding a year of it in memory.
     */
    public void forEach(String organizationId, Filter filter, Consumer<AuditEvent> sink) {
        if (!isAvailable()) return;
        StringBuilder sql = new StringBuilder("select * from audit_events where organization_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(organizationId);
        appendFilter(sql, args, filter);
        sql.append(" order by id asc");
        jdbc.query(sql.toString(), rs -> {
            sink.accept(row(rs));
        }, args.toArray());
    }

    /** Removes every row older than {@code cutoffMillis}, on every organization; how many went. */
    public int deleteOlderThan(long cutoffMillis) {
        if (!isAvailable()) return 0;
        return jdbc.update("delete from audit_events where at < ?", cutoffMillis);
    }

    private static void appendFilter(StringBuilder sql, List<Object> args, Filter filter) {
        if (filter == null) return;
        if (filter.actor() != null && !filter.actor().isBlank()) {
            // A substring, case-blind: "gerard" finds gerard@tecnovent.com, and "system:" finds
            // every action nobody was signed in for.
            sql.append(" and lower(actor_email) like ?");
            args.add("%" + filter.actor().trim().toLowerCase() + "%");
        }
        if (filter.kind() != null && !filter.kind().isBlank()) {
            sql.append(" and kind = ?");
            args.add(filter.kind().trim());
        }
        if (filter.fromMillis() != null) {
            sql.append(" and at >= ?");
            args.add(filter.fromMillis());
        }
        if (filter.toMillis() != null) {
            sql.append(" and at <= ?");
            args.add(filter.toMillis());
        }
    }

    private static AuditEvent row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AuditEvent(rs.getLong("id"), rs.getLong("at"), rs.getString("actor_email"),
                rs.getString("actor_role"), rs.getString("kind"), rs.getString("subject_type"),
                rs.getString("subject_id"), rs.getString("subject_label"),
                rs.getString("detail_json"));
    }
}
