package com.concentus.service;

import com.concentus.model.NodeExec;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The monthly ceiling, applied while a run is going.
 *
 * <p>A ceiling that only refused the NEXT run let one run spend through it. The check sits on
 * the usage report because that is the moment a number changes; what it decides depends on
 * whether the backend bills at all, which is the difference between a stop and a remark.
 */
class AgentRunBudgetTest {

    private static AgentRun run(boolean bills) {
        AgentRun run = new AgentRun("run-1", "flow_1", "Ads", "local");
        // $3 per million input tokens, $15 per million output: one million output tokens is $15.
        run.pricing = new PricingTable("", 3.0, 15.0);
        run.budgetUsd = 10.0;
        run.spentBeforeUsd = 4.0;
        run.billsPerToken = bills;
        return run;
    }

    private static void spend(AgentRun run, long outputTokens) {
        NodeExec exec = run.nodeExec("a", "agent", "Writer");
        exec.outputTokens += outputTokens;
        run.accrueUsage(0, outputTokens, 0, 0);
    }

    @Test
    void a_billed_run_stops_the_moment_the_month_reaches_the_ceiling() {
        AgentRun run = run(true);
        AtomicInteger stops = new AtomicInteger();
        run.onBudgetExceeded = stops::incrementAndGet;

        spend(run, 200_000); // $3 → $7 of $10: under
        assertThat(run.budgetTripped).isFalse();
        assertThat(stops).hasValue(0);

        spend(run, 200_000); // $6 → $10 of $10: at the ceiling
        assertThat(run.budgetTripped).isTrue();
        assertThat(stops).hasValue(1);
        assertThat(run.error).contains("$10.00 of the $10.00 monthly ceiling");

        spend(run, 200_000); // and never twice
        assertThat(stops).hasValue(1);
    }

    @Test
    void a_subscription_run_is_told_once_and_not_stopped() {
        AgentRun run = run(false);
        AtomicInteger stops = new AtomicInteger();
        run.onBudgetExceeded = stops::incrementAndGet;

        spend(run, 400_000);
        spend(run, 400_000);

        assertThat(run.budgetTripped).isFalse();
        assertThat(stops).hasValue(0);
        assertThat(run.error).isNull();
        assertThat(run.bufferedEvents()).filteredOn(e -> e.text().contains("Not stopped")).hasSize(1);
    }

    @Test
    void a_run_without_a_ceiling_never_looks() {
        AgentRun run = run(true);
        run.budgetUsd = null;
        run.onBudgetExceeded = () -> {
            throw new AssertionError("no ceiling, no stop");
        };

        spend(run, 5_000_000);

        assertThat(run.budgetTripped).isFalse();
    }
}
