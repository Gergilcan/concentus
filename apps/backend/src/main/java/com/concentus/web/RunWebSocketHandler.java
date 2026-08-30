package com.concentus.web;

import com.concentus.model.RunEvent;
import com.concentus.service.AgentRun;
import com.concentus.service.RunService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.function.Consumer;

/**
 * Streams a run's output events to the browser and accepts inbound commands.
 *
 * <p>Connect to {@code /ws/runs?runId=<id>}. On connect the buffered history is
 * replayed, then live events follow. A client may also send {@code {"type":"command","text":"..."}}.
 */
@Component
public class RunWebSocketHandler extends TextWebSocketHandler {

    private final RunService runService;
    private final ObjectMapper mapper;
    /** Which groups' runs the socket's principal may watch; read by principal, since a socket has no security context. */
    private final com.concentus.groups.GroupContext groups;

    public RunWebSocketHandler(RunService runService, ObjectMapper mapper, com.concentus.groups.GroupContext groups) {
        this.groups = groups;
        this.runService = runService;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String runId = queryParam(session, "runId");
        AgentRun run = runId == null ? null : runService.get(runId).orElse(null);
        // Another organization's run is unknown here too: the handshake carried the browser's
        // session, so the principal is on the socket, and it answers the same question the REST
        // endpoints answer with a 404.
        if (run != null && !organizationOf(session).equals(run.organizationId)) run = null;
        // And a group's run is unknown to somebody outside the group, exactly as the run list
        // and the run endpoints answer — the id is not a secret, but it must not be a key.
        if (run != null && !seesGroupOf(session, run)) run = null;
        if (run == null) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Unknown runId"));
            return;
        }

        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, 5_000, 1 << 20);
        Consumer<RunEvent> listener = ev -> send(safe, ev);

        // Replay history, then attach for live updates.
        for (RunEvent e : run.bufferedEvents()) {
            send(safe, e);
        }
        run.addListener(listener);
        session.getAttributes().put("run", run);
        session.getAttributes().put("listener", listener);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        AgentRun run = (AgentRun) session.getAttributes().get("run");
        if (run == null) return;
        JsonNode node = mapper.readTree(message.getPayload());
        String type = node.path("type").asText("");
        if ("command".equals(type)) {
            String text = node.path("text").asText("");
            if (!text.isBlank()) {
                runService.sendCommand(run.id, text);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AgentRun run = (AgentRun) session.getAttributes().get("run");
        Consumer<RunEvent> listener = (Consumer<RunEvent>) session.getAttributes().get("listener");
        if (run != null && listener != null) {
            run.removeListener(listener);
        }
    }

    private void send(WebSocketSession session, RunEvent event) {
        try {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(event)));
        } catch (Exception ignored) {
            // client went away; the close handler will detach the listener
        }
    }

    /**
     * The organization of the browser this socket belongs to, from the principal the handshake
     * carried; a value no run can match when there is none, so an unauthenticated socket sees
     * nothing rather than everything.
     */
    private static String organizationOf(WebSocketSession session) {
        com.concentus.auth.ConcentusUserDetails user = principalOf(session);
        return user == null ? "" : user.organizationId();
    }

    /** Unscoped: everybody. Scoped: the group's members and the organization's admins. */
    private boolean seesGroupOf(WebSocketSession session, AgentRun run) {
        if (run.groupId == null || run.groupId.isBlank()) return true;
        com.concentus.auth.ConcentusUserDetails user = principalOf(session);
        if (user == null) return false;
        if (com.concentus.auth.Accounts.ROLE_ADMIN.equalsIgnoreCase(user.role())) return true;
        return groups.of(user.userId(), user.organizationId()).groupIds().contains(run.groupId);
    }

    private static com.concentus.auth.ConcentusUserDetails principalOf(WebSocketSession session) {
        if (session.getPrincipal() instanceof org.springframework.security.core.Authentication auth
                && auth.getPrincipal() instanceof com.concentus.auth.ConcentusUserDetails user) {
            return user;
        }
        return null;
    }

    private static String queryParam(WebSocketSession session, String key) {
        String query = session.getUri() == null ? null : session.getUri().getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(key)) {
                return java.net.URLDecoder.decode(pair.substring(i + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
