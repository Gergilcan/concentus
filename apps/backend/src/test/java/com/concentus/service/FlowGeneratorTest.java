package com.concentus.service;

import com.concentus.model.FlowGraph;
import com.concentus.support.LocalClaudeSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FlowGenerator}: the prompt it builds, what it accepts from the model, and
 * the one retry. The CLI is a stub — nothing here launches a process or reaches a model.
 */
class FlowGeneratorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FlowCompiler compiler = new FlowCompiler();

    /** A CLI that answers with the given outputs in order, recording the prompts it was given. */
    private static final class FakeCli implements FlowGenerator.CliRunner {
        private final List<String> answers;
        final List<String> prompts = new ArrayList<>();
        int exit = 0;

        FakeCli(String... answers) {
            this.answers = new ArrayList<>(List.of(answers));
        }

        @Override
        public CliProcess.Result run(List<String> args, int timeoutSeconds) {
            prompts.add(args.get(args.indexOf("-p") + 1));
            String answer = answers.isEmpty() ? "" : answers.remove(0);
            return new CliProcess.Result(exit, answer);
        }
    }

    private FlowGenerator generator(FakeCli cli) {
        LocalClaudeSupport support = mock(LocalClaudeSupport.class);
        when(support.command()).thenReturn(Optional.of("claude"));
        return new FlowGenerator(support, compiler, mapper, cli);
    }

    /** The smallest flow that compiles: one input, one coordinator, an edge between them. */
    private static String validFlowJson() {
        return """
                {"name":"Daily report","mode":"local",
                 "nodes":[{"id":"in-1","type":"input","data":{"mode":"manual"}},
                          {"id":"a-1","type":"agent","role":"coordinator",
                           "data":{"name":"Reporter","systemPrompt":"Write the report."}}],
                 "edges":[{"id":"e1","source":"in-1","target":"a-1"}]}
                """;
    }

    // ---------------------------------------------------------------- the prompt

    @Test
    void thePromptCarriesTheSchemaTheRulesAndTheRequest() {
        FlowGenerator generator = generator(new FakeCli());

        String prompt = generator.prompt("watch my inbox and summarise it", null);

        assertThat(prompt).contains("ONE JSON object");
        assertThat(prompt).contains("exactly one input node and exactly one coordinator agent");
        assertThat(prompt).contains("watch my inbox and summarise it");
        // The examples are the shipped samples, read from the classpath — so what the model is
        // shown cannot drift from what the app actually runs.
        assertThat(prompt).contains("\"id\": \"docs-from-code\"");
        assertThat(prompt).contains("\"id\": \"daily-briefing\"");
    }

    @Test
    void aRetryTellsTheModelWhatWasWrongWithItsPreviousAnswer() {
        FlowGenerator generator = generator(new FakeCli());

        String prompt = generator.prompt("anything", "No coordinator agent found.");

        assertThat(prompt).contains("Your previous answer was rejected");
        assertThat(prompt).contains("No coordinator agent found.");
    }

    @Test
    void theGenerationIsReadOnly() {
        // It answers a question about JSON; a generation that could edit files would be a bug with
        // consequences, not a wrong answer.
        assertThat(FlowGenerator.args("claude", "hi")).containsSequence("--disallowedTools", "Task,Bash,Write,Edit");
    }

    // ---------------------------------------------------------------- reading the answer

    @Test
    void acceptsJsonWrappedInAFencedBlockOrProse() {
        assertThat(FlowGenerator.json("Here you go:\n```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(FlowGenerator.json("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(FlowGenerator.json("prefix {\"a\":1} suffix")).isEqualTo("{\"a\":1}");
    }

    @Test
    void anAnswerWithNoJsonAtAllIsRejected() {
        assertThatThrownBy(() -> FlowGenerator.json("I cannot do that."))
                .hasMessageContaining("did not answer with JSON");
    }

    // ---------------------------------------------------------------- generate()

    @Test
    void returnsAnUnsavedFlowWithEveryNodePlaced() {
        FlowGenerator generator = generator(new FakeCli(validFlowJson()));

        FlowGraph flow = generator.generate("a daily report");

        // No id: it is a draft, and an id would make the first save land on someone else's flow.
        assertThat(flow.id()).isNull();
        assertThat(flow.name()).isEqualTo("Daily report");
        assertThat(flow.nodes()).hasSize(2);
        // Positions the model did not give, so two nodes cannot stack on the same spot and read
        // as one.
        assertThat(flow.nodes()).allSatisfy(n -> assertThat(n.dataOrEmpty().get("_pos")).isInstanceOf(Map.class));
    }

    @Test
    void retriesOnceWhenTheFirstAnswerDoesNotCompileAndSaysWhy() {
        // Two coordinators: parses as a FlowGraph, and the runner would refuse it. Compiling is
        // what catches that here rather than on the canvas.
        String twoCoordinators = """
                {"name":"Broken","mode":"local",
                 "nodes":[{"id":"in-1","type":"input","data":{}},
                          {"id":"a-1","type":"agent","role":"coordinator","data":{"name":"One"}},
                          {"id":"a-2","type":"agent","role":"coordinator","data":{"name":"Two"}}],
                 "edges":[{"id":"e1","source":"in-1","target":"a-1"}]}
                """;
        FakeCli cli = new FakeCli(twoCoordinators, validFlowJson());
        FlowGenerator generator = generator(cli);

        FlowGraph flow = generator.generate("a daily report");

        assertThat(flow.name()).isEqualTo("Daily report");
        assertThat(cli.prompts).hasSize(2);
        // The second prompt names the failure, so the retry is a correction rather than a re-roll.
        assertThat(cli.prompts.get(1)).contains("Your previous answer was rejected");
    }

    @Test
    void twoBadAnswersAreReportedRatherThanHandingBackAHalfBuiltCanvas() {
        FlowGenerator generator = generator(new FakeCli("not json", "still not json"));

        assertThatThrownBy(() -> generator.generate("a daily report"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be used");
    }

    @Test
    void aFailedCliIsReportedImmediatelyInsteadOfRetried() {
        // A CLI that could not run at all will not run better the second time; retrying would just
        // make the user wait twice for the same failure.
        FakeCli cli = new FakeCli("claude: command failed");
        cli.exit = 1;
        FlowGenerator generator = generator(cli);

        assertThatThrownBy(() -> generator.generate("a daily report"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not generate a flow");
        assertThat(cli.prompts).hasSize(1);
    }

    @Test
    void anEmptyDescriptionIsRefusedBeforeAnythingIsLaunched() {
        FakeCli cli = new FakeCli();

        assertThatThrownBy(() -> generator(cli).generate("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(cli.prompts).isEmpty();
    }

    @Test
    void withoutTheClaudeCliItSaysSoRatherThanFailingObscurely() {
        LocalClaudeSupport support = mock(LocalClaudeSupport.class);
        when(support.command()).thenReturn(Optional.empty());
        FlowGenerator generator = new FlowGenerator(support, compiler, mapper, new FakeCli());

        assertThatThrownBy(() -> generator.generate("a daily report"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claude CLI is not available");
    }
}
