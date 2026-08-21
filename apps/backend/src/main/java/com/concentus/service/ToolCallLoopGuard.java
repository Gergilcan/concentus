package com.concentus.service;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Noticing when an agent has stopped getting anywhere.
 *
 * <p>An agent that calls the same tool with the same arguments and receives the same answer, over
 * and over, is not working. Nothing stopped it: a worker had fifteen minutes of timeout and a
 * coordinator had none at all, and the bill is the same whether those minutes were spent thinking
 * or spent asking one question forty times. It is not a rare failure either — it is what a model
 * does when a tool's answer does not contain what it was told to look for, and it decides the
 * problem was how it asked.
 *
 * <p><b>The rule is same tool, same arguments, AND same result.</b> Arguments alone would catch a
 * legitimate poll — {@code get_status(id=5)} until the job finishes is exactly right, and its
 * answers differ each time. Only when the answer has not changed either is there provably no
 * progress being made, which is the difference between waiting and looping.
 *
 * <p><b>A speed bump, not a wall.</b> At the limit the call is refused once, with a message
 * telling the agent what it has been doing, and the count resets. An agent genuinely waiting on
 * something gets to carry on; one that is stuck is interrupted every few calls with the reason
 * rather than running until a timeout nobody is watching. Refusing forever would turn a
 * cost-saving measure into a way to break a run that was about to succeed.
 */
@Component
public class ToolCallLoopGuard {

    /**
     * Identical, answer-and-all, calls tolerated before one is refused.
     *
     * <p>Three, because two is a retry — the first call failed, or the agent wanted to be sure —
     * and by the fourth identical answer there is nothing left to learn from a fifth.
     */
    static final int LIMIT = 3;

    private final ConcurrentHashMap<String, State> byCaller = new ConcurrentHashMap<>();

    private static final class State {
        String tool;
        int argsHash;
        int resultHash;
        int repeats;
    }

    /**
     * Whether this call should be refused, and what to tell the agent if so.
     *
     * @param callerKey the run and the box within it. Per box, because two workers asking the same
     *                  question of the same tool are two independent agents, not a loop
     */
    public Optional<String> refuse(String callerKey, String tool, String arguments) {
        State state = byCaller.get(callerKey);
        if (state == null || !Objects.equals(state.tool, tool)
                || state.argsHash != hash(arguments) || state.repeats < LIMIT) {
            return Optional.empty();
        }
        // Reset on refusal: the next few calls are allowed through, so an agent that really is
        // waiting for something is delayed rather than stopped.
        state.repeats = 0;
        return Optional.of("You have called " + tool + " " + LIMIT + " times with these exact "
                + "arguments and received the same answer every time. It will not change. Either "
                + "ask something different, use what you already have, or say plainly that this "
                + "tool cannot answer the question — repeating the call is not going to work.");
    }

    /**
     * Records what a call was and what it returned.
     *
     * <p>Called after every completed call, including failures: a tool erroring identically forty
     * times is the same waste as one answering identically forty times, and the agent has as
     * little to learn from it.
     */
    public void record(String callerKey, String tool, String arguments, String result) {
        State state = byCaller.computeIfAbsent(callerKey, k -> new State());
        int args = hash(arguments);
        int res = hash(result);
        synchronized (state) {
            if (Objects.equals(state.tool, tool) && state.argsHash == args && state.resultHash == res) {
                state.repeats++;
            } else {
                state.tool = tool;
                state.argsHash = args;
                state.resultHash = res;
                state.repeats = 1;
            }
        }
    }

    /** How many identical calls in a row this caller has made, for the run log to say so. */
    public int repeats(String callerKey) {
        State state = byCaller.get(callerKey);
        return state == null ? 0 : state.repeats;
    }

    /** Everything this run was tracking. Called when it ends, so a long-lived backend does not grow. */
    public void forget(String runId) {
        byCaller.keySet().removeIf(k -> k.startsWith(runId + "/"));
    }

    /** The run and the box. */
    public static String key(String runId, String nodeId) {
        return runId + "/" + (nodeId == null ? "-" : nodeId);
    }

    private static int hash(String value) {
        return value == null ? 0 : value.hashCode();
    }
}
