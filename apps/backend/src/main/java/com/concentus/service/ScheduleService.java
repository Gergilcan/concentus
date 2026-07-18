package com.concentus.service;

import com.concentus.model.FlowGraph;
import com.concentus.model.TriggerSpec;
import com.concentus.store.FlowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 * Runs saved flows automatically when their {@code input} node uses {@code cron} mode. Rebuilt on
 * startup and whenever a flow is saved/deleted (see {@code FlowController}).
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    private final FlowStore flows;
    private final RunService runService;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final Map<String, ScheduledFuture<?>> jobs = new HashMap<>();

    public ScheduleService(FlowStore flows, RunService runService) {
        this.flows = flows;
        this.runService = runService;
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("flow-cron-");
        scheduler.initialize();
    }

    /** Cancel all jobs and re-register from the current set of saved flows. Idempotent. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void reschedule() {
        jobs.values().forEach(f -> f.cancel(false));
        jobs.clear();
        for (FlowGraph flow : flows.list()) {
            TriggerSpec t = TriggerSpec.from(flow);
            if (flow.id() == null || !t.scheduled()) continue;
            if (!flow.isEnabled()) {
                log.info("Flow '{}' is paused — not scheduling.", flow.name());
                continue;
            }
            try {
                String flowId = flow.id();
                ScheduledFuture<?> f = scheduler.schedule(() -> fire(flowId), new CronTrigger(normalize(t.cron())));
                if (f != null) {
                    jobs.put(flowId, f);
                    log.info("Scheduled flow '{}' ({}) on cron '{}'", flow.name(), flowId, t.cron());
                }
            } catch (Exception e) {
                log.warn("Ignoring bad cron for flow {}: {}", flow.id(), e.getMessage());
            }
        }
    }

    private void fire(String flowId) {
        flows.get(flowId).ifPresent(flow -> {
            if (runService.hasActiveRun(flowId)) {
                log.info("Scheduled tick for '{}' skipped — a run is still active.", flow.name());
                return;
            }
            try {
                runService.start(flow);
                log.info("Scheduled run started for flow '{}'.", flow.name());
            } catch (Exception e) {
                log.warn("Scheduled run for '{}' failed to start: {}", flow.name(), e.getMessage());
            }
        });
    }

    /** Spring cron expects 6 fields (leading seconds). Accept the common 5-field form by prefixing "0 ". */
    static String normalize(String cron) {
        String c = cron.trim();
        return c.split("\\s+").length == 5 ? "0 " + c : c;
    }
}
