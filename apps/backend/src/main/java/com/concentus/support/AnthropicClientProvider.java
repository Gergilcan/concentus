package com.concentus.support;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.concentus.model.AuthStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides how the backend authenticates and executes:
 *
 * <ul>
 *   <li><b>auto</b> (default): if {@code ANTHROPIC_API_KEY} (or {@code ANTHROPIC_AUTH_TOKEN})
 *       is set, run in the <b>cloud</b> via Managed Agents; otherwise run <b>local</b> via the
 *       {@code claude} CLI on your Claude subscription login.</li>
 *   <li><b>api-key</b>: force cloud; require {@code ANTHROPIC_API_KEY}.</li>
 *   <li><b>local</b>: force the local {@code claude} CLI (subscription).</li>
 * </ul>
 *
 * The API client (cloud only) is built lazily so the server starts without credentials.
 */
@Component
public class AnthropicClientProvider {

    private final String mode;
    private final LocalClaudeSupport localSupport;
    private volatile AnthropicClient cached;

    public AnthropicClientProvider(@Value("${anthropic.auth-mode:auto}") String mode,
                                   LocalClaudeSupport localSupport) {
        this.mode = (mode == null || mode.isBlank()) ? "auto" : mode.trim().toLowerCase();
        this.localSupport = localSupport;
    }

    /** "cloud" (API key), "local" (claude CLI subscription), or "none". */
    public String backend() {
        boolean key = hasKey() || hasToken();
        boolean local = localSupport.available();
        return switch (mode) {
            case "api-key" -> key ? "cloud" : "none";
            case "local" -> local ? "local" : "none";
            default -> key ? "cloud" : (local ? "local" : "none");
        };
    }

    /** Cloud API client (only valid when {@link #backend()} is "cloud"). */
    public synchronized AnthropicClient client() {
        if (cached == null) {
            cached = build();
        }
        return cached;
    }

    private AnthropicClient build() {
        if ("local".equals(mode)) {
            throw new IllegalStateException("auth-mode=local runs via the claude CLI, not the API client.");
        }
        String key = System.getenv("ANTHROPIC_API_KEY");
        if ("api-key".equals(mode)) {
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("anthropic.auth-mode=api-key but ANTHROPIC_API_KEY is not set.");
            }
            return AnthropicOkHttpClient.builder().apiKey(key).build();
        }
        return AnthropicOkHttpClient.fromEnv();
    }

    public AuthStatus status() {
        boolean hasKey = hasKey();
        String source;
        boolean ok;
        String detail;
        switch (backend()) {
            case "cloud" -> {
                ok = true;
                source = hasKey ? "api-key" : "auth-token";
                detail = hasKey ? "ANTHROPIC_API_KEY" : "ANTHROPIC_AUTH_TOKEN";
            }
            case "local" -> {
                ok = true;
                source = "local";
                detail = "Claude Code subscription";
            }
            default -> {
                ok = false;
                source = "none";
                detail = null;
            }
        }
        String hint = ok ? null
                : "Not signed in. Sign in to Claude Code (`claude`) to run on your subscription, "
                + "or set ANTHROPIC_API_KEY to use the cloud API.";
        return new AuthStatus(mode, source, ok, detail, hint);
    }

    private static boolean hasKey() {
        return notBlank(System.getenv("ANTHROPIC_API_KEY"));
    }

    private static boolean hasToken() {
        return notBlank(System.getenv("ANTHROPIC_AUTH_TOKEN"));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
