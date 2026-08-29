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

    // ---- the organization's ceiling, beside the flow's (organization policy) ----

    @Test
    void the_organizations_ceiling_trips_a_run_whose_own_flow_has_room_and_says_which_one() {
        AgentRun run = run(true);
        run.budgetUsd = 100.0;          // the flow could spend $96 more...
        run.orgBudgetUsd = 50.0;        // ...but the organization only $2 more
        run.orgSpentBeforeUsd = 48.0;
        AtomicInteger stops = new AtomicInteger();
        run.onBudgetExceeded = stops::incrementAndGet;

        spend(run, 200_000); // $3: past the organization's, nowhere near the flow's

        assertThat(run.budgetTripped).isTrue();
        assertThat(stops).hasValue(1);
        assertThat(run.error).contains("organization's $50.00 monthly ceiling")
                .contains("organization policy");
    }

    @Test
    void when_both_ceilings_are_reached_the_one_with_less_room_is_named() {
        AgentRun run = run(true);
        run.budgetUsd = 10.0;           // $6 of room
        run.spentBeforeUsd = 4.0;
        run.orgBudgetUsd = 20.0;        // $2 of room: the stricter, though the bigger number
        run.orgSpentBeforeUsd = 18.0;
        run.onBudgetExceeded = () -> { };

        spend(run, 400_000); // $6: both reached at once

        assertThat(run.error).contains("organization's $20.00 monthly ceiling");
    }

    @Test
    void a_run_with_only_an_organization_ceiling_is_stopped_by_it() {
        AgentRun run = run(true);
        run.budgetUsd = null;
        run.orgBudgetUsd = 5.0;
        run.orgSpentBeforeUsd = 0.0;
        AtomicInteger stops = new AtomicInteger();
        run.onBudgetExceeded = stops::incrementAndGet;

        spend(run, 200_000); // $3
        assertThat(run.budgetTripped).isFalse();
        spend(run, 200_000); // $6
        assertThat(run.budgetTripped).isTrue();
        assertThat(stops).hasValue(1);
    }
}
