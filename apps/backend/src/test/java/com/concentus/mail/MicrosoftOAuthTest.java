package com.concentus.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The device code flow against a stub Entra, exercised through the responses that actually decide
 * whether a mailbox keeps polling: pending, rotated tokens, and the errors an operator has to fix.
 */
class MicrosoftOAuthTest {

    private HttpServer server;
    private MicrosoftOAuth oauth;
    /** Queued responses, so a test can script "pending, pending, approved". */
    private final Deque<Response> responses = new ArrayDeque<>();
    private final List<String> requestBodies = new ArrayList<>();

    private record Response(int status, String body) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Response next = responses.isEmpty() ? new Response(500, "{}") : responses.poll();
            byte[] body = next.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(next.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        oauth = new MicrosoftOAuth(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void queue(int status, String body) {
        responses.add(new Response(status, body));
    }

    @Test
    void startDeviceCodeReturnsWhatThePersonHasToEnter() {
        queue(200, """
                {"device_code":"DEV-1","user_code":"ABCD-EFGH",
                 "verification_uri":"https://microsoft.com/devicelogin",
                 "message":"Enter ABCD-EFGH","expires_in":900,"interval":5}""");

        MicrosoftOAuth.DeviceCode code = oauth.startDeviceCode("tenant", "client");

        assertThat(code.userCode()).isEqualTo("ABCD-EFGH");
        assertThat(code.deviceCode()).isEqualTo("DEV-1");
        assertThat(code.interval()).isEqualTo(5);
        assertThat(requestBodies.getFirst()).contains("client_id=client")
                .contains("IMAP.AccessAsUser.All");
    }

    @Test
    void startDeviceCodeRequestsOfflineAccess() {
        // Without it Entra returns no refresh token, and the mailbox stops polling after an hour.
        queue(200, """
                {"device_code":"D","user_code":"U","verification_uri":"https://x","message":"m"}""");

        oauth.startDeviceCode("tenant", "client");

        assertThat(requestBodies.getFirst()).contains("offline_access");
    }

    @Test
    void aPendingSignInIsNotAFailure() {
        queue(400, """
                {"error":"authorization_pending","error_description":"still waiting"}""");

        assertThat(oauth.pollDeviceCode("tenant", "client", "DEV-1")).isEmpty();
    }

    @Test
    void slowDownIsAlsoJustPending() {
        queue(400, """
                {"error":"slow_down","error_description":"polling too fast"}""");

        assertThat(oauth.pollDeviceCode("tenant", "client", "DEV-1")).isEmpty();
    }

    @Test
    void pollReturnsTokensOnceApproved() {
        queue(200, """
                {"access_token":"AT","refresh_token":"RT","expires_in":3599}""");

        Optional<MicrosoftOAuth.Tokens> tokens = oauth.pollDeviceCode("tenant", "client", "DEV-1");

        assertThat(tokens).isPresent();
        assertThat(tokens.get().accessToken()).isEqualTo("AT");
        assertThat(tokens.get().refreshToken()).isEqualTo("RT");
        assertThat(tokens.get().isFresh()).isTrue();
    }

    @Test
    void publicClientFlowNotAllowedExplainsTheSettingToChange() {
        queue(400, """
                {"error":"unauthorized_client","error_description":"AADSTS7000218"}""");

        assertThatThrownBy(() -> oauth.pollDeviceCode("tenant", "client", "DEV-1"))
                .isInstanceOf(MicrosoftOAuth.OAuthException.class)
                .hasMessageContaining("Allow public client flows");
    }

    @Test
    void anExpiredCodeSaysToStartAgain() {
        queue(400, """
                {"error":"expired_token","error_description":"code expired"}""");

        assertThatThrownBy(() -> oauth.pollDeviceCode("tenant", "client", "DEV-1"))
                .hasMessageContaining("Start the sign-in again");
    }

    @Test
    void refreshKeepsTheOldTokenWhenEntraOmitsARotatedOne() {
        // Entra returns refresh_token only when it rotates. Overwriting with null would lose the
        // only credential that can renew access.
        queue(200, """
                {"access_token":"AT2","expires_in":3599}""");

        MicrosoftOAuth.Tokens tokens = oauth.refresh("tenant", "client", "OLD-RT");

        assertThat(tokens.accessToken()).isEqualTo("AT2");
        assertThat(tokens.refreshToken()).isEqualTo("OLD-RT");
    }

    @Test
    void refreshTakesTheRotatedTokenWhenEntraSendsOne() {
        queue(200, """
                {"access_token":"AT2","refresh_token":"NEW-RT","expires_in":3599}""");

        assertThat(oauth.refresh("tenant", "client", "OLD-RT").refreshToken()).isEqualTo("NEW-RT");
    }

    @Test
    void aRevokedSignInSaysToSignInAgain() {
        queue(400, """
                {"error":"invalid_grant","error_description":"AADSTS50173 password changed"}""");

        assertThatThrownBy(() -> oauth.refresh("tenant", "client", "OLD-RT"))
                .hasMessageContaining("Sign in again");
    }

    @Test
    void refreshingWithoutAStoredTokenFailsBeforeAnyRequest() {
        assertThatThrownBy(() -> oauth.refresh("tenant", "client", " "))
                .hasMessageContaining("No refresh token stored");
        assertThat(requestBodies).isEmpty();
    }

    @Test
    void missingIdsAreReportedRatherThanSentAsBlanks() {
        assertThatThrownBy(() -> oauth.startDeviceCode("", "client"))
                .hasMessageContaining("MAIL_MICROSOFT_TENANT_ID");
        assertThat(requestBodies).isEmpty();
    }

    @Test
    void aNodeThatNamesNoRegistrationUsesTheDeploymentsOwn() {
        // One Entra registration serves every mailbox, so asking for the same two ids on each node
        // is asking the same question over and over.
        MicrosoftOAuth configured = new MicrosoftOAuth(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "tenant-default", "client-default");
        queue(200, """
                {"device_code":"D","user_code":"U","verification_uri":"https://x","message":"m"}""");

        configured.startDeviceCode("", "");

        assertThat(requestBodies.getFirst()).contains("client_id=client-default");
    }

    @Test
    void aNodeMayStillOverrideTheDeploymentsRegistration() {
        MicrosoftOAuth configured = new MicrosoftOAuth(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), "tenant-default", "client-default");
        queue(200, """
                {"device_code":"D","user_code":"U","verification_uri":"https://x","message":"m"}""");

        configured.startDeviceCode("other-tenant", "other-client");

        assertThat(requestBodies.getFirst()).contains("client_id=other-client");
    }

    @Test
    void aDeploymentWithHalfARegistrationCountsAsHavingNone() {
        // Half is unusable, and reporting it as configured would produce a Connect button that
        // cannot work.
        MicrosoftOAuth half = new MicrosoftOAuth(new ObjectMapper(), "http://x", "tenant-only", "");

        assertThat(half.hasDefaults()).isFalse();
    }

    @Test
    void errorBodiesAreRedactedBeforeTheyBecomeAMessage() {
        // Entra echoes the request, so an unredacted body would carry the refresh token into a log.
        queue(400, """
                {"error":"invalid_request","refresh_token":"0.AAAAsecret-value-here"}""");

        assertThatThrownBy(() -> oauth.pollDeviceCode("tenant", "client", "DEV-1"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-value-here"));
    }

    @Test
    void aFreshlyIssuedTokenIsFreshAndAnExpiringOneIsNot() {
        MicrosoftOAuth.Tokens live = new MicrosoftOAuth.Tokens("AT", "RT",
                java.time.Instant.now().plusSeconds(3600));
        // Inside the safety margin: still technically valid, but not long enough to start a poll on.
        MicrosoftOAuth.Tokens nearlyDone = new MicrosoftOAuth.Tokens("AT", "RT",
                java.time.Instant.now().plusSeconds(60));

        assertThat(live.isFresh()).isTrue();
        assertThat(nearlyDone.isFresh()).isFalse();
    }
}
