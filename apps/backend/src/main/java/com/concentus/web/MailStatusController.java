package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.mail.MailPollStatus;
import com.concentus.mail.MailTriggerService;
import com.concentus.mail.MailTriggerSpec;
import com.concentus.model.FlowGraph;
import com.concentus.model.TriggerSpec;
import com.concentus.store.FlowStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Answers "is this mail trigger actually working?".
 *
 * <p>A poller succeeds by doing nothing most of the time, so a correctly configured trigger
 * watching a quiet folder and a completely broken one produce the same silence. The only evidence
 * otherwise is a log line on a server nobody is tailing — which is why this exists at all.
 */
@RestController
@RequestMapping("/api/mail")
public class MailStatusController {

    private final FlowStore flows;
    private final MailPollStatus status;
    private final MailTriggerService triggers;
    private final OrgContext orgContext;

    public MailStatusController(FlowStore flows, MailPollStatus status, MailTriggerService triggers,
                                OrgContext orgContext) {
        this.flows = flows;
        this.status = status;
        this.triggers = triggers;
        this.orgContext = orgContext;
    }

    @GetMapping("/status/{flowId}")
    public Map<String, Object> status(@PathVariable String flowId) {
        FlowGraph flow = flows.get(flowId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        if (flow == null) {
            out.put("state", "unknown");
            out.put("detail", "Save the flow first — a trigger only runs for a saved flow.");
            return out;
        }

        MailTriggerSpec spec = MailTriggerSpec.from(flow);
        if (!TriggerSpec.from(flow).mail()) {
            out.put("state", "off");
            out.put("detail", "This flow does not start from mail.");
            return out;
        }
        out.put("folder", spec.folder());
        out.put("host", spec.host());
        out.put("pollSeconds", spec.pollSeconds());
        out.put("runsStarted", status.runsStarted(flowId));

        if (!spec.isConfigured()) {
            out.put("state", "incomplete");
            out.put("detail", "Not polling — still missing: " + spec.missingFields() + ".");
            return out;
        }
        if (!flow.isEnabled()) {
            out.put("state", "paused");
            out.put("detail", "This flow is paused, so its mailbox is not being polled.");
            return out;
        }

        var last = status.lastPoll(flowId).orElse(null);
        if (last == null) {
            // Configured and scheduled, but the first tick has not landed yet. Saying "waiting" is
            // more honest than an empty panel that reads as broken.
            out.put("state", "waiting");
            out.put("detail", "Scheduled. Waiting for the first poll (every " + spec.pollSeconds() + "s).");
            return out;
        }
        out.put("state", last.ok() ? "ok" : "error");
        out.put("detail", last.detail());
        out.put("at", last.at());
        out.put("matched", last.matched());
        return out;
    }

    /**
     * Polls right now instead of waiting for the next tick.
     *
     * <p>Configuring a trigger is a trial-and-error loop — folder name, conditions, credentials —
     * and a minute of silence per attempt makes it unusable.
     */
    @PostMapping("/poll/{flowId}")
    public Map<String, Object> pollNow(@PathVariable String flowId) {
        orgContext.requireAdmin();
        triggers.pollNow(flowId);
        return status(flowId);
    }
}
