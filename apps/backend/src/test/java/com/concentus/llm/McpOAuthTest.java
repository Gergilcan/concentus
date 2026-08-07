package com.concentus.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Signing in to an OAuth-protected MCP server.
 *
 * <p>The stub here mimics the shape a real one uses: an unauthenticated request answered with a
 * {@code WWW-Authenticate} pointer, metadata behind it, and dynamic client registration. That
 * chain is the part with no second chance — get discovery wrong and every server looks like it
 * does not support OAuth at all.
 */
class McpOAuthTest {

    private HttpServer server;
    private McpOAuth oauth;
    private final Map<String, String> lastForm = new HashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        oauth = new McpOAuth(new ObjectMapper());

        server.createContext("/mcp", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("WWW-Authenticate",
                    "Bearer resource_metadata=\"" + base() + "/.well-known/oauth-protected-resource\"");
            respond(ex, 401, "{\"error\":\"unauthorized\"}");
        });
        server.createContext("/.well-known/oauth-protected-resource", ex -> respond(ex, 200, """
                {"resource":"%s/mcp","authorization_servers":["%s"]}""".formatted(base(), base())));
        server.createContext("/.well-known/oauth-authorization-server", ex -> respond(ex, 200, """
                {"authorization_endpoint":"%s/authorize","token_endpoint":"%s/token",
                 "registration_endpoint":"%s/register"}""".formatted(base(), base(), base())));
        server.createContext("/register", ex -> {
            ex.getRequestBody().readAllBytes();
            respond(ex, 200, "{\"client_id\":\"client-123\"}");
        });
        server.createContext("/token", ex -> {
            String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastForm.clear();
            for (String pair : form.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    lastForm.put(pair.substring(0, eq),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                }
            }
            respond(ex, 200, """
                    {"access_token":"at-1","refresh_token":"rt-1","expires_in":3600}""");
        });
        server.start();
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void discoveryFollowsTheChallengeToTheAuthorizationServer() {
        McpOAuth.Endpoints endpoints = oauth.discover(base() + "/mcp");

        assertThat(endpoints.authorizationUrl()).isEqualTo(base() + "/authorize");
        assertThat(endpoints.tokenUrl()).isEqualTo(base() + "/token");
        assertThat(endpoints.registrationUrl()).isEqualTo(base() + "/register");
        // The resource identifier, which binds the token to this MCP endpoint specifically.
        assertThat(endpoints.resource()).isEqualTo(base() + "/mcp");
    }

    @Test
    void registrationYieldsAClientId() {
        McpOAuth.Endpoints endpoints = oauth.discover(base() + "/mcp");

        assertThat(oauth.register(endpoints, "http://localhost:3000/cb", "Concentus").clientId())
                .isEqualTo("client-123");
    }

    @Test
    void aServerWithoutRegistrationSaysSoRatherThanFailingLater() {
        McpOAuth.Endpoints noRegistration =
                new McpOAuth.Endpoints(base() + "/authorize", base() + "/token", null, base() + "/mcp");

        assertThatThrownBy(() -> oauth.register(noRegistration, "http://localhost:3000/cb", "C"))
                .isInstanceOf(McpOAuth.OAuthNotSupported.class)
                .hasMessageContaining("dynamic client registration");
    }

    @Test
    void theAuthorizationUrlCarriesAHashRatherThanTheVerifier() {
        // PKCE: if the URL carried the verifier, intercepting the redirect would be enough to mint
        // a token, which is the whole thing PKCE exists to prevent.
        McpOAuth.Endpoints endpoints = oauth.discover(base() + "/mcp");
        String verifier = oauth.newCodeVerifier();

        String url = oauth.authorizationUrl(endpoints, new McpOAuth.Registration("client-123", null),
                "http://localhost:3000/cb", verifier, "state-1", null);

        assertThat(url).doesNotContain(verifier);
        assertThat(url).contains("code_challenge=" + McpOAuth.challengeFor(verifier))
                .contains("code_challenge_method=S256")
                .contains("state=state-1")
                .contains("response_type=code");
    }

    @Test
    void theAuthorizationUrlNamesTheResource() {
        McpOAuth.Endpoints endpoints = oauth.discover(base() + "/mcp");

        String url = oauth.authorizationUrl(endpoints, new McpOAuth.Registration("c", null),
                "http://localhost:3000/cb", oauth.newCodeVerifier(), "s", null);

        // Without it an authorization server fronting several resources can issue a token the MCP
        // server then refuses as not its own.
        assertThat(url).contains("resource=");
    }

    @Test
    void aQueryStringOnTheAuthorizeEndpointIsAppendedToRatherThanBroken() {
        McpOAuth.Endpoints withQuery = new McpOAuth.Endpoints(
                base() + "/authorize?tenant=acme", base() + "/token", null, base() + "/mcp");

        String url = oauth.authorizationUrl(withQuery, new McpOAuth.Registration("c", null),
                "http://localhost:3000/cb", oauth.newCodeVerifier(), "s", null);

        assertThat(url).contains("?tenant=acme&response_type=code");
    }

    @Test
    void theCodeExchangeSendsTheVerifierAndGetsTokens() {
        McpOAuth.Endpoints endpoints = oauth.discover(base() + "/mcp");
        String verifier = oauth.newCodeVerifier();

        McpOAuth.Tokens tokens = oauth.exchangeCode(endpoints,
                new McpOAuth.Registration("client-123", null), "the-code",
                "http://localhost:3000/cb", verifier);

        assertThat(tokens.accessToken()).isEqualTo("at-1");
        assertThat(tokens.refreshToken()).isEqualTo("rt-1");
        assertThat(tokens.isFresh()).isTrue();
        assertThat(lastForm).containsEntry("grant_type", "authorization_code")
                .containsEntry("code_verifier", verifier)
                .containsEntry("code", "the-code");
    }

    @Test
    void aRefreshKeepsTheOldTokenWhenTheServerDoesNotRotateIt() throws Exception {
        server.removeContext("/token");
        server.createContext("/token", ex -> {
            ex.getRequestBody().readAllBytes();
            respond(ex, 200, "{\"access_token\":\"at-2\",\"expires_in\":3600}");
        });
        McpOAuth.Endpoints endpoints = oauth.discover(base() + "/mcp");

        McpOAuth.Tokens tokens = oauth.refresh(endpoints,
                new McpOAuth.Registration("client-123", null), "rt-original");

        assertThat(tokens.accessToken()).isEqualTo("at-2");
        // Overwriting with null would discard the only thing that can renew access, and the
        // failure would surface days later as an unexplained 401.
        assertThat(tokens.refreshToken()).isEqualTo("rt-original");
    }

    @Test
    void aTokenWithNoStatedExpiryIsTreatedAsUsable() {
        // Some servers simply don't say. Treating that as "already expired" would refuse to use a
        // perfectly good token; a 401 later is what forces the refresh instead.
        assertThat(new McpOAuth.Tokens("at", "rt", null).isFresh()).isTrue();
        assertThat(new McpOAuth.Tokens("", "rt", null).isFresh()).isFalse();
    }

    @Test
    void aResourceThatNamesNoAuthorizationServerIsReportedClearly() throws Exception {
        server.removeContext("/.well-known/oauth-protected-resource");
        server.createContext("/.well-known/oauth-protected-resource",
                ex -> respond(ex, 200, "{\"resource\":\"x\"}"));

        assertThatThrownBy(() -> oauth.discover(base() + "/mcp"))
                .isInstanceOf(McpOAuth.OAuthNotSupported.class)
                .hasMessageContaining("Use a token on the node instead");
    }

    @Test
    void pkceChallengesAreUrlSafeAndUnpadded() {
        String challenge = McpOAuth.challengeFor("abc");

        assertThat(challenge).doesNotContain("=").doesNotContain("+").doesNotContain("/");
    }

    @Test
    void eachSignInGetsItsOwnVerifierAndState() {
        assertThat(oauth.newCodeVerifier()).isNotEqualTo(oauth.newCodeVerifier());
        assertThat(oauth.newState()).isNotEqualTo(oauth.newState());
    }
}
