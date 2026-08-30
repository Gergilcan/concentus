package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a branch is handed when the block before it failed, or when the verifier rejected.
 *
 * <p>The text is the whole interface between the run and whatever is wired to its second output.
 * A condition drawn on it matches on these exact words, and an agent asked "why was this
 * rejected" has only this to read — so the shape is pinned here line by line.
 */
class BranchPayloadsTest {

    private static AgentSpec spec(String nodeId, String name) {
        AgentSpec s = new AgentSpec();
        s.nodeId = nodeId;
        s.name = name;
        return s;
    }

    private static AgentRun run() {
        AgentRun run = new AgentRun("run-1", "flow_1", "Ads campaign");
        run.compiled = new CompiledFlow(spec("coord", "Planner"),
                List.of(spec("w1", "Ads writer"), spec("w2", "Ads reviewer")),
                null, spec("v", "Judge"), List.of());
        return run;
    }

    @Test
    void the_log_of_a_block_is_its_own_lines_only_with_the_time_they_were_written() {
        AgentRun run = run();
        run.restoreEvents(List.of(
                new RunEvent("agent_message", "drafting", "Ads writer", "w1", 0L),
                new RunEvent("tool_use", "Read(file)", "Ads reviewer", "w2", 1000L),
                new RunEvent("system", "Verification starts", "Judge", "v", 2000L),
                new RunEvent("agent_message", "done", "Ads writer", "w1", 3_600_000L + 65_000L)));

        String log = run.logOf("w1");

        assertThat(log.lines()).hasSize(2);
        assertThat(log).contains("[agent_message]  drafting").contains("[agent_message]  done");
        assertThat(log).doesNotContain("Read(file)").doesNotContain("Verification starts");
        // HH:mm:ss, in the run's own zone: the number a person compares against the console.
        assertThat(log.lines().toList().get(1)).matches("\\d\\d:\\d\\d:\\d\\d  \\[agent_message]  done");
    }

    @Test
    void the_log_of_a_block_that_said_nothing_is_empty() {
        assertThat(run().logOf("w1")).isEmpty();
    }

    @Test
    void the_error_payload_keeps_the_legacy_first_line_and_appends_the_blocks_log() {
        AgentRun run = run();
        NodeExec exec = run.nodeExec("w1", "agent", "Ads writer");
        exec.status = "failed";
        exec.error = "the mailbox refused the credential";
        run.restoreEvents(List.of(new RunEvent("error", "credential refused", "Ads writer", "w1", 0L)));

        String payload = BranchPayloads.errorOf(run, "w1");

        // The first line is byte-for-byte what the branch received before the log was added, so
        // a condition drawn on it keeps matching.
        assertThat(payload.lines().findFirst().orElseThrow())
                .isEqualTo("Ads writer failed: the mailbox refused the credential");
        assertThat(payload).contains("## Log — Ads writer").contains("[error]  credential refused");
    }

    @Test
    void an_unattributed_failure_is_worded_as_the_run_did_and_carries_the_coordinators_log() {
        AgentRun run = run();
        run.error = "Every worker failed. The combined report lists each reason.";
        run.restoreEvents(List.of(new RunEvent("system", "planning", "Planner", "coord", 0L)));

        String payload = BranchPayloads.errorOf(run, "coord");

        assertThat(payload.lines().findFirst().orElseThrow())
                .isEqualTo("Every worker failed. The combined report lists each reason.");
        assertThat(payload).contains("## Log — Planner").contains("planning");
    }

    @Test
    void the_verification_report_lists_rejected_workers_first_with_reason_output_and_log() {
        AgentRun run = run();
        run.lastVerdictSummary = "One claim cited no file.";
        NodeExec w1 = run.nodeExec("w1", "agent", "Ads writer");
        w1.status = "passed";
        w1.verdict = "accepted";
        w1.output = "Three headlines.";
        NodeExec w2 = run.nodeExec("w2", "agent", "Ads reviewer");
        w2.status = "passed";
        w2.verdict = "rejected";
        w2.verdictReason = "Cites a CTR that appears in no file.";
        w2.output = "CTR is 12%.";
        run.nodeExec("v", "agent", "Judge").status = "passed";
        run.restoreEvents(List.of(
                new RunEvent("agent_message", "writing headlines", "Ads writer", "w1", 0L),
                new RunEvent("agent_message", "checking numbers", "Ads reviewer", "w2", 0L),
                new RunEvent("system", "rejecting w2", "Judge", "v", 0L)));

        String report = BranchPayloads.verificationReport(run);

        assertThat(report).startsWith("# Verification report — Ads campaign\n");
        assertThat(report).contains("One claim cited no file.");
        assertThat(report).contains("Rejected 1 of 2 worker(s).");
        assertThat(report.indexOf("## ✖ Ads reviewer — REJECTED"))
                .isLessThan(report.indexOf("## ✔ Ads writer — accepted"));
        assertThat(report).contains("Reason: Cites a CTR that appears in no file.");
        assertThat(report).contains("CTR is 12%.").contains("checking numbers");
        assertThat(report).contains("Three headlines.").contains("writing headlines");
        assertThat(report).contains("## Verifier log — Judge").contains("rejecting w2");
    }

    @Test
    void a_worker_that_crashed_before_any_verdict_is_still_in_the_report() {
        AgentRun run = run();
        NodeExec w1 = run.nodeExec("w1", "agent", "Ads writer");
        w1.status = "failed";
        w1.error = "timed out";
        NodeExec w2 = run.nodeExec("w2", "agent", "Ads reviewer");
        w2.status = "passed";
        w2.verdict = "rejected";
        w2.verdictReason = "off-task";

        String report = BranchPayloads.verificationReport(run);

        assertThat(report).contains("## ✖ Ads writer — failed").contains("timed out");
        assertThat(report).contains("Rejected 1 of 2 worker(s).");
        assertThat(report).contains("(no output)");
    }
}
