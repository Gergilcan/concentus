package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.llm.McpOAuthFlow;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * Signs Concentus in to an OAuth-protected MCP server, so a self-hosted model can use its tools.
 *
 * <p>Needed because the {@code claude} CLI keeps its own MCP authorizations in its own credential
 * store: a server that works on the Claude backends returns <b>401</b> to anything else, which
 * reads as a broken flow rather than as a missing authorization. This gives the application its
 * own grant.
 *
 * <p>Thin by design: the flow itself — discovery, registration, the pending-PKCE map, the code
 * exchange — lives in {@link McpOAuthFlow}, where it can be driven and tested without a servlet
 * request. What stays here is HTTP: who may call, and what the browser tab shows.
 *
 * <p>{@code /callback} is the only endpoint reachable without a session — an OAuth redirect
 * arrives as a plain top-level navigation carrying no credentials of ours.
 */
@RestController
@RequestMapping("/api/mcp/oauth")
public class McpOAuthController {

    private final McpOAuthFlow flow;
    private final OrgContext orgContext;

    public McpOAuthController(McpOAuthFlow flow, OrgContext orgContext) {
        this.flow = flow;
        this.orgContext = orgContext;
    }

    public record StartRequest(String url, String scope) {
    }

    /**
     * Which organization owns an MCP grant.
     *
     * <p>The organization the admin is working in. This used to be the deployment's default, and
     * for a reason: a run happens on a background thread with no principal, and once resolved
     * grants against the configured organization — so a grant stored under whoever clicked the
     * button read "connected" on screen while every run got 401. Runs now carry their flow's
     * organization ({@code AgentRun.organizationId}) and present the grant stored under it, which
     * is exactly this one: the flow, the admin connecting its server, and the run all belong to the
     * same organization. Who may create one is still gated by {@code requireAdmin()}.
     */
    private String grantOwner() {
        return orgContext.currentOrganizationId();
    }

    /**
     * Where the browser reached the backend for THIS request — scheme, host and port as the
     * Host header carries them. The default callback base, because it is by definition an
     * address that browser can come back to; MCP_OAUTH_REDIRECT_BASE still overrides it.
     */
    private static String requestBase() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody StartRequest body) {
        orgContext.requireAdmin();
        McpOAuthFlow.StartResult result = flow.start(grantOwner(), body.url(), body.scope(), requestBase());
        Map<String, Object> out = new HashMap<>();
        out.put("ok", result.ok());
        out.put("redirectUri", result.redirectUri());
        if (result.ok()) out.put("authorizationUrl", result.authorizationUrl());
        else out.put("error", result.error());
        return out;
    }

    /**
     * Where the browser lands after the person approves.
     *
     * <p>Returns HTML rather than JSON: a human is looking at this tab, not a script. Errors are
     * shown here too — a redirect that lands on a blank page gives no way to tell "declined" from
     * "the exchange failed".
     */
    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam(required = false) String code,
                                           @RequestParam(required = false) String state,
                                           @RequestParam(required = false) String error,
                                           @RequestParam(name = "error_description", required = false)
                                           String errorDescription) {
        McpOAuthFlow.CallbackOutcome outcome = flow.finish(code, state, error, errorDescription);
        return page(outcome.title(), outcome.detail());
    }

    /** Whether a server is already authorized, for the button's label. */
    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String url) {
        orgContext.requireAdmin();
        return Map.of("connected", flow.connected(grantOwner(), url),
                "redirectUri", flow.redirectUri(requestBase()));
    }

    @PostMapping("/disconnect")
    public Map<String, Object> disconnect(@RequestBody StartRequest body) {
        orgContext.requireAdmin();
        flow.disconnect(grantOwner(), body.url());
        return Map.of("connected", false);
    }

    /** Minimal self-contained page — this tab has no access to the SPA's assets. */
    private static ResponseEntity<String> page(String title, String detail) {
        String html = """
                <!doctype html><meta charset="utf-8">
                <title>%s</title>
                <body style="font-family:system-ui;margin:3rem auto;max-width:32rem;color:#111">
                <h1 style="font-size:1.25rem">%s</h1>
                <p style="color:#555">%s</p>
                </body>""".formatted(escape(title), escape(title), escape(detail));
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    /** The detail can contain a server's error text, which is not ours to trust as markup. */
    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
