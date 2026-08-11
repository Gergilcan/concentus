package com.concentus.mail;

import com.concentus.auth.OrgContext;
import com.concentus.integration.Redact;
import com.concentus.model.FlowGraph;
import com.concentus.model.RunSummary;
import com.concentus.model.TriggerSpec;
import com.concentus.service.RunService;
import com.concentus.store.FlowStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

/**
 * Starts runs when mail matching a flow's conditions arrives in an IMAP folder.
 *
 * <p>The mail counterpart of {@code ScheduleService}: registered from the saved flows on startup
 * and rebuilt whenever a flow is saved or deleted, on the same {@link ThreadPoolTaskScheduler}
 * mechanism, so the deployment gains no new moving part.
 *
 * <p>Polling rather than IMAP IDLE. IDLE would be a little more immediate, but it needs a held
 * connection per folder and careful re-establishment when the server drops it — for a workflow
 * measured in minutes, a poll every minute is the better trade, and it degrades to "slightly
 * late" rather than "silently stopped".
 */
@Service
public class MailTriggerService {

    private static final Logger log = LoggerFactory.getLogger(MailTriggerService.class);

    /** Processed-mail records older than this are purged; long past a message being re-delivered. */
    private static final long RETENTION_MS = Duration.ofDays(90).toMillis();

    private final FlowStore flows;
    private final RunService runService;
    private final ImapMailReader reader;
    private final MailPromptRenderer renderer;
    private final ProcessedMailStore processed;
    private final MailAuthProvider mailAuth;
    private final MailPollStatus status;
    private final OrgContext orgContext;
    private final boolean enabled;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final Map<String, ScheduledFuture<?>> jobs = new HashMap<>();

    public MailTriggerService(FlowStore flows, RunService runService, ImapMailReader reader,
                              MailPromptRenderer renderer, ProcessedMailStore processed,
                              MailAuthProvider mailAuth, MailPollStatus status, OrgContext orgContext,
                              @Value("${mail.triggers-enabled:true}") boolean enabled,
                              @Value("${mail.poller-threads:2}") int threads) {
        this.flows = flows;
        this.runService = runService;
        this.reader = reader;
        this.renderer = renderer;
        this.processed = processed;
        this.mailAuth = mailAuth;
        this.status = status;
        this.orgContext = orgContext;
        this.enabled = enabled;
        scheduler.setPoolSize(Math.max(1, threads));
        scheduler.setThreadNamePrefix("mail-trigger-");
        scheduler.initialize();
    }

    /** Cancels every poller and re-registers from the current set of saved flows. Idempotent. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void reschedule() {
        jobs.values().forEach(f -> f.cancel(false));
        jobs.clear();
        if (!enabled) {
            log.info("Mail triggers disabled (mail.triggers-enabled=false).");
            return;
        }

        for (FlowGraph flow : flows.list()) {
            if (flow.id() == null || !TriggerSpec.from(flow).mail()) continue;
            if (!flow.enabledOrDefault()) {
                log.info("Flow '{}' is paused — not polling its mailbox.", flow.name());
                continue;
            }
            MailTriggerSpec spec = MailTriggerSpec.from(flow);
            if (!spec.isConfigured()) {
                // Said out loud: a half-configured mail node that silently does nothing is
                // indistinguishable from a mailbox with no new mail. But only half-configured —
                // a trigger with nothing filled in yet is a template or a work in progress, not a
                // mistake, and warning about it on every launch teaches people to skim past
                // warnings that matter.
                boolean untouched = spec.host().isBlank() && spec.username().isBlank()
                        && spec.credentialId().isBlank();
                if (untouched) {
                    log.info("Flow '{}' has a mail trigger that is not configured yet. Not polling.",
                            flow.name());
                } else {
                    log.warn("Flow '{}' has a mail trigger missing: {}. Not polling.",
                            flow.name(), spec.missingFields());
                }
                continue;
            }
            String flowId = flow.id();
            ScheduledFuture<?> job = scheduler.scheduleWithFixedDelay(
                    () -> safely(flowId, () -> poll(flowId)),
                    Duration.ofSeconds(spec.pollSeconds()));
            jobs.put(flowId, job);
            log.info("Polling {}{} for flow '{}' every {}s.",
                    spec.host(), "/" + spec.folder(), flow.name(), spec.pollSeconds());
        }
        if (!jobs.isEmpty()) {
            scheduler.scheduleWithFixedDelay(() -> processed.purgeOlderThan(RETENTION_MS),
                    Duration.ofHours(12));
        }
    }

    /**
     * Polls one flow immediately, out of schedule, recording the outcome like any other poll.
     *
     * <p>For the "Check now" button: configuring a trigger is a trial-and-error loop over folder
     * names, conditions and credentials, and a minute of silence per attempt makes that unusable.
     * Failures are swallowed the same way a scheduled poll's are — the outcome is on the status.
     */
    public void pollNow(String flowId) {
        safely(flowId, () -> poll(flowId));
    }

    /** One poll of one flow's folder. Package-private so tests can drive it deterministically. */
    void poll(String flowId) {
        FlowGraph flow = flows.get(flowId).orElse(null);
        if (flow == null || !flow.enabledOrDefault()) return;
        if (!processed.isAvailable()) {
            // Polling with no memory would start a run per tick for the same message.
            log.warn("Skipping the mail poll for '{}': the processed-mail store is unavailable.",
                    flow.name());
            return;
        }

        MailTriggerSpec spec = MailTriggerSpec.from(flow);
        // A poll has no authenticated principal, so the organization comes from configuration.
        // That matches how flows themselves are stored — shared across the deployment rather than
        // partitioned per tenant — so resolving their credentials anywhere else would be wrong.
        String organizationId = orgContext.defaultOrganizationId();
        MailAuthProvider.MailAuth auth;
        try {
            auth = mailAuth.resolve(spec, organizationId);
        } catch (RuntimeException e) {
            // A master key that changed, or a Microsoft sign-in that was revoked. Both are
            // actionable and neither is fixed by retrying.
            log.warn("Flow '{}': could not obtain mailbox credentials — {}", flow.name(), e.getMessage());
            status.recordFailure(flowId, "Could not obtain mailbox credentials: " + e.getMessage());
            return;
        }
        if (auth == null) {
            log.warn("Flow '{}' references a credential that no longer exists. "
                    + "Re-select one on the Input node. Not polling.", flow.name());
            status.recordFailure(flowId, "This node references a credential that no longer exists. "
                    + "Select one again.");
            return;
        }

        Set<String> seen = processed.recentIdentities(flowId, spec.folder());
        ImapMailReader.Fetched fetched;
        try {
            fetched = reader.fetch(spec, auth, seen, spec.maxPerPoll());
        } catch (ImapMailReader.MailException e) {
            log.warn("Mail poll for '{}' failed: {}", flow.name(), e.getMessage());
            status.recordFailure(flowId, e.getMessage());
            return;
        }

        List<FetchedMail> mails = fetched.mails();
        status.recordPoll(flowId, mails.size(), fetched.matchedSearch(),
                describe(spec, fetched, seen.size()));
        if (mails.isEmpty()) {
            // Every 15 seconds by default, so DEBUG — the same information is on the status the UI
            // reads, which is where someone actually looks for it.
            log.debug("Flow '{}': nothing new in {} ({} in folder, {} matched the conditions).",
                    flow.name(), spec.folder(), fetched.inFolder(), fetched.matchedSearch());
            return;
        }

        log.info("Flow '{}': {} new message(s) matched in {}.", flow.name(), mails.size(), spec.folder());
        String instruction = TriggerSpec.from(flow).prompt();
        for (FetchedMail mail : mails) {
            start(flow, spec, mail, auth, instruction);
        }
    }

    /**
     * A one-line account of a poll, phrased so an empty result explains itself.
     *
     * <p>"No new messages" has three quite different causes, and reporting all three the same way
     * is what makes a working trigger indistinguishable from a broken one.
     */
    private static String describe(MailTriggerSpec spec, ImapMailReader.Fetched fetched, int alreadySeen) {
        if (!fetched.mails().isEmpty()) {
            return fetched.mails().size() + " new message(s) matched — starting a run for each.";
        }
        if (fetched.inFolder() == 0) {
            return "Connected. The folder '" + spec.folder() + "' is empty.";
        }
        if (fetched.matchedSearch() == 0) {
            return "Connected. " + fetched.inFolder() + " message(s) in '" + spec.folder()
                    + "', none matching the conditions on this node.";
        }
        return "Connected. " + fetched.matchedSearch() + " message(s) match the conditions, all "
                + "already processed (" + alreadySeen + " remembered).";
    }

    /**
     * Claims a message, then starts its run.
     *
     * <p>Claim first, deliberately. Starting first and recording afterwards leaves a window in
     * which a second tick sees the same message and launches a duplicate agent — and an agent that
     * writes to a customer's sales system is not something to launch twice.
     */
    private void start(FlowGraph flow, MailTriggerSpec spec, FetchedMail mail,
                       MailAuthProvider.MailAuth auth,
                       String instruction) {
        String identity = mail.identity();
        if (!processed.claim(flow.id(), spec.folder(), identity, mail.subject(),
                Redact.email(mail.from()))) {
            return; // another tick got there first
        }
        try {
            String prompt = renderer.render(mail, instruction, true);
            RunSummary run = runService.start(flow, prompt);
            processed.recordRun(flow.id(), spec.folder(), identity, run.id());
            status.recordRunStarted(flow.id());
            log.info("Mail '{}' started run {} for flow '{}'.",
                    Redact.personalData(String.valueOf(mail.subject())), run.id(), flow.name());

            // Only after the run exists: filing marks the mail handled, and doing it for a run
            // that never started would hide the message from every future poll.
            if (mail.messageId() != null && !mail.messageId().isBlank()) {
                reader.fileMessage(spec, auth, mail.messageId());
            }
        } catch (RuntimeException e) {
            // The claim has to go back, or a message that was never processed is never seen again.
            processed.release(flow.id(), spec.folder(), identity);
            log.warn("Could not start a run for a message in '{}': {}", flow.name(), e.getMessage());
        }
    }

    /**
     * Runs a poll, swallowing failures.
     *
     * <p>An exception escaping a scheduled task cancels its schedule permanently, so one transient
     * IMAP outage would otherwise stop the trigger forever — the exact failure this exists to
     * prevent.
     */
    private void safely(String flowId, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.warn("Mail poll for flow {} failed: {}", flowId, e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }
}
