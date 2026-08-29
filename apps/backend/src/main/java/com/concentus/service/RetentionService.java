package com.concentus.service;

import com.concentus.audit.AuditKinds;
import com.concentus.audit.AuditService;
import com.concentus.config.Settings;
import com.concentus.license.Feature;
import com.concentus.license.LicenseService;
import com.concentus.store.AuditStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * What this deployment keeps, for how long, and the nightly job that enforces it.
 *
 * <p>Three answers, decided by the license and nothing else:
 * <ul>
 *   <li><b>Team</b> keeps {@link Feature#TEAM_RETENTION_DAYS} days of runs, flow versions and audit
 *       trail. That is the cap the tier carries; unlimited retention is one of the things an
 *       organization asks for and a team of five never does.</li>
 *   <li><b>Enterprise</b> keeps everything — unless an administrator chose a shorter window under
 *       Settings ({@link #SETTING_ENTERPRISE_DAYS}), which a data-protection policy sometimes
 *       requires. The setting is read only here, on the tier that owns it: below Enterprise it is
 *       ignored, because a Team deployment cannot buy its way past ninety days by typing a bigger
 *       number, and a free one has nothing to purge.</li>
 *   <li><b>Free</b> — one person on their own machine — purges nothing. Nothing in the license
 *       gates reaches somebody's own disk.</li>
 * </ul>
 *
 * <p>What a purge spares: a golden run (it is the reference the flow is compared against, and
 * letting it age out would disable that comparison on the flows that run most), the current
 * version of every flow (it IS the flow), and the version a golden run executed (the comparison
 * needs both halves). Runs carry their events and node executions as columns, so deleting the
 * row is deleting all of it.
 *
 * <p>The purge writes its own audit row — how many of each went, under which window — credited
 * to {@code system:retention}. A trail that lost ninety days of rows overnight and did not say
 * so would look like tampering to exactly the person it exists for.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    /** Days an Enterprise deployment keeps; 0 (the default) is forever. Ignored below Enterprise. */
    public static final String SETTING_ENTERPRISE_DAYS = "retention.enterprise-days";

    /** The system actor the purge is credited to. */
    static final String TRIGGER = "retention";

    /** The window in force and why — what the panel prints. {@code days} null means forever. */
    public record Policy(Integer days, String reason) {
        public boolean purges() {
            return days != null;
        }
    }

    /** What one purge removed. All zeros when the policy keeps everything. */
    public record Report(Integer days, int runs, int versions, int auditEvents) {
        public int total() {
            return runs + versions + auditEvents;
        }
    }

    private final JdbcTemplate jdbc;
    private final AuditStore auditStore;
    private final AuditService audit;
    private final LicenseService license;
    private final Settings settings;
    private final RunService runService;
    private final Clock clock;

    // @Autowired is load-bearing: the constructor below is a second one, and Spring faced with two
    // picks neither.
    @Autowired
    public RetentionService(JdbcTemplate jdbc, AuditStore auditStore, AuditService audit,
                            LicenseService license, Settings settings, RunService runService) {
        this(jdbc, auditStore, audit, license, settings, runService, Clock.systemUTC());
    }

    /** For tests: an injectable clock, so "older than ninety days" is a fixed fact. */
    RetentionService(JdbcTemplate jdbc, AuditStore auditStore, AuditService audit,
                     LicenseService license, Settings settings, RunService runService, Clock clock) {
        this.jdbc = jdbc;
        this.auditStore = auditStore;
        this.audit = audit;
        this.license = license;
        this.settings = settings;
        this.runService = runService;
        this.clock = clock;
    }

    /** The window in force right now, decided from the license as it stands at this moment. */
    public Policy policy() {
        if (license.allows(Feature.UNLIMITED_RETENTION)) {
            int chosen = enterpriseDays();
            if (chosen > 0) {
                return new Policy(chosen, "Enterprise license: an administrator chose to keep "
                        + chosen + " days of runs, flow versions and audit trail (Settings → Retention).");
            }
            return new Policy(null, "Enterprise license: runs, flow versions and the audit trail "
                    + "are kept without limit.");
        }
        if (license.teamTier()) {
            return new Policy(Feature.TEAM_RETENTION_DAYS, "Team license: runs, flow versions and the "
                    + "audit trail are kept for " + Feature.TEAM_RETENTION_DAYS + " days. "
                    + Feature.UNLIMITED_RETENTION.label + " is an Enterprise feature.");
        }
        return new Policy(null, "No paid license: this is a single-person installation and "
                + "nothing on it is purged.");
    }

    /**
     * Every night at 03:17 — an odd minute on purpose, so it does not land on the hour with
     * every cron flow somebody scheduled at three. A failure is logged and tried again tomorrow;
     * there is nothing to retry sooner for.
     */
    @Scheduled(cron = "0 17 3 * * *")
    public void nightly() {
        try {
            Report report = runNow();
            if (report.total() > 0) {
                log.info("Retention purged {} runs, {} flow versions and {} audit events older than "
                        + "{} days.", report.runs(), report.versions(), report.auditEvents(),
                        report.days());
            }
        } catch (Exception e) {
            log.warn("The nightly retention purge failed: {}", e.getMessage());
        }
    }

    /** Applies the policy now and reports what went. Zeros, and no audit row, when it keeps all. */
    public Report runNow() {
        Policy policy = policy();
        if (!policy.purges()) return new Report(null, 0, 0, 0);
        long cutoff = clock.millis() - Duration.ofDays(policy.days()).toMillis();

        int runs = jdbc.update("delete from runs where created_at < ? and not golden", cutoff);
        // The in-memory registry mirrors the table; a run gone from one and not the other would
        // reappear on the next persist, since the store's write is an upsert.
        runService.forgetOlderThan(cutoff);
        int versions = jdbc.update("""
                delete from flow_versions v
                 where v.created_at < ?
                   and v.version < (select max(m.version) from flow_versions m where m.flow_id = v.flow_id)
                   and not exists (select 1 from runs r
                                    where r.golden and r.flow_id = v.flow_id and r.flow_version = v.version)
                """, cutoff);
        int auditEvents = auditStore.deleteOlderThan(cutoff);

        Report report = new Report(policy.days(), runs, versions, auditEvents);
        if (report.total() > 0) {
            audit.recordSystem(TRIGGER, AuditKinds.RETENTION_PURGED, "retention", null,
                    policy.days() + " days", Map.of("days", policy.days(), "runs", runs,
                            "versions", versions, "auditEvents", auditEvents));
        }
        return report;
    }

    /**
     * The administrator's chosen window, clamped to "unset" when it is blank or not a number.
     * Resolved for the current organization — the default one when the nightly job asks, since
     * it runs with no principal — which on every deployment but a multi-tenant one is the same.
     */
    private int enterpriseDays() {
        try {
            return Math.max(0, Integer.parseInt(settings.get(SETTING_ENTERPRISE_DAYS, "0").trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
