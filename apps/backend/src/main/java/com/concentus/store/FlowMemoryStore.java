package com.concentus.store;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Notes a flow's agents leave for their future selves — the persistence behind the
 * {@code memory_read} / {@code memory_append} run tools.
 *
 * <p>Keyed by flow id, not run id, deliberately: a run is one execution and dies with it; the whole
 * point of a note is to be found by the <em>next</em> execution of the same flow. Ad-hoc runs of an
 * unsaved canvas have no flow id and therefore no memory — there is no stable identity for the
 * notes to belong to.
 *
 * <p>Reads are best-effort like every store here (an unreachable database returns nothing rather
 * than breaking the app), but {@link #append} propagates its failure: an agent told "your note was
 * saved" when it was not would act on that lie next run.
 */
@Component
public class FlowMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(FlowMemoryStore.class);

    /**
     * Longest note accepted. A limit the tool reports honestly, so the agent shortens the note
     * itself rather than us truncating it mid-sentence and storing something it never said.
     */
    public static final int MAX_NOTE_CHARS = 4000;

    /** One note, as stored. */
    public record Note(long id, String runId, String note, long createdAt) {
    }

    private final JdbcTemplate jdbc;
    private volatile boolean available;

    public FlowMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        try {
            jdbc.queryForObject("select count(*) from flow_memory", Integer.class);
            available = true;
            log.info("Flow memory ready (PostgreSQL).");
        } catch (Exception e) {
            available = false;
            log.warn("Flow memory unavailable — the memory tools will say so: {}", e.getMessage());
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /** The newest {@code limit} notes, newest first (what a UI shows at the top). */
    public List<Note> latest(String flowId, int limit) {
        if (!available) return List.of();
        try {
            return jdbc.query(
                    "select id, run_id, note, created_at from flow_memory"
                            + " where flow_id = ? order by id desc limit ?",
                    (rs, i) -> new Note(rs.getLong("id"), rs.getString("run_id"),
                            rs.getString("note"), rs.getLong("created_at")),
                    flowId, limit);
        } catch (Exception e) {
            log.debug("flow memory read failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** The newest {@code limit} notes in chronological order — how an agent should read them. */
    public List<Note> recent(String flowId, int limit) {
        List<Note> notes = new ArrayList<>(latest(flowId, limit));
        Collections.reverse(notes);
        return notes;
    }

    public int count(String flowId) {
        if (!available) return 0;
        try {
            Integer n = jdbc.queryForObject(
                    "select count(*) from flow_memory where flow_id = ?", Integer.class, flowId);
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.debug("flow memory count failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Appends one note. Throws on failure — see the class comment — and validates here rather than
     * only at the tool layer, so a future second caller cannot quietly skip the limits.
     */
    public void append(String flowId, String runId, String note) {
        if (flowId == null || flowId.isBlank()) {
            throw new IllegalArgumentException("Only saved flows have a memory.");
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("A non-empty note is required.");
        }
        if (note.length() > MAX_NOTE_CHARS) {
            throw new IllegalArgumentException("The note is " + note.length() + " characters; the "
                    + "limit is " + MAX_NOTE_CHARS + ". Shorten it to what a future run needs.");
        }
        jdbc.update("insert into flow_memory (flow_id, run_id, note, created_at) values (?,?,?,?)",
                flowId, runId, note.strip(), System.currentTimeMillis());
    }

    /** Deletes every note of a flow — the user's reset button, not something agents can call. */
    public void clear(String flowId) {
        if (!available) return;
        try {
            jdbc.update("delete from flow_memory where flow_id = ?", flowId);
        } catch (Exception e) {
            log.debug("flow memory clear failed: {}", e.getMessage());
        }
    }
}
