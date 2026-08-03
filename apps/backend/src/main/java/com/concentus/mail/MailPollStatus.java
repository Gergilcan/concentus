package com.concentus.mail;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the mail poller last did, per flow, so the app can answer "is this working?".
 *
 * <p>A poller is invisible by nature: it succeeds by doing nothing most of the time. Without this,
 * a correctly-configured trigger with an empty folder and a completely broken one look identical —
 * both produce silence — and the only evidence is a log line on a server nobody is tailing.
 *
 * <p>In memory and not persisted: it describes the <em>current</em> process, and a value surviving
 * a restart would claim a poll that this process never ran.
 */
@Component
public class MailPollStatus {

    /**
     * The outcome of one poll.
     *
     * @param at        when the poll finished
     * @param ok        false when the poll could not be completed at all
     * @param matched   messages that matched and started runs this poll
     * @param scanned   messages the server returned before local filtering, so "the folder isn't
     *                  empty, your conditions just exclude everything" is distinguishable from
     *                  "the folder is empty"
     * @param detail    a human-readable summary, or the error when {@code ok} is false
     */
    public record Poll(long at, boolean ok, int matched, int scanned, String detail) {
    }

    private final Map<String, Poll> byFlow = new ConcurrentHashMap<>();
    /** Runs started since this process began, per flow — the number that says it is really working. */
    private final Map<String, Integer> startedByFlow = new ConcurrentHashMap<>();

    public void recordPoll(String flowId, int matched, int scanned, String detail) {
        byFlow.put(flowId, new Poll(System.currentTimeMillis(), true, matched, scanned, detail));
    }

    public void recordFailure(String flowId, String error) {
        byFlow.put(flowId, new Poll(System.currentTimeMillis(), false, 0, 0, error));
    }

    public void recordRunStarted(String flowId) {
        startedByFlow.merge(flowId, 1, Integer::sum);
    }

    public Optional<Poll> lastPoll(String flowId) {
        return Optional.ofNullable(byFlow.get(flowId));
    }

    public int runsStarted(String flowId) {
        return startedByFlow.getOrDefault(flowId, 0);
    }

    /** Forgets a flow, so a deleted or reconfigured trigger does not report a stale poll. */
    public void forget(String flowId) {
        byFlow.remove(flowId);
        startedByFlow.remove(flowId);
    }
}
