package com.concentus.service;

import com.concentus.config.AgentSpec;
import com.concentus.model.NodeExec;
import com.concentus.model.RunEvent;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsAgentThreadMessageReceivedEvent;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsAgentThreadMessageSentEvent;
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsStreamSessionEvents;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps Managed Agents (cloud) session events onto flow nodes, so each sub-agent's input, output
 * and status land on its own block rather than all being folded into the coordinator's.
 *
 * <p>The correlation key is the <b>session thread</b>. Only {@code session.thread_created} names
 * the agent that owns a thread, so that event records {@code sessionThreadId -> nodeId} in
 * {@link AgentRun#threadToNode}; every later event that carries a thread id is resolved through
 * that map. Thread messages give us the two halves of a sub-agent's work:
 *
 * <ul>
 *   <li>{@code agent.thread_message_sent} — the coordinator delegating <em>to</em> a sub-agent,
 *       which is that sub-agent's <b>input</b>;</li>
 *   <li>{@code agent.thread_message_received} — what the sub-agent handed back, its <b>output</b>.</li>
 * </ul>
 *
 * <p>A plain {@code agent.message} carries no thread id: it is the main thread talking, i.e. the
 * coordinator. This mirrors {@link LocalStreamEventHandler}, which does the same job for the CLI
 * backend using {@code parent_tool_use_id} as its correlation key.
 */
@Component
public class CloudStreamEventHandler {

    public void handle(AgentRun run, BetaManagedAgentsStreamSessionEvents ev) {
        if (ev.isAgentMessage()) {
            // No thread id on this event: the main thread is the coordinator.
            StringBuilder sb = new StringBuilder();
            ev.asAgentMessage().content().forEach(b -> sb.append(b.text()));
            String text = sb.toString();
            NodeExec coord = coordExec(run);
            if (coord != null) coord.appendOutput(text);
            run.emit(RunEvent.of("agent_message", text, coordName(run), coordNodeId(run)));

        } else if (ev.isSessionThreadCreated()) {
            var e = ev.asSessionThreadCreated();
            String nodeId = nodeIdByAgentName(run, e.agentName());
            if (nodeId != null) {
                run.threadToNode.put(e.sessionThreadId(), nodeId);
                NodeExec ne = run.nodeExec(nodeId, "agent", e.agentName());
                if (ne != null && !"failed".equals(ne.status)) ne.status = "running";
            }
            run.emit(RunEvent.of("system", "Sub-agent thread started.", e.agentName(), nodeId));

        } else if (ev.isAgentThreadMessageSent()) {
            var e = ev.asAgentThreadMessageSent();
            String name = e.toAgentName().orElse(null);
            String nodeId = resolveNode(run, e.toSessionThreadId(), name);
            String text = sentText(e.content());
            NodeExec ne = nodeExecFor(run, nodeId, name);
            if (ne != null) {
                if (!"failed".equals(ne.status)) ne.status = "running";
                ne.appendInput(text);
            }
            // Attributed to the receiving agent: this is the instruction it is about to work on.
            run.emit(RunEvent.of("system", "→ " + text, labelFor(run, nodeId, name), nodeId));

        } else if (ev.isAgentThreadMessageReceived()) {
            var e = ev.asAgentThreadMessageReceived();
            String name = e.fromAgentName().orElse(null);
            String nodeId = resolveNode(run, e.fromSessionThreadId(), name);
            String text = receivedText(e.content());
            NodeExec ne = nodeExecFor(run, nodeId, name);
            if (ne != null) ne.appendOutput(text);
            run.emit(RunEvent.of("agent_message", text, labelFor(run, nodeId, name), nodeId));

        } else if (ev.isAgentToolUse()) {
            var e = ev.asAgentToolUse();
            String toolNode = threadNodeId(run, e.sessionThreadId().orElse(null));
            run.emit(RunEvent.of("tool_use", e.name(), labelFor(run, toolNode, null), toolNode));

        } else if (ev.isAgentMcpToolUse()) {
            run.emit(RunEvent.of("tool_use", "(MCP tool)", coordName(run), coordNodeId(run)));

        } else if (ev.isSessionThreadStatusIdle()) {
            // The sub-agent finished its turn. Exhausting its retries is a failure, not a
            // completed piece of work, so the block must not come back green.
            var e = ev.asSessionThreadStatusIdle();
            boolean failed = e.stopReason().isRetriesExhausted();
            finishThread(run, e.sessionThreadId(), e.agentName(),
                    failed ? "failed" : "passed",
                    failed ? "sub-agent exhausted its retries" : null);

        } else if (ev.isSessionThreadStatusTerminated()) {
            // Some threads end without ever going idle; without this their block would
            // stay "running" for the rest of the run.
            var e = ev.asSessionThreadStatusTerminated();
            finishThread(run, e.sessionThreadId(), e.agentName(), "passed", null);

        } else if (ev.isSessionStatusRunning()) {
            run.status = "RUNNING";
            run.emit(RunEvent.of("status", "running"));

        } else if (ev.isSessionStatusIdle()) {
            run.status = "IDLE";
            run.emit(RunEvent.of("status", "idle"));

        } else if (ev.isSessionError()) {
            run.emit(RunEvent.of("error", "Session reported an error."));
        }
    }

    /** Closes out a thread's node, unless it was already marked failed. */
    private static void finishThread(AgentRun run, String threadId, String agentName,
                                     String status, String error) {
        String nodeId = resolveNode(run, threadId, agentName);
        NodeExec ne = nodeExecFor(run, nodeId, agentName);
        if (ne == null || "failed".equals(ne.status)) return;
        ne.status = status;
        if (error != null) ne.error = error;
        ne.endedAt = System.currentTimeMillis();
    }

    /**
     * Thread id first (authoritative), falling back to the agent name — the name is optional on
     * message events, and a thread we never saw created would otherwise be unattributable.
     */
    private static String resolveNode(AgentRun run, String threadId, String agentName) {
        if (threadId != null) {
            String byThread = run.threadToNode.get(threadId);
            if (byThread != null) return byThread;
        }
        String byName = nodeIdByAgentName(run, agentName);
        // Remember it so later events on this thread resolve without the name.
        if (byName != null && threadId != null) run.threadToNode.put(threadId, byName);
        return byName;
    }

    private static NodeExec nodeExecFor(AgentRun run, String nodeId, String fallbackName) {
        if (nodeId == null) return null;
        return run.nodeExec(nodeId, "agent", labelFor(run, nodeId, fallbackName));
    }

    /** The node behind a thread id; the coordinator when the event names no thread. */
    private static String threadNodeId(AgentRun run, String threadId) {
        if (threadId == null) return coordNodeId(run);
        String nodeId = run.threadToNode.get(threadId);
        return nodeId != null ? nodeId : coordNodeId(run);
    }

    private static String coordNodeId(AgentRun run) {
        return run.compiled == null ? null : run.compiled.coordinator().nodeId;
    }

    /** Display name for a node, preferring the compiled flow's agent name. */
    private static String labelFor(AgentRun run, String nodeId, String fallbackName) {
        if (run.compiled != null && nodeId != null) {
            if (nodeId.equals(run.compiled.coordinator().nodeId)) return run.compiled.coordinator().name;
            for (AgentSpec s : run.compiled.subAgents()) {
                if (nodeId.equals(s.nodeId)) return s.name;
            }
        }
        return fallbackName != null ? fallbackName : nodeId;
    }

    private static String nodeIdByAgentName(AgentRun run, String agentName) {
        if (run.compiled == null || agentName == null || agentName.isBlank()) return null;
        if (agentName.equals(run.compiled.coordinator().name)) return run.compiled.coordinator().nodeId;
        for (AgentSpec s : run.compiled.subAgents()) {
            if (agentName.equals(s.name)) return s.nodeId;
        }
        return null;
    }

    private static String coordName(AgentRun run) {
        return run.compiled == null ? "coordinator" : run.compiled.coordinator().name;
    }

    private static NodeExec coordExec(AgentRun run) {
        if (run.compiled == null) return null;
        var c = run.compiled.coordinator();
        return run.nodeExec(c.nodeId, "agent", c.name);
    }

    private static String sentText(List<BetaManagedAgentsAgentThreadMessageSentEvent.Content> content) {
        StringBuilder sb = new StringBuilder();
        for (var c : content) {
            if (c.isText()) sb.append(c.asText().text());
        }
        return sb.toString();
    }

    private static String receivedText(List<BetaManagedAgentsAgentThreadMessageReceivedEvent.Content> content) {
        StringBuilder sb = new StringBuilder();
        for (var c : content) {
            if (c.isText()) sb.append(c.asText().text());
        }
        return sb.toString();
    }
}
