package com.concentus.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The record of what the poller last did.
 *
 * <p>It exists because a poller is invisible when it works: a healthy trigger on a quiet folder and
 * a broken one both produce silence, and telling them apart is the whole point.
 */
class MailPollStatusTest {

    @Test
    void aFlowThatHasNeverPolledHasNoStatus() {
        assertThat(new MailPollStatus().lastPoll("flow-1")).isEmpty();
    }

    @Test
    void aSuccessfulPollIsRecordedWithItsCounts() {
        MailPollStatus status = new MailPollStatus();

        status.recordPoll("flow-1", 2, 7, "2 new message(s) matched");

        MailPollStatus.Poll poll = status.lastPoll("flow-1").orElseThrow();
        assertThat(poll.ok()).isTrue();
        assertThat(poll.matched()).isEqualTo(2);
        assertThat(poll.scanned()).isEqualTo(7);
        assertThat(poll.at()).isPositive();
    }

    @Test
    void aFailureReplacesTheLastSuccess() {
        // The panel answers "is it working right now", so the newest outcome is the only one that
        // matters — a stale success would say yes while the mailbox is unreachable.
        MailPollStatus status = new MailPollStatus();
        status.recordPoll("flow-1", 1, 1, "fine");

        status.recordFailure("flow-1", "AUTHENTICATE failed");

        MailPollStatus.Poll poll = status.lastPoll("flow-1").orElseThrow();
        assertThat(poll.ok()).isFalse();
        assertThat(poll.detail()).contains("AUTHENTICATE failed");
    }

    @Test
    void startedRunsAccumulateAcrossPolls() {
        MailPollStatus status = new MailPollStatus();

        status.recordRunStarted("flow-1");
        status.recordRunStarted("flow-1");

        assertThat(status.runsStarted("flow-1")).isEqualTo(2);
    }

    @Test
    void flowsDoNotSeeEachOthersStatus() {
        MailPollStatus status = new MailPollStatus();
        status.recordPoll("flow-1", 1, 1, "one");
        status.recordRunStarted("flow-1");

        assertThat(status.lastPoll("flow-2")).isEmpty();
        assertThat(status.runsStarted("flow-2")).isZero();
    }

    @Test
    void forgettingAFlowClearsBothItsPollAndItsCount() {
        MailPollStatus status = new MailPollStatus();
        status.recordPoll("flow-1", 1, 1, "one");
        status.recordRunStarted("flow-1");

        status.forget("flow-1");

        assertThat(status.lastPoll("flow-1")).isEmpty();
        assertThat(status.runsStarted("flow-1")).isZero();
    }
}
