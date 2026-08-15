package com.concentus.web;

import com.concentus.auth.AccountStore;
import com.concentus.auth.Accounts;
import com.concentus.auth.OrgContext;
import com.concentus.mcp.Schema;
import com.concentus.mcp.StudioTool;
import com.concentus.mcp.StudioToolset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * The Studio MCP transport: who may call it, and what a caller sees when a tool goes wrong.
 *
 * <p>The auth cases carry the weight. This is the one endpoint that lets something outside the
 * browser reach every flow, run and credential in the application, and its rules are conditional
 * on a deployment setting — the combination that must never work (authentication on, no token
 * configured, request walks in) is exactly the one that is invisible until someone tries it.
 *
 * <p>The error cases pin the other half of the contract: a tool that fails must come back as a
 * readable tool result the model can act on, never as a JSON-RPC transport error that ends the
 * exchange.
 */
class McpStudioControllerTest {

    private static final String TOKEN = "s3cret-token";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------- auth

    @Test
    void withAuthOffAndNoTokenTheEndpointIsOpen() {
        McpStudioController controller = controller(authOff(), "", "", echoTool());

        ResponseEntity<JsonNode> response = call(controller, null, listRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(names(response)).containsExactly("echo");
    }

    @Test
    void withAuthOffAConfiguredTokenIsStillEnforced() {
        McpStudioController controller = controller(authOff(), TOKEN, "", echoTool());

        assertThat(call(controller, null, listRequest()).getStatusCode().value()).isEqualTo(401);
        assertThat(call(controller, "wrong", listRequest()).getStatusCode().value()).isEqualTo(401);
        assertThat(call(controller, TOKEN, listRequest()).getStatusCode().value()).isEqualTo(200);
    }

    /**
     * The case this test class exists for. A reachable deployment with no token configured must
     * refuse everyone — anything else would mean shipping an unauthenticated door to every flow
     * and credential in the organization the moment someone turns accounts on.
     */
    @Test
    void withAuthOnAndNoTokenConfiguredEveryRequestIsRefused() {
        McpStudioController controller = controller(authOn(), "", "", echoTool());

        assertThat(call(controller, null, listRequest()).getStatusCode().value()).isEqualTo(401);
        assertThat(call(controller, TOKEN, listRequest()).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void withAuthOnTheTokenActsAsTheConfiguredAccount() {
        AccountStore accounts = mock(AccountStore.class);
        when(accounts.findByEmail("owner@example.com")).thenReturn(Optional.of(
                new Accounts.UserAccount("u1", "org-7", "owner@example.com", "hash", "ADMIN", true, 0L)));

        McpStudioController controller = new McpStudioController(
                List.of(() -> List.of(echoTool())), MAPPER, authOn(), accounts,
                TOKEN, "owner@example.com");

        assertThat(call(controller, TOKEN, listRequest()).getStatusCode().value()).isEqualTo(200);
    }

    /**
     * A token whose account cannot be resolved is refused rather than run in the default
     * organization: that fallback would be a cross-tenant read, and it would happen silently.
     */
    @Test
    void withAuthOnAnUnknownOrDisabledAccountIsRefused() {
        AccountStore accounts = mock(AccountStore.class);
        when(accounts.findByEmail(anyString())).thenReturn(Optional.empty());
        McpStudioController unknown = new McpStudioController(
                List.of(() -> List.of(echoTool())), MAPPER, authOn(), accounts, TOKEN, "ghost@example.com");
        assertThat(call(unknown, TOKEN, listRequest()).getStatusCode().value()).isEqualTo(401);

        when(accounts.findByEmail("off@example.com")).thenReturn(Optional.of(
                new Accounts.UserAccount("u2", "org-7", "off@example.com", "hash", "USER", false, 0L)));
        McpStudioController disabled = new McpStudioController(
                List.of(() -> List.of(echoTool())), MAPPER, authOn(), accounts, TOKEN, "off@example.com");
        assertThat(call(disabled, TOKEN, listRequest()).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void withAuthOnAndNoAccountConfiguredTheTokenAloneIsNotEnough() {
        McpStudioController controller = controller(authOn(), TOKEN, "", echoTool());

        assertThat(call(controller, TOKEN, listRequest()).getStatusCode().value()).isEqualTo(401);
    }

    /** Nothing this endpoint authenticated as may leak into whatever the thread serves next. */
    @Test
    void theAuthenticatedPrincipalDoesNotOutliveTheRequest() {
        AccountStore accounts = mock(AccountStore.class);
        when(accounts.findByEmail("owner@example.com")).thenReturn(Optional.of(
                new Accounts.UserAccount("u1", "org-7", "owner@example.com", "hash", "ADMIN", true, 0L)));
        McpStudioController controller = new McpStudioController(
                List.of(() -> List.of(echoTool())), MAPPER, authOn(), accounts, TOKEN, "owner@example.com");

        call(controller, TOKEN, listRequest());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // ------------------------------------------------------------- registration

    @Test
    void twoToolsSharingANameFailAtStartupRatherThanSilentlyShadowing() {
        StudioToolset first = () -> List.of(echoTool());
        StudioToolset second = () -> List.of(echoTool());

        assertThatThrownBy(() -> new McpStudioController(List.of(first, second), MAPPER,
                authOff(), mock(AccountStore.class), "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("echo");
    }

    @Test
    void initializeAnnouncesTheServerAndHowToUseIt() {
        McpStudioController controller = controller(authOff(), "", "", echoTool());

        JsonNode result = call(controller, null, request("initialize")).getBody().path("result");

        assertThat(result.path("serverInfo").path("name").asText()).isEqualTo("concentus-studio");
        assertThat(result.path("capabilities").has("tools")).isTrue();
        assertThat(result.path("instructions").asText()).contains("flow_schema", "validate_flow");
    }

    // ------------------------------------------------------------------ errors

    @Test
    void anUnknownToolIsAFixableToolResultNotATransportError() {
        McpStudioController controller = controller(authOff(), "", "", echoTool());

        JsonNode result = callTool(controller, "does_not_exist").path("result");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(text(result)).contains("does_not_exist").contains("tools/list");
    }

    /** Every REST controller reached by a tool throws these for "no such flow" / "no such run". */
    @Test
    void aNotFoundFromTheUnderlyingControllerComesBackAsItsReason() {
        StudioTool exploding = tool("boom", args -> {
            throw new ResponseStatusException(NOT_FOUND, "No such flow");
        });
        McpStudioController controller = controller(authOff(), "", "", exploding);

        JsonNode result = callTool(controller, "boom").path("result");

        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(text(result)).isEqualTo("No such flow");
    }

    @Test
    void aBadArgumentComesBackAsItsMessage() {
        StudioTool exploding = tool("boom", args -> {
            throw new IllegalArgumentException("'flowId' is required and was not given.");
        });
        McpStudioController controller = controller(authOff(), "", "", exploding);

        assertThat(text(callTool(controller, "boom").path("result")))
                .isEqualTo("'flowId' is required and was not given.");
    }

    @Test
    void anUnexpectedFailureIsReportedWithoutEndingTheExchange() {
        StudioTool exploding = tool("boom", args -> {
            throw new NullPointerException("somewhere deep");
        });
        McpStudioController controller = controller(authOff(), "", "", exploding);

        JsonNode body = callTool(controller, "boom");

        assertThat(body.has("error")).isFalse();
        assertThat(body.path("result").path("isError").asBoolean()).isTrue();
        assertThat(text(body.path("result"))).contains("NullPointerException");
    }

    @Test
    void anUnsupportedMethodIsAJsonRpcError() {
        McpStudioController controller = controller(authOff(), "", "", echoTool());

        JsonNode body = call(controller, null, request("resources/list")).getBody();

        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32601);
    }

    /** A tool called with no arguments at all must see an empty object, not a null. */
    @Test
    void missingArgumentsArriveAsAnEmptyObject() {
        McpStudioController controller = controller(authOff(), "", "", echoTool());

        var params = MAPPER.createObjectNode();
        params.put("name", "echo");
        var body = MAPPER.createObjectNode();
        body.put("jsonrpc", "2.0").put("method", "tools/call").put("id", 1);
        body.set("params", params);

        assertThat(text(call(controller, null, body).getBody().path("result"))).isEqualTo("{}");
    }

    // ------------------------------------------------------------------ setup

    private static OrgContext authOff() {
        return new OrgContext("local", false);
    }

    private static OrgContext authOn() {
        return new OrgContext("default", true);
    }

    private static McpStudioController controller(OrgContext orgContext, String token,
                                                  String account, StudioTool... tools) {
        return new McpStudioController(List.of(() -> List.of(tools)), MAPPER, orgContext,
                mock(AccountStore.class), token, account);
    }

    /** Answers with its own arguments, so a test can see exactly what the handler received. */
    private static StudioTool echoTool() {
        return tool("echo", args -> StudioTool.Result.ok(args.toString()));
    }

    private static StudioTool tool(String name, Function<JsonNode, StudioTool.Result> handler) {
        return new StudioTool(name, "test tool", Schema.of(MAPPER).build(), handler);
    }

    private static ResponseEntity<JsonNode> call(McpStudioController controller, String token,
                                                 JsonNode request) {
        return controller.rpc(token == null ? null : "Bearer " + token, null, request);
    }

    private static JsonNode callTool(McpStudioController controller, String name) {
        var params = MAPPER.createObjectNode();
        params.put("name", name);
        params.putObject("arguments");
        var body = MAPPER.createObjectNode();
        body.put("jsonrpc", "2.0").put("method", "tools/call").put("id", 1);
        body.set("params", params);
        return call(controller, null, body).getBody();
    }

    private static JsonNode request(String method) {
        var body = MAPPER.createObjectNode();
        body.put("jsonrpc", "2.0").put("method", method).put("id", 1);
        return body;
    }

    private static JsonNode listRequest() {
        return request("tools/list");
    }

    private static List<String> names(ResponseEntity<JsonNode> response) {
        return response.getBody().path("result").path("tools").findValuesAsText("name");
    }

    private static String text(JsonNode result) {
        return result.path("content").path(0).path("text").asText();
    }
}
