package com.concentus.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The two things every browser-driven OAuth sign-in in this package needs and neither should own:
 * the address the browser reached the backend through, and the page it lands on afterwards.
 *
 * <p>Shared by {@link McpOAuthController} and {@link OAuthCredentialController}, which otherwise
 * carried identical copies — and a callback page that drifts between two sign-ins would be the
 * kind of difference somebody notices only when one of them stops escaping a provider's error.
 */
public final class OAuthCallbacks {

    private OAuthCallbacks() {
    }

    /**
     * The address this browser reached the backend through — one it can, by definition, reach
     * again. Public because the runners screen answers the same question with the same reasoning:
     * a runner is told to dial the address the person registering it is looking at.
     */
    public static String requestBase() {
        return ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
    }

    /** Minimal self-contained page — this tab has no access to the SPA's assets. */
    static ResponseEntity<String> page(String title, String detail) {
        String html = """
                <!doctype html><meta charset="utf-8">
                <title>%s</title>
                <body style="font-family:system-ui;margin:3rem auto;max-width:32rem;color:#111">
                <h1 style="font-size:1.25rem">%s</h1>
                <p style="color:#555">%s</p>
                </body>""".formatted(escape(title), escape(title), escape(detail));
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    /** The detail can carry a provider's error text, which is not ours to trust as markup. */
    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
