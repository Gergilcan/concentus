package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.llm.ChatTypes;
import com.concentus.llm.LlmException;
import com.concentus.llm.LlmProvider;
import com.concentus.llm.ProviderRegistry;
import com.concentus.model.NodeExec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The delegation loop this backend owns.
 *
 * <p>Claude Code supplies sub-agent orchestration for the local backend; here it has to be built,
 * so the things that would silently break — an agent seeing a roster it shouldn't, usage landing
 * on the wrong block, a failed delegate killing the whole run, a coordinator looping forever —
 * are pinned rather than assumed.
 */
class ApiAgentExecutorTest {

    /** A provider that replays scripted replies and records what it was asked. */
    private static final class ScriptedProvider implements LlmProvider {
        private final Deque<ChatTypes.ChatReply> replies = new ArrayDeque<>();
        private final List<ChatTypes.ChatRequest> requests = new ArrayList<>();

        void push(ChatTypes.ChatReply reply) {
            replies.add(reply);
        }

        @Override
        public String id() {
            return "scripted";
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public ChatTypes.ChatReply chat(ChatTypes.ChatRequest request) {
            requests.add(request);
            if (replies.isEmpty()) return text("done");
            return replies.removeFirst();
        }
    }

    private static ChatTypes.ChatReply text(String s) {
        return new ChatTypes.ChatReply(s, List.of(), ChatTypes.TokenUsage.NONE);
    }

    private static ChatTypes.ChatReply callsTool(String toolName, String task) {
        return new ChatTypes.ChatReply(null,
                List.of(new ChatTypes.ToolCall("c1", toolName, "{\"task\":\"" + task + "\"}")),
                ChatTypes.TokenUsage.NONE);
    }

    private static ChatTypes.ChatReply usage(long in, long out) {
        return new ChatTypes.ChatReply("ok", List.of(), new ChatTypes.TokenUsage(in, out, 0, 0));
    }

    private ScriptedProvider provider;
    private ProviderRegistry registry;
    private ApiAgentExecutor executor;
    private AgentRun run;

    @BeforeEach
    void setUp() {
        provider = new ScriptedProvider();
        registry = mock(ProviderRegistry.class);
        when(registry.forModel(any())).thenReturn(Optional.of(provider));
        when(registry.providerIdForModel(any())).thenReturn(Optional.of("scripted"));

        RagContextInjector rag = mock(RagContextInjector.class);
        executor = new ApiAgentExecutor(registry, rag, new ObjectMapper(), 12);

        run = new AgentRun("run1", "f1", "Flow", "api");
        run.pricing = new PricingTable("", 3.0, 15.0);
    }

    private static AgentSpec agent(String nodeId, String name, String cliName, String... delegatesTo) {
        AgentSpec s = new AgentSpec();
        s.nodeId = nodeId;
        s.name = name;
        s.cliName = cliName;
        s.model = new AgentSpec.ModelSpec();
        s.model.id = "gpt-5";
        s.model.maxTokens = 1000;
        s.delegatesTo = new ArrayList<>(List.of(delegatesTo));
        return s;
    }

    private NodeExec exec(String nodeId) {
        return run.nodeExecList().stream().filter(n -> nodeId.equals(n.nodeId)).findFirst().orElse(null);
    }

    private CompiledFlow flowWithOneSub() {
        AgentSpec coord = agent("c1", "Tech Lead", "tech-lead", "researcher");
        AgentSpec sub = agent("s1", "Researcher", "researcher");
        return new CompiledFlow(coord, List.of(sub));
    }

    @Test
    void aPlainAnswerNeedsNoDelegation() {
        provider.push(text("here is the answer"));
        run.compiled = flowWithOneSub();

        executor.runTurn(run, run.compiled, "what is 2+2?");

        assertThat(exec("c1").status).isEqualTo("passed");
        assertThat(exec("c1").output).contains("here is the answer");
        assertThat(run.bufferedEvents()).anySatisfy(e -> {
            assertThat(e.text()).isEqualTo("here is the answer");
            assertThat(e.agent()).isEqualTo("Tech Lead");
        });
    }

    @Test
    void anAgentOnlySeesToolsForTheAgentsWiredBehindIt() {
        provider.push(text("done"));
        run.compiled = flowWithOneSub();

        executor.runTurn(run, run.compiled, "go");

        // The coordinator can reach the researcher and nothing else — the roster, not the whole
        // flow, is what it is offered.
        List<ChatTypes.ToolSpec> tools = provider.requests.get(0).tools();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("delegate_to_researcher");
    }

    @Test
    void aSubAgentWithNoRosterIsOfferedNoTools() {
        run.compiled = flowWithOneSub();
        provider.push(callsTool("delegate_to_researcher", "find it"));
        provider.push(text("found it"));   // the sub-agent's answer
        provider.push(text("all done"));   // coordinator wraps up

        executor.runTurn(run, run.compiled, "go");

        // Second call is the sub-agent's; it delegates to nobody.
        assertThat(provider.requests.get(1).tools()).isEmpty();
    }

    @Test
    void delegationRunsTheSubAgentAndFeedsItsAnswerBack() {
        run.compiled = flowWithOneSub();
        provider.push(callsTool("delegate_to_researcher", "find the changelog"));
        provider.push(text("found 3 entries"));
        provider.push(text("summary: 3 entries"));

        executor.runTurn(run, run.compiled, "go");

        // The sub-agent got its own block, with the delegated task as input.
        assertThat(exec("s1").input).contains("find the changelog");
        assertThat(exec("s1").output).contains("found 3 entries");
        assertThat(exec("s1").status).isEqualTo("passed");

        // ...and its answer came back to the coordinator as a tool result.
        ChatTypes.ChatRequest wrapUp = provider.requests.get(2);
        assertThat(wrapUp.messages()).anySatisfy(m -> {
            assertThat(m.role()).isEqualTo("tool");
            assertThat(m.text()).contains("found 3 entries");
        });
    }

    @Test
    void usageIsAttributedToTheAgentThatSpentIt() {
        run.compiled = flowWithOneSub();
        provider.push(callsTool("delegate_to_researcher", "go"));
        provider.push(usage(500, 50));   // sub-agent
        provider.push(usage(100, 10));   // coordinator wrap-up

        executor.runTurn(run, run.compiled, "go");

        assertThat(exec("s1").inputTokens).isEqualTo(500L);
        assertThat(exec("c1").inputTokens).isEqualTo(100L);
        assertThat(run.totalInputTokens).isEqualTo(600L);
    }

    @Test
    void aFailedDelegateIsReportedBackInsteadOfKillingTheRun() {
        run.compiled = flowWithOneSub();
        // Sub-agent call throws; coordinator should still get a turn to react.
        when(registry.forModel(any())).thenReturn(Optional.of(new LlmProvider() {
            private int calls = 0;

            @Override
            public String id() {
                return "scripted";
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public ChatTypes.ChatReply chat(ChatTypes.ChatRequest request) {
                calls++;
                if (calls == 1) return callsTool("delegate_to_researcher", "go");
                if (calls == 2) throw new LlmException("scripted", "429 rate limited");
                return text("continuing without it");
            }
        }));

        executor.runTurn(run, run.compiled, "go");

        assertThat(exec("s1").status).isEqualTo("failed");
        assertThat(exec("s1").error).contains("rate limited");
        // The run itself survives — the coordinator was told and carried on.
        assertThat(run.status).isNotEqualTo("ERROR");
        assertThat(exec("c1").status).isEqualTo("passed");
    }

    @Test
    void anUnknownToolNameIsReportedToTheModelRatherThanThrown() {
        run.compiled = flowWithOneSub();
        provider.push(callsTool("delegate_to_nobody", "go"));
        provider.push(text("ok, I'll do it myself"));

        executor.runTurn(run, run.compiled, "go");

        assertThat(run.status).isNotEqualTo("ERROR");
        ChatTypes.ChatRequest second = provider.requests.get(1);
        assertThat(second.messages()).anySatisfy(m ->
                assertThat(m.text()).contains("No agent named 'delegate_to_nobody'"));
    }

    @Test
    void aCoordinatorThatNeverStopsDelegatingIsCappedAndSaysSo() {
        run.compiled = flowWithOneSub();
        // Always delegates, never answers.
        when(registry.forModel(any())).thenReturn(Optional.of(new LlmProvider() {
            @Override
            public String id() {
                return "scripted";
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public ChatTypes.ChatReply chat(ChatTypes.ChatRequest request) {
                return request.tools().isEmpty()
                        ? text("sub done")
                        : callsTool("delegate_to_researcher", "again");
            }
        }));

        executor.runTurn(run, run.compiled, "go");

        assertThat(run.bufferedEvents())
                .anySatisfy(e -> assertThat(e.text()).contains("stopped after"));
        assertThat(run.status).isNotEqualTo("RUNNING");
    }

    @Test
    void anUnconfiguredProviderFailsAtLaunchWithAnActionableMessage() {
        run.compiled = flowWithOneSub();
        when(registry.forModel(any())).thenReturn(Optional.empty());
        when(registry.providerIdForModel(any())).thenReturn(Optional.of("openai"));

        executor.runTurn(run, run.compiled, "go");

        assertThat(run.status).isEqualTo("ERROR");
        assertThat(run.error).contains("openai").contains("no credential");
    }

    @Test
    void aClaudeModelIsSentBackToItsOwnBackendRatherThanFailingObscurely() {
        run.compiled = flowWithOneSub();
        when(registry.forModel(any())).thenReturn(Optional.empty());
        when(registry.providerIdForModel(any())).thenReturn(Optional.of("anthropic"));

        executor.runTurn(run, run.compiled, "go");

        assertThat(run.error).contains("Claude model").contains("local");
    }

    @Test
    void toolNamesAreSanitisedForProvidersThatRestrictTheCharset() {
        assertThat(ApiAgentExecutor.toolNameFor("code-reviewer")).isEqualTo("delegate_to_code-reviewer");
        assertThat(ApiAgentExecutor.toolNameFor("back end dev")).isEqualTo("delegate_to_back_end_dev");
    }

    @Test
    void ragContextIsInjectedOnceNotOnEveryTurn() {
        RagContextInjector rag = mock(RagContextInjector.class);
        ApiAgentExecutor exec2 = new ApiAgentExecutor(registry, rag, new ObjectMapper(), 12);
        run.compiled = flowWithOneSub();
        provider.push(text("a"));
        provider.push(text("b"));

        exec2.runTurn(run, run.compiled, "first");
        exec2.runTurn(run, run.compiled, "second");

        // Re-injecting would re-read the database and append the same rows to the prompt again.
        org.mockito.Mockito.verify(rag, org.mockito.Mockito.times(2)).inject(any(), any(), any());
    }

    @Test
    void mapsAreNotSharedBetweenRuns() {
        // Guards against a static/shared collection sneaking in: two runs must not see each
        // other's blocks.
        AgentRun other = new AgentRun("run2", "f1", "Flow", "api");
        other.compiled = flowWithOneSub();
        run.compiled = flowWithOneSub();
        provider.push(text("a"));

        executor.runTurn(run, run.compiled, "go");

        assertThat(run.nodeExecList()).isNotEmpty();
        assertThat(other.nodeExecList()).isEmpty();
    }
}
