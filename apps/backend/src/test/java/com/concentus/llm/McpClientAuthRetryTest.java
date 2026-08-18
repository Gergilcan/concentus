package com.concentus.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What happens when an access token dies in the middle of a run.
 *
 * <p>OAuth-protected servers hand out short-lived tokens — Resend's last fifteen minutes — and a
 * flow that reads a mailbox, thinks, and then sends a mail can easily outlive one. The token was
 * fine when the client was built, so the failure lands on whichever call happens to be the first
 * one after the clock ran out, which is nowhere near where a person would look for the cause.
 */
class McpClientAuthRetryTest {

    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    /** The only token the stub server accepts; anything else comes back 401. */
    private volatile String accepted = "fresh";
    private final AtomicInteger rejections = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + accepted).equals(auth)) {
                rejections.incrementAndGet();
                respond(ex, 401, "{\"error\":\"token expired\"}");
                return;
            }
            if (body.contains("\"initialize\"")) {
                ex.getResponseHeaders().add("MCP-Session-Id", "s-1");
                respond(ex, 200, """
                        {"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25",
                         "capabilities":{},"serverInfo":{"name":"stub","version":"1"}}}""");
            } else if (body.contains("notifications/initialized")) {
                respond(ex, 202, "");
            } else {
                respond(ex, 200, """
                        {"jsonrpc":"2.0","id":2,"result":{"tools":[
                          {"name":"send_email","description":"Sends","inputSchema":{"type":"object"}}]}}""");
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void renews_the_token_and_retries_when_the_server_answers_401() {
        AtomicInteger renewals = new AtomicInteger();
        McpClient.TokenSource auth = new McpClient.TokenSource() {
            private String token = "stale";

            @Override
            public String current() {
                return token;
            }

            @Override
            public String renew() {
                renewals.incrementAndGet();
                token = "fresh";
                return token;
            }
        };

        try (McpClient client = new McpClient("resend", base() + "/mcp", auth, mapper)) {
            assertThat(client.listTools()).extracting(ChatTypes.ToolSpec::name)
                    .containsExactly("send_email");
        }

        assertThat(renewals).hasValue(1);
        assertThat(rejections).hasValue(1);
    }

    @Test
    void says_which_server_needs_authorizing_again_when_the_token_cannot_be_renewed() {
        McpClient.TokenSource auth = new McpClient.TokenSource() {
            @Override
            public String current() {
                return "stale";
            }

            @Override
            public String renew() {
                return null;
            }
        };

        try (McpClient client = new McpClient("resend", base() + "/mcp", auth, mapper)) {
            assertThatThrownBy(client::listTools)
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("resend")
                    .hasMessageContaining("authoriz");
        }
    }

    @Test
    void gives_up_after_one_renewal_rather_than_hammering_the_server() {
        accepted = "never-this";
        AtomicInteger renewals = new AtomicInteger();
        McpClient.TokenSource auth = new McpClient.TokenSource() {
            @Override
            public String current() {
                return "stale";
            }

            @Override
            public String renew() {
                renewals.incrementAndGet();
                return "still-wrong";
            }
        };

        try (McpClient client = new McpClient("resend", base() + "/mcp", auth, mapper)) {
            assertThatThrownBy(client::listTools).isInstanceOf(LlmException.class);
        }

        assertThat(renewals).hasValue(1);
        assertThat(rejections).hasValue(2);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
