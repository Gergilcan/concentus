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

    public RunWebSocketHandler(RunService runService, ObjectMapper mapper) {
        this.runService = runService;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String runId = queryParam(session, "runId");
        AgentRun run = runId == null ? null : runService.get(runId).orElse(null);
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
    @SuppressWarnings("unchecked")
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
