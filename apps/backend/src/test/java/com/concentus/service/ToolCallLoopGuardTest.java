package com.concentus.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When an agent has stopped getting anywhere, and — more important — when it has not.
 *
 * <p>The false positive is the expensive one. A guard that interrupts a legitimate poll turns a
 * cost-saving measure into a way to break a run that was about to succeed, so most of what is
 * asserted here is what the guard must leave alone.
 */
class ToolCallLoopGuardTest {

    private final ToolCallLoopGuard guard = new ToolCallLoopGuard();
    private static final String CALLER = ToolCallLoopGuard.key("run1", "node1");

    private void call(String tool, String args, String result) {
        guard.record(CALLER, tool, args, result);
    }

    @Test
    void aRetryOrTwoIsNotALoop() {
        call("search", "{\"q\":\"x\"}", "nothing found");
        assertThat(guard.refuse(CALLER, "search", "{\"q\":\"x\"}")).isEmpty();
        call("search", "{\"q\":\"x\"}", "nothing found");
        assertThat(guard.refuse(CALLER, "search", "{\"q\":\"x\"}")).isEmpty();
    }

    @Test
    void theSameQuestionGettingTheSameAnswerIsRefusedAndToldWhy() {
        for (int i = 0; i < ToolCallLoopGuard.LIMIT; i++) {
            call("search", "{\"q\":\"x\"}", "nothing found");
        }

        assertThat(guard.refuse(CALLER, "search", "{\"q\":\"x\"}"))
                .get()
                .asString()
                // The message has to say what it has been doing, not merely that it may not.
                .contains("search")
                .contains("same answer");
    }

    @Test
    void aPollWhoseAnswerChangesIsNeverRefused() {
        // get_status(id=5) until the job finishes is exactly right, and the whole reason the rule
        // is "same result" rather than "same arguments".
        for (int i = 0; i < 20; i++) {
            call("get_status", "{\"id\":5}", "still running, " + i + "%");
            assertThat(guard.refuse(CALLER, "get_status", "{\"id\":5}")).isEmpty();
        }
    }

    @Test
    void refusingResetsTheCountSoAWaitingAgentIsDelayedRatherThanStopped() {
        for (int i = 0; i < ToolCallLoopGuard.LIMIT; i++) call("poll", "{}", "not yet");
        assertThat(guard.refuse(CALLER, "poll", "{}")).isPresent();

        // Immediately after: allowed through again. A wall would break a run that was about to
        // succeed; a speed bump costs it one call every few.
        assertThat(guard.refuse(CALLER, "poll", "{}")).isEmpty();
    }

    @Test
    void differentArgumentsClearIt() {
        for (int i = 0; i < ToolCallLoopGuard.LIMIT; i++) call("search", "{\"q\":\"x\"}", "nothing");
        call("search", "{\"q\":\"y\"}", "nothing");

        assertThat(guard.refuse(CALLER, "search", "{\"q\":\"y\"}")).isEmpty();
    }

    @Test
    void oneWorkerLoopingDoesNotSilenceAnother() {
        String other = ToolCallLoopGuard.key("run1", "node2");
        for (int i = 0; i < ToolCallLoopGuard.LIMIT; i++) call("search", "{\"q\":\"x\"}", "nothing");

        // Two workers asking the same question of the same tool are two independent agents.
        assertThat(guard.refuse(other, "search", "{\"q\":\"x\"}")).isEmpty();
    }

    @Test
    void aToolFailingIdenticallyCountsToo() {
        for (int i = 0; i < ToolCallLoopGuard.LIMIT; i++) {
            call("post", "{\"body\":1}", "The call failed: connection refused");
        }

        // There is as little to learn from the fortieth identical failure as from the fortieth
        // identical success.
        assertThat(guard.refuse(CALLER, "post", "{\"body\":1}")).isPresent();
    }

    @Test
    void aRunThatEndsIsForgotten() {
        for (int i = 0; i < ToolCallLoopGuard.LIMIT; i++) call("search", "{}", "nothing");
        assertThat(guard.repeats(CALLER)).isEqualTo(ToolCallLoopGuard.LIMIT);

        guard.forget("run1");

        assertThat(guard.repeats(CALLER)).isZero();
        // And a run whose id merely starts the same is left alone.
        assertThat(guard.repeats(ToolCallLoopGuard.key("run10", "node1"))).isZero();
    }
}
