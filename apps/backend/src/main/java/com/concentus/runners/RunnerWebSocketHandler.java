package com.concentus.runners;

import com.concentus.runners.protocol.Frame;
import com.concentus.runners.protocol.Frames;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * The runner protocol's server end, at {@code /ws/runner}.
 *
 * <p>Authentication is the handshake's: {@link Handshake} turns the bearer token into the runner
 * row, or refuses the upgrade with the status the runner should not retry on. The first frame
 * after that must be {@code hello}; only then is the runner registered and given work.
 */
@Component
public class RunnerWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RunnerWebSocketHandler.class);

    static final String RUNNER = "runner";
    private static final String CONNECTION = "connection";

    /**
     * A patch of a large checkout, or a merge workspace's worth of files, is megabytes. Tomcat
     * hands them over in parts; Spring reassembles them up to this limit.
     */
    static final int MAX_TEXT_MESSAGE = 32 * 1024 * 1024;

    private final RunnerRegistry registry;
    private final ObjectMapper mapper;

    public RunnerWebSocketHandler(RunnerRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Runner runner = (Runner) session.getAttributes().get(RUNNER);
        if (runner == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("No runner"));
            return;
        }
        session.setTextMessageSizeLimit(MAX_TEXT_MESSAGE);
        WebSocketSession safe = new org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator(
                session, 60_000, 64 * 1024 * 1024);
        session.getAttributes().put(CONNECTION, new RunnerConnection(runner, safe, mapper));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        RunnerConnection connection = (RunnerConnection) session.getAttributes().get(CONNECTION);
        if (connection == null) return;
        Frame frame;
        try {
            frame = Frames.read(mapper, message.getPayload());
        } catch (IllegalArgumentException e) {
            log.warn("Runner {} sent something that is not a frame: {}", connection.runnerId(), e.getMessage());
            return;
        }
        if (connection.hello() == null) {
            if (frame instanceof Frame.Hello hello) {
                connection.welcomed(hello);
                registry.connect(connection);
                connection.send(new Frame.Welcome(connection.runnerId(), connection.runnerName()));
            } else {
                session.close(CloseStatus.PROTOCOL_ERROR.withReason("hello first"));
            }
            return;
        }
        if (frame instanceof Frame.Heartbeat) registry.heartbeat(connection);
        connection.deliver(frame);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        RunnerConnection connection = (RunnerConnection) session.getAttributes().get(CONNECTION);
        if (connection != null) registry.disconnect(connection);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        RunnerConnection connection = (RunnerConnection) session.getAttributes().get(CONNECTION);
        log.debug("Runner socket error ({}): {}", connection == null ? "?" : connection.runnerId(),
                exception.getMessage());
    }

    /**
     * Turns {@code Authorization: Bearer crn_…} into the runner row, before the upgrade.
     *
     * <p>Refused with 401 for a token that resolves to nothing and 403 for a revoked one — the two
     * answers a runner must not retry on, as opposed to a dropped socket, which it must. The hash
     * comparison is constant-time after the lookup, like the service account filter's.
     */
    @Component
    public static class Handshake implements HandshakeInterceptor {

        private final RunnerStore store;

        public Handshake(RunnerStore store) {
            this.store = store;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String token = bearer(request.getHeaders().getFirst("Authorization"));
            if (!RunnerTokens.looksLike(token)) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            String hash = RunnerTokens.hash(token);
            Runner runner = store.findByTokenHash(hash).orElse(null);
            if (runner == null || !MessageDigest.isEqual(runner.tokenHash().getBytes(StandardCharsets.UTF_8),
                    hash.getBytes(StandardCharsets.UTF_8))) {
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            if (runner.revoked()) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            attributes.put(RUNNER, runner);
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }

        static String bearer(String header) {
            if (header == null) return null;
            String trimmed = header.trim();
            if (trimmed.length() < 7 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
            String token = trimmed.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
    }
}
