package com.concentus.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an agent is told when one of its tool servers refuses the connection.
 *
 * <p>This exists because of a real failure: a flow asked a self-hosted model for data in Holded,
 * the Holded MCP server returned 401, and the model — left with no tools and no explanation —
 * answered from memory that "holded" was not a recognised term. A confident invented answer is a
 * far worse outcome than an error, so the text below is load-bearing.
 */
class McpFailureMessageTest {

    /** Package-private static on the executor; reached reflectively to keep it off the API. */
    private static String describe(String server, String message) throws Exception {
        return describe(server, message, true);
    }

    private static String describe(String server, String message, boolean hadCredential) throws Exception {
        Method m = LocalModelAgentExecutor.class.getDeclaredMethod(
                "describeMcpFailure", String.class, String.class, boolean.class);
        m.setAccessible(true);
        return (String) m.invoke(null, server, message, hadCredential);
    }

    @Test
    void a401WithNothingToSendSaysToSignIn() throws Exception {
        // The case that actually happens first: no token on the node and no grant stored.
        String text = describe("Holded", "Holded returned 401", false);

        assertThat(text).contains("Sign in to this server").contains("Holded");
    }

    @Test
    void a401AfterSendingACredentialSaysToReAuthorizeInstead() throws Exception {
        // Telling someone to sign in when they already have is how an afternoon disappears.
        String text = describe("Holded", "Holded returned 401", true);

        assertThat(text).contains("refused it").contains("Re-authorize");
        assertThat(text).doesNotContain("Sign in to this server");
    }

    @Test
    void theWordUnauthorizedAloneIsEnoughToTriggerTheExplanation() throws Exception {
        assertThat(describe("Holded", "unauthorized", false)).contains("Sign in to this server");
    }

    @Test
    void anOrdinaryFailureIsReportedPlainlyWithoutTheOAuthAdvice() throws Exception {
        String text = describe("Linear", "Connection refused");

        assertThat(text).contains("Linear").contains("Connection refused");
        assertThat(text).doesNotContain("OAuth");
    }

    @Test
    void aNullMessageDoesNotProduceTheWordNull() throws Exception {
        assertThat(describe("Linear", null)).doesNotContain("null");
    }
}
