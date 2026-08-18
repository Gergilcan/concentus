package com.concentus.service;

import com.concentus.config.AgentSpec.McpServerSpec;
import com.concentus.llm.McpClient;
import com.concentus.llm.McpOAuthStore;

/**
 * How a server's bearer token is obtained, in the one place every caller shares.
 *
 * <p>Two sources, and they behave differently when the server says no. A token typed into
 * Credentials is as good as it will ever be: if it is refused, refusing again is the honest
 * answer, and a person has to paste a new one. An OAuth grant is the opposite — the refusal is
 * routine, the store holds a refresh token for exactly this, and the renewal is invisible.
 *
 * <p>This exists as a shared factory because the three call sites (the worker facade, the
 * local-model executor, the designer's tool picker) each used to resolve a token into a string
 * and hand it over. A string cannot renew itself, which is why a run that outlived its fifteen
 * minutes of Resend access failed in three different places for one reason.
 */
public final class McpAuthSources {

    private McpAuthSources() {
    }

    /**
     * The grant first, the node's stored credential second.
     *
     * <p>That order is not arbitrary: a grant exists only because someone deliberately signed this
     * server in, and the store vouches for it. Letting a leftover credential win instead is how an
     * OAuth-only server answered every run with a 401 while the UI said "Authorized".
     */
    public static McpClient.TokenSource forServer(McpServerSpec server, McpOAuthStore oauth,
                                                  String organizationId) {
        String granted = oauth.accessToken(organizationId, server.url).orElse(null);
        if (granted == null || granted.isBlank()) {
            return McpClient.TokenSource.fixed(server.resolveToken());
        }
        return new McpClient.TokenSource() {
            @Override
            public String current() {
                return oauth.accessToken(organizationId, server.url).orElse(null);
            }

            @Override
            public String renew() {
                return oauth.renewedAccessToken(organizationId, server.url).orElse(null);
            }
        };
    }
}
