package com.concentus.web;

import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.model.RunSummary;
import com.concentus.service.RunService;
import com.concentus.store.FlowStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WebhookController}'s request authentication. Uses hand-wired mocks rather
 * than a Spring context, since only the controller's own logic is under test.
 *
 * <p>The controller is provider-agnostic: the Input node names the parameter carrying the proof,
 * and the value authenticates if it's an HMAC-SHA256 of the raw body OR the secret verbatim.
 */
class WebhookControllerTest {

    private static final String SECRET = "s3cr3t";

    private FlowStore flows = mock(FlowStore.class);
    private RunService runService = mock(RunService.class);

    private WebhookController controllerFor(FlowGraph flow) {
        flows = mock(FlowStore.class);
        runService = mock(RunService.class);
        when(flows.get("f1")).thenReturn(Optional.of(flow));
        when(runService.start(any(FlowGraph.class), anyString())).thenReturn(summary());
        return new WebhookController(flows, runService, new ObjectMapper());
    }

    private static FlowGraph webhookFlow(String secret, String authParam) {
        Map<String, Object> data = new HashMap<>();
        data.put("mode", "webhook");
        if (secret != null) data.put("secret", secret);
        if (authParam != null) data.put("authParam", authParam);
        FlowNode input = new FlowNode("in1", "input", null, data);
        return new FlowGraph("f1", "Flow", "managed", List.of(input), List.of(), null, List.of(), null, null);
    }

    private static RunSummary summary() {
        return new RunSummary("run_abc123", "f1", "Flow", "managed", "RUNNING", 0L, null, List.of(), null, "webhook",
                0L, 0L, 0.0);
    }

    /** Signs exactly as a provider would: hex HMAC-SHA256 over the raw body bytes. */
    private static String sign(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static MockHttpServletRequest withHeader(String name, String value) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.addHeader(name, value);
        return r;
    }

    // ---- signature scheme (Linear, GitHub, …) ----

    @Test
    void validSignaturePasses() throws Exception {
        byte[] body = "{\"action\":\"create\"}".getBytes(StandardCharsets.UTF_8);
        WebhookController controller = controllerFor(webhookFlow(SECRET, "Linear-Signature"));

        ResponseEntity<Map<String, String>> response = controller.receive(
                "f1", body, withHeader("Linear-Signature", sign(body, SECRET)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("runId", "run_abc123");
    }

    @Test
    void signatureOverDifferentBodyIsRejected() throws Exception {
        // A signature valid for *some* payload must not authorize a tampered one.
        String signatureOfOtherBody = sign("{\"other\":true}".getBytes(StandardCharsets.UTF_8), SECRET);
        WebhookController controller = controllerFor(webhookFlow(SECRET, "Linear-Signature"));

        assertThatThrownBy(() -> controller.receive(
                "f1",
                "{\"action\":\"create\"}".getBytes(StandardCharsets.UTF_8),
                withHeader("Linear-Signature", signatureOfOtherBody)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(runService, never()).start(any(), anyString());
    }

    @Test
    void signatureFromWrongSecretIsRejected() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        WebhookController controller = controllerFor(webhookFlow(SECRET, "Linear-Signature"));

        assertThatThrownBy(() -> controller.receive(
                "f1", body, withHeader("Linear-Signature", sign(body, "not-the-secret"))))
                .isInstanceOf(ResponseStatusException.class);
        verify(runService, never()).start(any(), anyString());
    }

    @Test
    void parameterNameIsConfigurable() throws Exception {
        // Same code path, different provider: GitHub's header, with its "sha256=" prefix.
        byte[] body = "{\"zen\":\"…\"}".getBytes(StandardCharsets.UTF_8);
        WebhookController controller = controllerFor(webhookFlow(SECRET, "X-Hub-Signature-256"));

        ResponseEntity<Map<String, String>> response = controller.receive(
                "f1", body, withHeader("X-Hub-Signature-256", "sha256=" + sign(body, SECRET)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void signatureUnderTheWrongParameterNameIsRejected() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        WebhookController controller = controllerFor(webhookFlow(SECRET, "X-Hub-Signature-256"));

        // Correctly signed, but sent under a header the flow isn't configured to read.
        assertThatThrownBy(() -> controller.receive(
                "f1", body, withHeader("Linear-Signature", sign(body, SECRET))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getReason())
                        .contains("Missing 'X-Hub-Signature-256'"));
    }

    // ---- plain shared-token scheme ----

    @Test
    void plainTokenInHeaderPasses() {
        WebhookController controller = controllerFor(webhookFlow(SECRET, "X-Webhook-Token"));

        ResponseEntity<Map<String, String>> response = controller.receive(
                "f1", "{}".getBytes(StandardCharsets.UTF_8), withHeader("X-Webhook-Token", SECRET));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void plainTokenInQueryStringPasses() {
        WebhookController controller = controllerFor(webhookFlow(SECRET, "token"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("token", SECRET);

        ResponseEntity<Map<String, String>> response =
                controller.receive("f1", "{}".getBytes(StandardCharsets.UTF_8), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void wrongTokenIsRejected() {
        WebhookController controller = controllerFor(webhookFlow(SECRET, "token"));

        assertThatThrownBy(() -> controller.receive(
                "f1", "{}".getBytes(StandardCharsets.UTF_8), withHeader("token", "wrong-token")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(runService, never()).start(any(), anyString());
    }

    @Test
    void missingParameterIsRejected() {
        WebhookController controller = controllerFor(webhookFlow(SECRET, "Linear-Signature"));

        assertThatThrownBy(() -> controller.receive(
                "f1", "{}".getBytes(StandardCharsets.UTF_8), new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(runService, never()).start(any(), anyString());
    }

    // ---- an unconfigured secret must never mean "open endpoint" ----
    // These two previously documented a gap where a blank/null secret skipped the check entirely,
    // letting anyone start a run. Closing it is the point of these assertions.

    @Test
    void blankSecretRejectsEvenAnOtherwiseWellFormedRequest() {
        WebhookController controller = controllerFor(webhookFlow("", "token"));

        assertThatThrownBy(() -> controller.receive(
                "f1", "{}".getBytes(StandardCharsets.UTF_8), withHeader("token", "anything")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(runService, never()).start(any(), anyString());
    }

    @Test
    void missingSecretRejectsRequestWithNoCredentialAtAll() {
        WebhookController controller = controllerFor(webhookFlow(null, null));

        assertThatThrownBy(() -> controller.receive(
                "f1", "{}".getBytes(StandardCharsets.UTF_8), new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(runService, never()).start(any(), anyString());
    }

    // ---- replay protection ----

    @Test
    void staleTimestampIsRejectedAsReplay() throws Exception {
        long tenMinutesAgo = System.currentTimeMillis() - 600_000;
        byte[] body = ("{\"webhookTimestamp\":" + tenMinutesAgo + "}").getBytes(StandardCharsets.UTF_8);
        WebhookController controller = controllerFor(webhookFlow(SECRET, "Linear-Signature"));

        // Signature is genuine — this is a captured delivery being replayed later.
        assertThatThrownBy(() -> controller.receive(
                "f1", body, withHeader("Linear-Signature", sign(body, SECRET))))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getReason()).contains("replay"));
        verify(runService, never()).start(any(), anyString());
    }

    @Test
    void freshTimestampPasses() throws Exception {
        byte[] body = ("{\"webhookTimestamp\":" + System.currentTimeMillis() + "}")
                .getBytes(StandardCharsets.UTF_8);
        WebhookController controller = controllerFor(webhookFlow(SECRET, "Linear-Signature"));

        ResponseEntity<Map<String, String>> response = controller.receive(
                "f1", body, withHeader("Linear-Signature", sign(body, SECRET)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void payloadWithoutTimestampStillPasses() throws Exception {
        // Not every provider stamps deliveries; absence must not block them.
        byte[] body = "{\"action\":\"create\"}".getBytes(StandardCharsets.UTF_8);
        WebhookController controller = controllerFor(webhookFlow(SECRET, "Linear-Signature"));

        ResponseEntity<Map<String, String>> response = controller.receive(
                "f1", body, withHeader("Linear-Signature", sign(body, SECRET)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }
}
