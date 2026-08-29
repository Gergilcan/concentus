package com.concentus.service;

import com.concentus.model.FlowEvalCase;
import com.concentus.support.LocalClaudeSupport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EvalJudge}: the three string judges, and what the model judge is asked
 * and how its answer is read. The CLI is a stub — nothing here launches a process.
 */
class EvalJudgeTest {

    /** A CLI that answers with the given output, recording the arguments it was given. */
    private static final class FakeCli implements EvalJudge.CliRunner {
        final List<List<String>> calls = new ArrayList<>();
        String answer = "";
        int exit = 0;

        @Override
        public CliProcess.Result run(List<String> args, int timeoutSeconds) {
            calls.add(args);
            return new CliProcess.Result(exit, answer);
        }

        String prompt() {
            List<String> args = calls.get(0);
            return args.get(args.indexOf("-p") + 1);
        }
    }

    private static EvalJudge judge(FakeCli cli) {
        LocalClaudeSupport support = mock(LocalClaudeSupport.class);
        when(support.command()).thenReturn(Optional.of("claude"));
        return new EvalJudge(support, cli);
    }

    private static FlowEvalCase aCase(String judge, String expected) {
        return new FlowEvalCase("c1", "f1", "Case", "input", expected, judge, 0);
    }

    // ---------------------------------------------------------------- contains

    @Test
    void containsIgnoresCaseAndSaysWhatItFound() {
        EvalJudge.Verdict v = EvalJudge.contains("Invoice 2024-17", "Sent INVOICE 2024-17 to the client.");

        assertThat(v.passed()).isTrue();
        assertThat(v.why()).contains("Invoice 2024-17");
    }

    @Test
    void containsFailsWithTheTextThatWasMissing() {
        EvalJudge.Verdict v = EvalJudge.contains("refund", "Nothing to report.");

        assertThat(v.passed()).isFalse();
        assertThat(v.why()).contains("\"refund\"").contains("does not appear");
    }

    // ---------------------------------------------------------------- regex

    @Test
    void regexFindsThePatternAnywhereInTheOutput() {
        assertThat(EvalJudge.regex("total: \\d+ EUR", "Summary\ntotal: 120 EUR\n").passed()).isTrue();
        assertThat(EvalJudge.regex("total: \\d+ EUR", "total: many EUR").passed()).isFalse();
    }

    @Test
    void anUnparseablePatternFailsAndSaysSoInsteadOfThrowing() {
        EvalJudge.Verdict v = EvalJudge.regex("total: (\\d+", "total: 12");

        assertThat(v.passed()).isFalse();
        assertThat(v.why()).contains("not a valid regular expression");
    }

    // ---------------------------------------------------------------- exact

    @Test
    void exactIgnoresSurroundingWhitespaceOnly() {
        // The trailing newline every CLI answer ends with is a formatting accident, not a difference.
        assertThat(EvalJudge.exact("OK", "OK\n").passed()).isTrue();
        assertThat(EvalJudge.exact("OK", "OK.").passed()).isFalse();
    }

    // ---------------------------------------------------------------- dispatch

    @Test
    void aRunWithNoFinalAnswerFailsEveryJudge() {
        EvalJudge judge = judge(new FakeCli());

        EvalJudge.Verdict v = judge.judge(aCase("contains", "anything"), null);

        assertThat(v.passed()).isFalse();
        assertThat(v.why()).contains("no final answer");
    }

    @Test
    void theCaseSaysWhichJudgeApplies() {
        EvalJudge judge = judge(new FakeCli());

        assertThat(judge.judge(aCase("exact", "hello"), "hello world").passed()).isFalse();
        assertThat(judge.judge(aCase("contains", "hello"), "hello world").passed()).isTrue();
        assertThat(judge.judge(aCase("regex", "^hello"), "hello world").passed()).isTrue();
    }

    // ---------------------------------------------------------------- the model judge

    @Test
    void theModelJudgeIsAskedTheExpectationAndTheOutputWithNoWritingTools() {
        FakeCli cli = new FakeCli();
        cli.answer = "PASS — the summary names the currency risk.";
        EvalJudge judge = judge(cli);

        EvalJudge.Verdict v = judge.judge(aCase("llm", "the summary mentions the currency risk"),
                "Q3 summary: revenue up, currency risk on USD contracts.");

        assertThat(v.passed()).isTrue();
        assertThat(v.why()).isEqualTo("the summary names the currency risk.");
        assertThat(cli.prompt())
                .contains("Does this output satisfy: the summary mentions the currency risk?")
                .contains("currency risk on USD contracts")
                .contains("Answer PASS or FAIL");
        List<String> args = cli.calls.get(0);
        // Read-only, and text out: the judge must be able to change nothing and say one thing.
        assertThat(args.get(args.indexOf("--disallowedTools") + 1)).contains("Bash").contains("Write");
        assertThat(args.get(args.indexOf("--output-format") + 1)).isEqualTo("text");
    }

    @Test
    void aVerdictOnItsOwnLineTakesItsReasonFromTheNextOne() {
        EvalJudge.Verdict v = EvalJudge.parse("FAIL\nThe total is missing from the report.");

        assertThat(v.passed()).isFalse();
        assertThat(v.why()).isEqualTo("The total is missing from the report.");
    }

    @Test
    void aReplyWithNoVerdictIsAFailureNotAPass() {
        // An unreadable verdict must never count as a pass: that is how a score inflates.
        EvalJudge.Verdict v = EvalJudge.parse("I think this looks broadly fine.");

        assertThat(v.passed()).isFalse();
        assertThat(v.why()).contains("gave no verdict");
    }

    @Test
    void aCliThatCannotRunFailsTheCaseWithItsLastLine() {
        FakeCli cli = new FakeCli();
        cli.exit = 1;
        cli.answer = "banner\nnot logged in";
        EvalJudge judge = judge(cli);

        EvalJudge.Verdict v = judge.judge(aCase("llm", "anything"), "some output");

        assertThat(v.passed()).isFalse();
        assertThat(v.why()).contains("could not run").contains("not logged in");
    }

    @Test
    void withoutTheCliTheModelJudgeFailsAndSaysWhy() {
        LocalClaudeSupport support = mock(LocalClaudeSupport.class);
        when(support.command()).thenReturn(Optional.empty());
        EvalJudge judge = new EvalJudge(support, new FakeCli());

        EvalJudge.Verdict v = judge.judge(aCase("llm", "anything"), "some output");

        assertThat(v.passed()).isFalse();
        assertThat(v.why()).contains("claude CLI was not found");
    }
}
