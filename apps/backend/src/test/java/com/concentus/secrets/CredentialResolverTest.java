package com.concentus.secrets;

import com.concentus.auth.OrgContext;
import com.concentus.llm.OAuthCredentialStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a node's {@code credential:…} reference resolves to.
 *
 * <p>The suffix exists because one OAuth authorization answers several environment variables at
 * once — the Google Ads server wants the client id, the client secret and the refresh token, and
 * they all come from the same sign-in. Writing them as three credentials would mean three sign-ins
 * that must somehow stay in step.
 */
class CredentialResolverTest {

    private static final String ORG = "local";

    private final CredentialStore credentials = mock(CredentialStore.class);
    private final OAuthCredentialStore oauth = mock(OAuthCredentialStore.class);

    private CredentialResolver resolver() {
        OrgContext org = mock(OrgContext.class);
        when(org.currentOrganizationId()).thenReturn(ORG);
        return new CredentialResolver(credentials, oauth, org);
    }

    @Test
    void a_pasted_secret_still_resolves_to_itself() {
        when(oauth.isOAuth(ORG, "cred_token")).thenReturn(false);
        when(credentials.reveal(ORG, "cred_token")).thenReturn(Optional.of("ghp_abc"));

        assertThat(resolver().resolve("cred_token")).isEqualTo("ghp_abc");
    }

    @Test
    void a_suffix_picks_one_field_of_an_authorization() {
        when(oauth.isOAuth(ORG, "cred_oauth")).thenReturn(true);
        when(oauth.field(ORG, "cred_oauth", "refresh_token")).thenReturn(Optional.of("refresh-1"));

        assertThat(resolver().resolve("cred_oauth:refresh_token")).isEqualTo("refresh-1");
    }

    @Test
    void an_authorization_with_no_suffix_gives_the_access_token_not_the_document() {
        // The stored document holds the client secret and the refresh token together. Reaching it
        // by forgetting a suffix would hand every one of them to a process that asked for a token.
        when(oauth.isOAuth(ORG, "cred_oauth")).thenReturn(true);
        when(oauth.field(ORG, "cred_oauth", null)).thenReturn(Optional.of("access-1"));

        assertThat(resolver().resolve("cred_oauth")).isEqualTo("access-1");
        verify(credentials, never()).reveal(anyString(), anyString());
    }

    @Test
    void a_suffix_on_a_pasted_secret_resolves_to_nothing_rather_than_the_secret() {
        // Asking for one field of something that has no fields is a mistake worth surfacing as an
        // empty value, not by quietly handing over the whole token.
        when(oauth.isOAuth(ORG, "cred_token")).thenReturn(false);

        assertThat(resolver().resolve("cred_token:client_id")).isNull();
        verify(credentials, never()).reveal(anyString(), anyString());
    }

    @Test
    void an_id_that_names_nothing_resolves_to_nothing() {
        when(oauth.isOAuth(ORG, "cred_gone")).thenReturn(false);
        when(credentials.reveal(ORG, "cred_gone")).thenReturn(Optional.empty());

        assertThat(resolver().resolve("cred_gone")).isNull();
        assertThat(resolver().resolve(null)).isNull();
        assertThat(resolver().resolve("  ")).isNull();
    }

    @Test
    void a_credential_that_cannot_be_decrypted_behaves_like_one_that_is_not_configured() {
        // Typically a master key that changed since the credential was saved.
        when(oauth.isOAuth(ORG, "cred_broken")).thenReturn(false);
        when(credentials.reveal(any(), any())).thenThrow(new IllegalStateException("bad key"));

        assertThat(resolver().resolve("cred_broken")).isNull();
    }
}
