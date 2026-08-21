package com.concentus.llm;

import com.concentus.secrets.CredentialStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A stored authorization this installation cannot decrypt.
 *
 * <p>Not hypothetical, and not rare: it is what every machine sees the first time it connects to a
 * database another installation filled. Credentials are sealed with a master key that stays on the
 * machine that saved them, so the rows are all present and none of them can be opened.
 *
 * <p>What made it worth a test is where the exception went. Reading a grant happens while a run's
 * workspace is being prepared, several frames below a thread pool nobody calls {@code get()} on —
 * so the throw killed the run and vanished. The flow reported COMPLETED with no output, no error
 * and nothing in the log; from the outside it simply never started, five times in a row, with no
 * way to find out why.
 */
class McpOAuthStoreUnreadableTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String URL = "https://mcp.resend.com/mcp";

    /** A credential store whose one grant refuses to decrypt, exactly as a wrong key makes it. */
    private static CredentialStore sealedWithAnotherKey() {
        CredentialStore credentials = mock(CredentialStore.class);
        CredentialStore.Credential credential = mock(CredentialStore.Credential.class);
        when(credential.id()).thenReturn("cred-1");
        when(credential.label()).thenReturn("mcp-oauth:" + URL);
        when(credentials.list("local")).thenReturn(List.of(credential));
        // Whatever the store throws — a decryption that failed, a database that went away
        // mid-read — the caller's answer must be the same: this server is not authorized.
        when(credentials.reveal("local", "cred-1")).thenThrow(
                new IllegalStateException("Could not read the stored credential."));
        return credentials;
    }

    @Test
    void an_unreadable_grant_does_not_escape_as_an_exception() {
        McpOAuthStore store = new McpOAuthStore(sealedWithAnotherKey(), mock(McpOAuth.class), MAPPER);

        assertThatCode(() -> store.load("local", URL)).doesNotThrowAnyException();
        assertThatCode(() -> store.accessToken("local", URL)).doesNotThrowAnyException();
    }

    // "Not authorized" is also the honest answer: a grant this installation cannot open is one it
    // does not have, and the caller already knows what to do with that — configure the server with
    // no header, and let the UI ask for a sign-in.
    @Test
    void it_reads_as_a_server_nobody_has_authorized_yet() {
        McpOAuthStore store = new McpOAuthStore(sealedWithAnotherKey(), mock(McpOAuth.class), MAPPER);

        assertThat(store.load("local", URL)).isEmpty();
        assertThat(store.accessToken("local", URL)).isEmpty();
    }
}
