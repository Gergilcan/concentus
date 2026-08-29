package com.concentus.service;

import com.concentus.model.FlowEvalCase;
import com.concentus.support.LocalClaudeSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Decides whether a run's final answer satisfies a case.
 *
 * <p>Three judges are plain string checks and cost nothing; they are the right tool whenever the
 * expectation can be written down literally — a reference number, a total, a line the report must
 * carry. The fourth asks a model, which is the only way to judge "the summary mentions the risk"
 * and also a second thing that can be wrong, at a model call per case. Each verdict carries its
 * reason in one line, because a score nobody can argue with is a score nobody trusts.
 *
 * <p>The model judge is a short read-only {@code claude} process, the same shape as
 * {@link FlowGenerator}'s: prompt in, text out, no tools that could change anything. It never
 * runs inside the flow's own session — a judge that shared the agent's context would be marking
 * its own homework.
 */
@Service
public class EvalJudge {

    /** Long enough for a model to read a long report; short enough that a hung CLI ends the case. */
    private static final int LLM_TIMEOUT_SECONDS = 120;
    /** What the judge is shown of a very long answer. It reads the whole of any normal one. */
    private static final int LLM_OUTPUT_CHARS = 12_000;
    /** How much of the expectation a deterministic verdict quotes back. */
    private static final int QUOTE_CHARS = 60;
    private static final int WHY_CHARS = 300;
    private static final Pattern VERDICT = Pattern.compile("\\b(PASS|FAIL)\\b", Pattern.CASE_INSENSITIVE);

    /** Test seam: everything host-facing is this one call. */
    interface CliRunner {
        CliProcess.Result run(List<String> args, int timeoutSeconds);
    }

    /** A verdict and its reason. The reason is never blank: "failed" alone is a number, not a finding. */
    public record Verdict(boolean passed, String why) {
    }

    private final LocalClaudeSupport support;
    private final CliRunner runner;

    // @Autowired because a second (test) constructor exists — without it Spring refuses the bean.
    @Autowired
    public EvalJudge(LocalClaudeSupport support) {
        this(support, CliProcess::run);
    }

    EvalJudge(LocalClaudeSupport support, CliRunner runner) {
        this.support = support;
        this.runner = runner;
    }

    /** Applies the case's judge to the run's final answer. A run with no answer fails every judge. */
    public Verdict judge(FlowEvalCase c, String output) {
        if (output == null || output.isBlank()) {
            return new Verdict(false, "The run produced no final answer to judge.");
        }
        String expected = c.expected() == null ? "" : c.expected();
        return switch (c.judge() == null ? "" : c.judge()) {
            case "regex" -> regex(expected, output);
            case "exact" -> exact(expected, output);
            case "llm" -> llm(expected, output);
            default -> contains(expected, output);
        };
    }

    /**
     * The expected text appears in the output, ignoring case. Ignoring case on purpose: the
     * judge is for "the invoice number is in there", and a capital letter is not a wrong answer.
     * A case that needs case to matter has {@link #regex}.
     */
    public static Verdict contains(String expected, String output) {
        String needle = expected.strip();
        if (needle.isEmpty()) return new Verdict(false, "The case expects nothing, so nothing can be found.");
        boolean found = output.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
        return found
                ? new Verdict(true, "Found \"" + quote(needle) + "\" in the output.")
                : new Verdict(false, "\"" + quote(needle) + "\" does not appear in the output.");
    }

    /** The pattern matches somewhere in the output. An unparseable pattern fails and says so. */
    public static Verdict regex(String pattern, String output) {
        if (pattern.isBlank()) return new Verdict(false, "The case has no pattern to match.");
        try {
            boolean found = Pattern.compile(pattern, Pattern.DOTALL).matcher(output).find();
            return found
                    ? new Verdict(true, "The output matches /" + quote(pattern) + "/.")
                    : new Verdict(false, "Nothing in the output matches /" + quote(pattern) + "/.");
        } catch (PatternSyntaxException e) {
            return new Verdict(false, "The pattern is not a valid regular expression: " + e.getDescription());
        }
    }

    /** The trimmed output IS the expected text. Surrounding whitespace is a formatting accident, not a difference. */
    public static Verdict exact(String expected, String output) {
        String want = expected.strip();
        String got = output.strip();
        return want.equals(got)
                ? new Verdict(true, "The output is exactly the expected text.")
                : new Verdict(false, "The output differs from the expected text ("
                        + got.length() + " characters against " + want.length() + " expected).");
    }

    /**
     * A model reads the expectation and the answer and says PASS or FAIL with a reason.
     *
     * <p>Two lines asked for, first word parsed: models add hedging, headings and markdown around
     * a verdict, and the first PASS or FAIL on its own is the only part that has to be found. A
     * reply with neither is a failure — an unreadable verdict is not a pass.
     */
    Verdict llm(String expected, String output) {
        String cmd = support.command().orElse(null);
        if (cmd == null) {
            return new Verdict(false, "The claude CLI was not found, so the model judge could not run.");
        }
        String shown = output.length() > LLM_OUTPUT_CHARS
                ? output.substring(0, LLM_OUTPUT_CHARS) + "\n…(truncated)"
                : output;
        String prompt = "You are judging the final answer of an automated flow.\n"
                + "Does this output satisfy: " + expected.strip() + "?\n\n"
                + "Output:\n<<<\n" + shown + "\n>>>\n\n"
                + "Answer PASS or FAIL on the first line, then one line saying why. Nothing else.";
        CliProcess.Result result = runner.run(List.of(cmd, "-p", prompt,
                "--output-format", "text",
                "--disallowedTools", "Task,Bash,Write,Edit,NotebookEdit"), LLM_TIMEOUT_SECONDS);
        if (result.exit() != 0) {
            return new Verdict(false, "The model judge could not run: " + CliProcess.lastLine(result.output()));
        }
        return parse(result.output());
    }

    static Verdict parse(String reply) {
        String text = reply == null ? "" : reply.strip();
        Matcher m = VERDICT.matcher(text);
        if (!m.find()) {
            return new Verdict(false, "The model judge gave no verdict: " + CliProcess.lastLine(text));
        }
        boolean passed = m.group(1).equalsIgnoreCase("PASS");
        // The reason is whatever follows the verdict: the rest of its line, or the next one when
        // the verdict stood alone.
        String why = text.substring(m.end()).strip().replaceFirst("^[\\s:—–\\-.,]+", "").strip();
        int newline = why.indexOf('\n');
        if (newline > 0) why = why.substring(0, newline).strip();
        if (why.isEmpty()) why = passed ? "The model judge said PASS." : "The model judge said FAIL.";
        if (why.length() > WHY_CHARS) why = why.substring(0, WHY_CHARS) + "…";
        return new Verdict(passed, why);
    }

    private static String quote(String s) {
        String one = s.replace('\n', ' ');
        return one.length() > QUOTE_CHARS ? one.substring(0, QUOTE_CHARS) + "…" : one;
    }
}
