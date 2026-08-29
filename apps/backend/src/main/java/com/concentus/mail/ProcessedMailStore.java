package com.concentus.mail;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Remembers which messages a flow has already been started for.
 *
 * <p>Without this, a poller is a duplicate machine: any message the conditions still match on the
 * next tick starts another run. Marking mail read or moving it usually prevents that, but not
 * always — a run can fail before filing, a human can mark something unread, and a folder move can
 * be undone. So the record is kept independently of the mailbox's own state.
 *
 * <p>Keyed on the RFC 5322 {@code Message-ID}, which survives the folder move this trigger
 * performs, rather than on the IMAP UID, which does not.
 *
 * <p>Claiming is an insert against a unique index, not a read-then-write. Two poll ticks
 * overlapping on a slow IMAP server is exactly the case that matters, and only the database can
 * settle it.
 */
@Component
public class ProcessedMailStore {

    private static final Logger log = LoggerFactory.getLogger(ProcessedMailStore.class);

    /** How many recent identities a poll pre-loads to filter with before claiming. */
    private static final int RECENT_WINDOW = 500;

    private final JdbcTemplate jdbc;
    private volatile boolean available;

    public ProcessedMailStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        // Created by the migrations; this only checks it arrived.
        try {
            jdbc.queryForObject("select count(*) from processed_mail", Integer.class);
            available = true;
            log.info("Processed-mail store ready (PostgreSQL).");
        } catch (Exception e) {
            available = false;
            log.error("Processed-mail store unavailable — mail triggers will not run, because "
                    + "polling without it would start a run per tick for the same message: {}",
                    e.getMessage());
        }
    }

    /**
     * False when the table the migrations should have created is not there.
     *
     * <p>The poller refuses to run in that state. A mail trigger with no memory would re-launch
     * an agent for the same message every minute, which is worse than not running at all.
     */
    public boolean isAvailable() {
        return available;
    }

    /** Identities seen recently, used to filter a fetch before anything is claimed. */
    public Set<String> recentIdentities(String flowId, String folder) {
        if (!available) return Set.of();
        try {
            List<String> ids = jdbc.queryForList("""
                    select identity from processed_mail
                    where flow_id = ? and folder = ?
                    order by created_at desc limit ?
                    """, String.class, flowId, folder, RECENT_WINDOW);
            return new LinkedHashSet<>(ids);
        } catch (Exception e) {
            log.warn("Could not read processed mail: {}", e.getMessage());
            // An empty set means "nothing is filtered", and claim() still rejects duplicates —
            // so a read failure costs a wasted fetch, never a duplicate run.
            return Set.of();
        }
    }

    /**
     * Claims a message. Returns false when it was already claimed, by an earlier tick or by
     * another thread racing this one.
     */
    public boolean claim(String flowId, String folder, String identity, String subject, String sender) {
        if (!available) return false;
        try {
            jdbc.update("""
                    insert into processed_mail (flow_id, folder, identity, subject, sender, created_at)
                    values (?,?,?,?,?,?)
                    """, flowId, folder, identity, subject, sender, System.currentTimeMillis());
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        } catch (Exception e) {
            // Fail closed: if the claim could not be recorded, starting the run would risk
            // starting it again on the next tick.
            log.warn("Could not claim message for flow {}: {}", flowId, e.getMessage());
            return false;
        }
    }

    /** Links the claim to the run it started, so history can be traced back from the mailbox. */
    public void recordRun(String flowId, String folder, String identity, String runId) {
        if (!available) return;
        try {
            jdbc.update("update processed_mail set run_id = ? where flow_id = ? and folder = ? "
                    + "and identity = ?", runId, flowId, folder, identity);
        } catch (Exception e) {
            log.debug("Could not record the run id for a processed message: {}", e.getMessage());
        }
    }

    /**
     * Releases a claim, so the message is eligible again.
     *
     * <p>Used when starting the run failed after claiming. Leaving the claim in place would mean
     * a message that was never processed is never looked at again.
     */
    public void release(String flowId, String folder, String identity) {
        if (!available) return;
        try {
            jdbc.update("delete from processed_mail where flow_id = ? and folder = ? and identity = ?",
                    flowId, folder, identity);
        } catch (Exception e) {
            log.debug("Could not release a mail claim: {}", e.getMessage());
        }
    }

    /** Drops records older than {@code olderThanMs}, so the table does not grow forever. */
    public int purgeOlderThan(long olderThanMs) {
        if (!available) return 0;
        try {
            return jdbc.update("delete from processed_mail where created_at < ?",
                    System.currentTimeMillis() - olderThanMs);
        } catch (Exception e) {
            return 0;
        }
    }
}
