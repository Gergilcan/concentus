package com.concentus.web;

import com.concentus.runners.RunnerWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * The two sockets: the live run-output stream at {@code /ws/runs?runId=...} for browsers, and
 * {@code /ws/runner} for runners — machines that execute runs for this backend, authenticated by
 * the bearer token their handshake carries rather than by a session.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RunWebSocketHandler handler;
    private final RunnerWebSocketHandler runners;
    private final RunnerWebSocketHandler.Handshake runnerHandshake;

    public WebSocketConfig(RunWebSocketHandler handler, RunnerWebSocketHandler runners,
                           RunnerWebSocketHandler.Handshake runnerHandshake) {
        this.handler = handler;
        this.runners = runners;
        this.runnerHandshake = runnerHandshake;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/runs").setAllowedOriginPatterns("*");
        registry.addHandler(runners, "/ws/runner").addInterceptors(runnerHandshake).setAllowedOriginPatterns("*");
    }
}
