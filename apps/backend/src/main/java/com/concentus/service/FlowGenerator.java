package com.concentus.service;

import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.support.LocalClaudeSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a sentence into a flow on the canvas.
 *
 * <p>The distance between "I know what I want automated" and a working graph is the drop-off this
 * closes: the canvas is easy to use once you know what a coordinator, a sub-agent and an input
 * node are FOR, and impossible to start from when you do not. So the first draft is written for
 * you — and it is a draft: nothing is saved. It opens in Studio exactly as if you had drawn it,
 * and the save button is still yours to press.
 *
 * <p>Honest about what it is: one non-interactive {@code claude -p} call with the flow schema and
 * two of the bundled samples as examples, whose answer must parse as a FlowGraph AND compile
 * before it is handed back. An answer that fails either is retried once with the error appended;
 * a second failure is reported, never a half-built canvas.
 */
@Service
public class FlowGenerator {

    private static final Logger log = LoggerFactory.getLogger(FlowGenerator.class);

    /** A generation is a full agent turn; the UI shows a spinner and can afford to wait. */
    private static final int TIMEOUT_SECONDS = 180;
    /** The samples shown as examples — real shipped flows, so the shapes cannot drift from reality. */
    private static final List<String> EXAMPLE_RESOURCES =
            List.of("library-flows/docs-from-code.json", "library-flows/daily-briefing.json");

    /** Test seam: everything host-facing is this one call. */
    interface CliRunner {
        CliProcess.Result run(List<String> args, int timeoutSeconds);
    }

    private final LocalClaudeSupport support;
    private final FlowCompiler compiler;
    private final ObjectMapper mapper;
    private final CliRunner runner;

    // @Autowired because a second (test) constructor exists — without it Spring refuses the bean.
    @Autowired
    public FlowGenerator(LocalClaudeSupport support, FlowCompiler compiler, ObjectMapper mapper) {
        this(support, compiler, mapper, CliProcess::run);
    }

    FlowGenerator(LocalClaudeSupport support, FlowCompiler compiler, ObjectMapper mapper, CliRunner runner) {
        this.support = support;
        this.compiler = compiler;
        this.mapper = mapper;
        this.runner = runner;
    }

    /**
     * Generates an unsaved flow from a description.
     *
     * @throws IllegalArgumentException when the description is empty
     * @throws IllegalStateException when the CLI is unavailable, or its answer could not be turned
     *                               into a flow that compiles — twice
     */
    public FlowGraph generate(String description) {
        String wanted = description == null ? "" : description.trim();
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("Describe what you want automated first.");
        }
        String cmd = support.command().orElseThrow(() -> new IllegalStateException(
                "The claude CLI is not available, and generating a flow needs it."));

        String problem = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            CliProcess.Result result = runner.run(args(cmd, prompt(wanted, problem)), TIMEOUT_SECONDS);
            if (result.exit() != 0) {
                // A CLI that could not run at all will not run better the second time.
                throw new IllegalStateException("The claude CLI could not generate a flow: "
                        + CliProcess.lastLine(result.output()));
            }
            try {
                return accept(result.output());
            } catch (Exception e) {
                problem = e.getMessage();
                log.debug("Generated flow rejected on attempt {}: {}", attempt, problem);
            }
        }
        throw new IllegalStateException(
                "The generated flow could not be used: " + problem + " Try describing it differently.");
    }

    /**
     * Parses, compiles and tidies one answer. Compiling is the real gate: JSON that parses into a
     * FlowGraph can still be a graph the runner would refuse (no coordinator, two of them, an edge
     * to nowhere), and finding that out on the canvas — or worse, on the first run — is the
     * failure this whole endpoint exists to avoid.
     */
    private FlowGraph accept(String output) throws Exception {
        FlowGraph parsed = mapper.readValue(json(output), FlowGraph.class);
        FlowGraph flow = laidOut(parsed);
        compiler.compile(flow);
        return flow;
    }

    /**
     * The JSON out of an answer that may be wrapped in prose or a fenced block.
     *
     * <p>Deliberately lenient about the wrapper and strict about the content: models are told to
     * answer with JSON only and mostly do, but a stray "Here's the flow:" must not cost the user
     * a whole generation.
     */
    static String json(String output) {
        String text = output == null ? "" : output.trim();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int start = text.indexOf('\n', fence);
            int end = text.indexOf("```", fence + 3);
            if (start > 0 && end > start) text = text.substring(start + 1, end).trim();
        }
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open < 0 || close <= open) {
            throw new IllegalStateException("The model did not answer with JSON.");
        }
        return text.substring(open, close + 1);
    }

    /**
     * The flow as it should reach the canvas: never carrying an id (it is a draft — an id would
     * make the first save overwrite whatever flow already had it), and with every node placed.
     * Nodes without a position all land on the same spot in the editor, which reads as one node
     * and loses the rest behind it.
     */
    private static FlowGraph laidOut(FlowGraph flow) {
        List<FlowNode> nodes = new ArrayList<>();
        List<FlowNode> source = flow.nodes() == null ? List.of() : flow.nodes();
        for (int i = 0; i < source.size(); i++) {
            FlowNode node = source.get(i);
            Map<String, Object> data = new LinkedHashMap<>(node.dataOrEmpty());
            if (!(data.get("_pos") instanceof Map)) {
                data.put("_pos", Map.of("x", 40 + (i % 4) * 300, "y", 120 + (i / 4) * 180));
            }
            nodes.add(new FlowNode(node.id(), node.type(), node.role(), data));
        }
        List<FlowEdge> edges = flow.edges() == null ? List.of() : flow.edges();
        String name = flow.name() == null || flow.name().isBlank() ? "Generated flow" : flow.name();
        String mode = flow.mode() == null || flow.mode().isBlank() ? "local" : flow.mode();
        return new FlowGraph(null, name, mode, nodes, edges, flow.enabled(), flow.tags(),
                flow.favorite(), flow.notifyWebhook(), flow.budgetUsd(),
                flow.approvalSlackCredentialId(), flow.approvalSlackChannel(),
                flow.approvalTeamsWebhook(), flow.variables(), flow.folder(), flow.goldenAutoRun());
    }

    /**
     * A one-shot, read-only generation.
     *
     * <p>{@code --disallowedTools} rather than trust: this answers a question about JSON and has
     * no business editing anything, and the same denylist already guards the fan-out planner. The
     * model is left unset so the user's own default applies.
     */
    static List<String> args(String cmd, String prompt) {
        return List.of(cmd, "-p", prompt,
                "--output-format", "text",
                "--disallowedTools", "Task,Bash,Write,Edit");
    }

    /** The instruction, with the schema, two real examples, and (on a retry) what went wrong. */
    String prompt(String description, String previousProblem) {
        StringBuilder p = new StringBuilder();
        p.append("""
                You design automation flows for Concentus. Answer with ONE JSON object and nothing
                else: no prose, no markdown fence, no explanation.

                The JSON is a flow graph:
                  name    a short human name for the flow
                  mode    "local" (the claude CLI) unless the request clearly needs the cloud API
                  nodes   a list of {id, type, role, data}
                  edges   a list of {id, source, target} joining node ids

                Node types and what their data holds:
                  input     the trigger. data.mode is "manual" (a person types the first message),
                            "prompt" (data.prompt is sent automatically), "cron" (data.cron is a
                            5-field expression), "webhook" or "mail". Exactly one per flow.
                  agent     role "coordinator" (exactly one, the agent that answers) or "subagent"
                            (a specialist the coordinator delegates to). data: name, model,
                            systemPrompt (write a real one — this is the agent's whole briefing),
                            description (for a subagent: WHEN to delegate to it).
                  mcp       an external tool server. data: name, url.
                  repo      a git repository the agents work in. data: url.
                  merge     combines several sub-agents' outputs into one answer.
                  verifier  judges sub-agent output before it is merged.

                Rules that make a flow valid:
                  - exactly one input node and exactly one coordinator agent
                  - every edge's source and target must be ids that exist
                  - the input node connects to the coordinator
                  - sub-agents connect FROM the coordinator (coordinator -> subagent)

                Write real system prompts, in the language the request is written in. A flow whose
                agents have empty prompts is not an answer.

                Here are two real flows, as examples of the exact shape expected:
                """);
        for (String example : examples()) p.append('\n').append(example).append('\n');
        p.append("\nNow design a flow for this request:\n").append(description).append('\n');
        if (previousProblem != null) {
            p.append("""

                    Your previous answer was rejected. Fix exactly this and answer again with the
                    whole JSON object:
                    """).append(previousProblem).append('\n');
        }
        return p.toString();
    }

    /** The bundled samples, read from the classpath so the examples are the shipped flows. */
    private List<String> examples() {
        List<String> out = new ArrayList<>();
        for (String path : EXAMPLE_RESOURCES) {
            try {
                out.add(new String(new ClassPathResource(path).getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8).trim());
            } catch (Exception e) {
                // An example that cannot be read costs quality, not correctness — the schema above
                // still describes the shape, and the answer is validated either way.
                log.debug("Example flow {} unavailable: {}", path, e.getMessage());
            }
        }
        return out;
    }
}
