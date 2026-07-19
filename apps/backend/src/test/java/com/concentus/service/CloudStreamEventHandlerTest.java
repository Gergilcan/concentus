package com.concentus.service;

import com.anthropic.models.beta.sessions.events.BetaManagedAgentsAgentMessageEvent;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsAgentThreadMessageReceivedEvent;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsAgentThreadMessageSentEvent;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsAgentToolUseEvent;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsSessionEndTurn;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsSessionRetriesExhausted;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsSessionThreadCreatedEvent;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsSessionThreadStatusIdleEvent;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsStreamSessionEvents;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsTextBlock;
import com.anthropic.core.JsonValue;
import com.concentus.config.AgentSpec;
import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Attribution of cloud (Managed Agents) events to individual flow nodes.
 *
 * <p>The behaviour under test is that a sub-agent's work lands on <em>its own</em> block: before
 * this handler existed every event was folded into the coordinator and sub-agent blocks never left
 * "pending". The correlation runs through the session thread id, which only the thread-created
 * event ties to an agent name — so the ordering of these events matters and is exercised here.
 */
class CloudStreamEventHandlerTest {

    private static final String COORD_NODE = "agent_coord";
    private static final String SUB_NODE = "agent_sub";
    private static final String THREAD = "thread_abc";

    private CloudStreamEventHandler handler;
    private AgentRun run;

    @BeforeEach
    void setUp() {
        handler = new CloudStreamEventHandler();
        run = new AgentRun("run1", "f1", "Flow", "managed");
        run.compiled = new CompiledFlow(agent(COORD_NODE, "Coordinator"), List.of(agent(SUB_NODE, "Researcher")));
    }

    private static AgentSpec agent(String nodeId, String name) {
        AgentSpec s = new AgentSpec();
        s.nodeId = nodeId;
        s.name = name;
        return s;
    }

    private NodeExec exec(String nodeId) {
        return run.nodeExecList().stream().filter(n -> nodeId.equals(n.nodeId)).findFirst().orElse(null);
    }

    private List<RunEvent> events() {
        return run.bufferedEvents();
    }

    // ---- event builders ----

    private static BetaManagedAgentsStreamSessionEvents threadCreated(String threadId, String agentName) {
        return BetaManagedAgentsStreamSessionEvents.ofSessionThreadCreated(
                BetaManagedAgentsSessionThreadCreatedEvent.builder()
                        .id("e1").agentName(agentName).sessionThreadId(threadId)
                        .type(BetaManagedAgentsSessionThreadCreatedEvent.Type.SESSION_THREAD_CREATED)
                        .processedAt(OffsetDateTime.now())
                        .build());
    }

    private static BetaManagedAgentsStreamSessionEvents sent(String threadId, String toAgent, String text) {
        var b = BetaManagedAgentsAgentThreadMessageSentEvent.builder()
                .id("e2").toSessionThreadId(threadId).addTextContent(text)
                .type(BetaManagedAgentsAgentThreadMessageSentEvent.Type.AGENT_THREAD_MESSAGE_SENT)
                .processedAt(OffsetDateTime.now());
        if (toAgent != null) b.toAgentName(toAgent);
        return BetaManagedAgentsStreamSessionEvents.ofAgentThreadMessageSent(b.build());
    }

    private static BetaManagedAgentsStreamSessionEvents received(String threadId, String fromAgent, String text) {
        var b = BetaManagedAgentsAgentThreadMessageReceivedEvent.builder()
                .id("e3").fromSessionThreadId(threadId).addTextContent(text)
                .type(BetaManagedAgentsAgentThreadMessageReceivedEvent.Type.AGENT_THREAD_MESSAGE_RECEIVED)
                .processedAt(OffsetDateTime.now());
        if (fromAgent != null) b.fromAgentName(fromAgent);
        return BetaManagedAgentsStreamSessionEvents.ofAgentThreadMessageReceived(b.build());
    }

    private static BetaManagedAgentsStreamSessionEvents threadIdle(String threadId, String agentName) {
        return threadIdle(threadId, agentName, BetaManagedAgentsSessionThreadStatusIdleEvent.StopReason.ofEndTurn(
                BetaManagedAgentsSessionEndTurn.builder()
                        .type(BetaManagedAgentsSessionEndTurn.Type.END_TURN).build()));
    }

    private static BetaManagedAgentsStreamSessionEvents threadIdle(
            String threadId, String agentName,
            BetaManagedAgentsSessionThreadStatusIdleEvent.StopReason stopReason) {
        return BetaManagedAgentsStreamSessionEvents.ofSessionThreadStatusIdle(
                BetaManagedAgentsSessionThreadStatusIdleEvent.builder()
                        .id("e4").agentName(agentName).sessionThreadId(threadId)
                        .type(BetaManagedAgentsSessionThreadStatusIdleEvent.Type.SESSION_THREAD_STATUS_IDLE)
                        .stopReason(stopReason)
                        .processedAt(OffsetDateTime.now())
                        .build());
    }

    // ---- tests ----

    @Test
    void coordinatorMessageLandsOnTheCoordinatorNode() {
        handler.handle(run, BetaManagedAgentsStreamSessionEvents.ofAgentMessage(
                BetaManagedAgentsAgentMessageEvent.builder()
                        .id("e0").type(BetaManagedAgentsAgentMessageEvent.Type.AGENT_MESSAGE)
                        .addContent(BetaManagedAgentsTextBlock.builder().text("planning the work").type(BetaManagedAgentsTextBlock.Type.TEXT).build())
                        .processedAt(OffsetDateTime.now())
                        .build()));

        assertThat(exec(COORD_NODE).output).contains("planning the work");
        assertThat(exec(SUB_NODE)).isNull();
        assertThat(events()).last().satisfies(e -> {
            assertThat(e.type()).isEqualTo("agent_message");
            // the real agent name, not the literal "coordinator"
            assertThat(e.agent()).isEqualTo("Coordinator");
        });
    }

    @Test
    void threadCreatedMapsTheThreadToItsNodeAndMarksItRunning() {
        handler.handle(run, threadCreated(THREAD, "Researcher"));

        assertThat(run.threadToNode).containsEntry(THREAD, SUB_NODE);
        assertThat(exec(SUB_NODE).status).isEqualTo("running");
    }

    @Test
    void delegatedInstructionBecomesTheSubAgentsInput() {
        handler.handle(run, threadCreated(THREAD, "Researcher"));
        handler.handle(run, sent(THREAD, "Researcher", "find the changelog"));

        assertThat(exec(SUB_NODE).input).contains("find the changelog");
        // and must not leak onto the coordinator's block
        assertThat(exec(COORD_NODE)).isNull();
    }

    @Test
    void subAgentReplyBecomesItsOutputNotTheCoordinators() {
        handler.handle(run, threadCreated(THREAD, "Researcher"));
        handler.handle(run, received(THREAD, "Researcher", "found 3 entries"));

        assertThat(exec(SUB_NODE).output).contains("found 3 entries");
        assertThat(exec(COORD_NODE)).isNull();
        assertThat(events()).last().satisfies(e -> {
            assertThat(e.type()).isEqualTo("agent_message");
            assertThat(e.agent()).isEqualTo("Researcher");
        });
    }

    @Test
    void threadIsResolvedByNameWhenThreadCreatedWasNeverSeen() {
        // A reconnect can drop the thread_created event; the agent name on the message
        // is the only remaining way to attribute it.
        handler.handle(run, received(THREAD, "Researcher", "late result"));

        assertThat(exec(SUB_NODE).output).contains("late result");
        // and the mapping is remembered so subsequent nameless events still resolve
        assertThat(run.threadToNode).containsEntry(THREAD, SUB_NODE);
    }

    @Test
    void unknownThreadWithNoNameIsNotAttributedToAnyNode() {
        handler.handle(run, received("thread_unknown", null, "orphan output"));

        // Better to attribute nothing than to blame the coordinator for a sub-agent's work.
        assertThat(exec(COORD_NODE)).isNull();
        assertThat(exec(SUB_NODE)).isNull();
    }

    @Test
    void toolUseIsAttributedToTheThreadThatRanIt() {
        handler.handle(run, threadCreated(THREAD, "Researcher"));
        handler.handle(run, BetaManagedAgentsStreamSessionEvents.ofAgentToolUse(
                BetaManagedAgentsAgentToolUseEvent.builder()
                        .id("t1").name("WebSearch").sessionThreadId(THREAD).type(BetaManagedAgentsAgentToolUseEvent.Type.AGENT_TOOL_USE)
                        .input(BetaManagedAgentsAgentToolUseEvent.Input.builder()
                                .putAdditionalProperty("q", JsonValue.from("x")).build())
                        .processedAt(OffsetDateTime.now())
                        .build()));

        assertThat(events()).last().satisfies(e -> {
            assertThat(e.type()).isEqualTo("tool_use");
            assertThat(e.agent()).isEqualTo("Researcher");
        });
    }

    @Test
    void toolUseWithoutAThreadFallsBackToTheCoordinator() {
        handler.handle(run, BetaManagedAgentsStreamSessionEvents.ofAgentToolUse(
                BetaManagedAgentsAgentToolUseEvent.builder()
                        .id("t2").name("Read").type(BetaManagedAgentsAgentToolUseEvent.Type.AGENT_TOOL_USE)
                        .input(BetaManagedAgentsAgentToolUseEvent.Input.builder().build())
                        .processedAt(OffsetDateTime.now())
                        .build()));

        assertThat(events()).last().satisfies(e -> assertThat(e.agent()).isEqualTo("Coordinator"));
    }

    @Test
    void goingIdleClosesTheSubAgentBlockAsPassed() {
        handler.handle(run, threadCreated(THREAD, "Researcher"));
        handler.handle(run, threadIdle(THREAD, "Researcher"));

        NodeExec ne = exec(SUB_NODE);
        assertThat(ne.status).isEqualTo("passed");
        assertThat(ne.endedAt).isGreaterThan(0L);
    }

    @Test
    void exhaustingRetriesMarksTheBlockFailedNotPassed() {
        handler.handle(run, threadCreated(THREAD, "Researcher"));
        handler.handle(run, threadIdle(THREAD, "Researcher",
                BetaManagedAgentsSessionThreadStatusIdleEvent.StopReason.ofRetriesExhausted(
                        BetaManagedAgentsSessionRetriesExhausted.builder()
                                .type(BetaManagedAgentsSessionRetriesExhausted.Type.RETRIES_EXHAUSTED).build())));

        NodeExec ne = exec(SUB_NODE);
        // A thread that gave up is not a completed piece of work.
        assertThat(ne.status).isEqualTo("failed");
        assertThat(ne.error).contains("retries");
    }

    @Test
    void aFailedBlockIsNotOverwrittenByLaterLifecycleEvents() {
        handler.handle(run, threadCreated(THREAD, "Researcher"));
        exec(SUB_NODE).status = "failed";

        handler.handle(run, sent(THREAD, "Researcher", "retry please"));
        handler.handle(run, threadIdle(THREAD, "Researcher"));

        assertThat(exec(SUB_NODE).status).isEqualTo("failed");
    }

    @Test
    void twoSubAgentsKeepTheirOwnInputAndOutput() {
        run.compiled = new CompiledFlow(
                agent(COORD_NODE, "Coordinator"),
                List.of(agent("agent_a", "Alpha"), agent("agent_b", "Beta")));

        handler.handle(run, threadCreated("t_a", "Alpha"));
        handler.handle(run, threadCreated("t_b", "Beta"));
        handler.handle(run, sent("t_a", "Alpha", "task A"));
        handler.handle(run, sent("t_b", "Beta", "task B"));
        handler.handle(run, received("t_a", "Alpha", "result A"));
        handler.handle(run, received("t_b", "Beta", "result B"));

        assertThat(exec("agent_a").input).contains("task A").doesNotContain("task B");
        assertThat(exec("agent_a").output).contains("result A").doesNotContain("result B");
        assertThat(exec("agent_b").input).contains("task B").doesNotContain("task A");
        assertThat(exec("agent_b").output).contains("result B").doesNotContain("result A");
    }
}
