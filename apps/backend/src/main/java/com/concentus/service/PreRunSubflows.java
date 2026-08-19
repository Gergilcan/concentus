package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.NodeExec;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Runs the flows wired INTO an agent, before the agent does anything, and hands it their answers.
 *
 * <p>This is what the direction rule means in practice. A box drawn feeding an agent feeds it: the
 * child runs first, with the text this run started from, and its closing answer arrives as context
 * — the same shape as a SQL source or a knowledge base, and for the same reason. Nobody has to
 * decide to ask.
 *
 * <p>The agent still gets {@code run_flow} for the same node, so it can ask again in its own words
 * when the first answer raises a question. The two are not redundant: one is what the flow always
 * needs, the other is what this particular run turned out to need.
 */
@Component
public class PreRunSubflows {

    private final SubflowService subflows;

    public PreRunSubflows(SubflowService subflows) {
        this.subflows = subflows;
    }

    /**
     * @param emit receives one line per child, for the run's own log — a run that waited four
     *             minutes on a sub-flow should say so rather than look stalled
     */
    public void inject(AgentSpec spec, AgentRun run, Consumer<String> emit) {
        if (spec.subflows.isEmpty()) return;

        StringBuilder context = new StringBuilder();
        for (AgentSpec.SubflowSpec sub : spec.subflows) {
            // Nodes saved as tools are called when the agent decides to, and only then. Running
            // one here would start a flow nobody asked for, on a graph drawn to mean the opposite.
            if (!sub.preRun) continue;
            NodeExec exec = run == null ? null : run.nodeExec(sub.nodeId, "flow", sub.label);
            if (exec != null) {
                exec.status = "running";
                exec.input = prompt(run);
            }

            SubflowService.Result result = subflows.run(run, sub, prompt(run));

            if (!result.started()) {
                // Said in the briefing, not only in the log: an agent that never hears about the
                // missing half writes around it as though it had the data.
                context.append("\n\n# ").append(sub.label)
                        .append(" — this flow could not run: ").append(result.error());
                emit.accept("Sub-flow '" + sub.label + "' did not run: " + result.error());
                if (exec != null) {
                    exec.status = "failed";
                    exec.output = result.error();
                    exec.endedAt = System.currentTimeMillis();
                }
                continue;
            }

            if (!sub.waitForResult || result.output() == null) {
                // Started and not waited for: there is no answer to inject, and inventing a line
                // saying "it is running" would read to the agent as data.
                emit.accept("Sub-flow '" + sub.label + "' started as run " + result.runId() + ".");
                if (exec != null) {
                    exec.status = "passed";
                    exec.output = "started as " + result.runId();
                    exec.endedAt = System.currentTimeMillis();
                }
                continue;
            }

            context.append("\n\n# Result of the flow '").append(sub.label).append("'\n")
                    .append(result.output());
            emit.accept("Sub-flow '" + sub.label + "' answered (run " + result.runId()
                    + "), injected into agent '" + spec.name + "'.");
            if (exec != null) {
                exec.status = "passed";
                exec.output = result.output();
                exec.endedAt = System.currentTimeMillis();
            }
        }

        if (!context.isEmpty()) {
            spec.systemPrompt = (spec.systemPrompt == null ? "" : spec.systemPrompt) + context;
        }
    }

    /** What the child is started with: the text this run itself began from. */
    private static String prompt(AgentRun run) {
        if (run == null) return "";
        String initial = run.initialPrompt;
        return initial == null ? "" : initial;
    }
}
