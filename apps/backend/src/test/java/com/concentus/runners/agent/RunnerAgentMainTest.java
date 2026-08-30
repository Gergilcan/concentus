package com.concentus.runners.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The command line, and the two spellings of the hub's address the agent has to derive from one. */
class RunnerAgentMainTest {

    @Test
    void flags_are_parsed_in_both_spellings_and_unknown_ones_are_refused() {
        Map<String, String> flags = RunnerAgentMain.parse(new String[] {
                "--url", "https://hub.example", "--token=crn_x", "--name", "nas", "--max-processes", "2"});

        assertThat(flags).containsEntry("url", "https://hub.example").containsEntry("token", "crn_x")
                .containsEntry("name", "nas").containsEntry("max-processes", "2");
        assertThatThrownBy(() -> RunnerAgentMain.parse(new String[] {"--url"})).hasMessageContaining("needs a value");
        assertThatThrownBy(() -> RunnerAgentMain.parse(new String[] {"--dance", "x"})).hasMessageContaining("Unknown flag");
        assertThatThrownBy(() -> RunnerAgentMain.parse(new String[] {"serve"})).hasMessageContaining("Unexpected");
    }

    @Test
    void the_hub_address_becomes_a_socket_url_and_an_http_url_whatever_was_pasted() {
        assertThat(new RunnerAgent.Config("https://hub.example/", "t", null, null).socketUrl())
                .isEqualTo("wss://hub.example/ws/runner");
        assertThat(new RunnerAgent.Config("https://hub.example/", "t", null, null).httpUrl())
                .isEqualTo("https://hub.example");
        assertThat(new RunnerAgent.Config("http://127.0.0.1:8734", "t", null, null).socketUrl())
                .isEqualTo("ws://127.0.0.1:8734/ws/runner");
        assertThat(new RunnerAgent.Config("ws://hub:8080/ws/runner", "t", null, null).httpUrl())
                .isEqualTo("http://hub:8080");
        assertThat(new RunnerAgent.Config("wss://hub.example/ws/runner", "t", null, null).socketUrl())
                .isEqualTo("wss://hub.example/ws/runner");
        // A bare host is taken as https: a runner talks to a hub on the internet.
        assertThat(new RunnerAgent.Config("hub.example", "t", null, null).socketUrl())
                .isEqualTo("wss://hub.example/ws/runner");
    }

    @Test
    void the_auth_kind_is_the_api_key_when_one_is_set_else_the_login() {
        // The environment is what it is on this machine; only the login-derived branch is
        // deterministic here.
        boolean apiKey = System.getenv("ANTHROPIC_API_KEY") != null && !System.getenv("ANTHROPIC_API_KEY").isBlank();
        assertThat(RunnerAgent.authKind(true)).isEqualTo(apiKey ? "api-key" : "subscription");
    }
}
