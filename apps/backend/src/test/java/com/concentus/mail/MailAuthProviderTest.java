package com.concentus.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.concentus.secrets.CredentialStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Turning a stored credential into whatever the IMAP connection has to send.
 *
 * <p>The cases that matter operationally are the ones where getting it wrong is silent: a rotated
 * refresh token that is not persisted works until a restart, and an access token fetched on every
 * poll works until the tenant throttles it.
 */
class MailAuthProviderTest {

    private CredentialStore credentials;
    private RecordingOAuth oauth;
    private MailAuthProvider provider;

    /** Counts refreshes and returns whatever token the test scripts, without any network. */
    private static class RecordingOAuth extends MicrosoftOAuth {
        final AtomicInteger refreshes = new AtomicInteger();
        Tokens next = new Tokens("access-1", "same-rt", Instant.now().plusSeconds(3600));

        RecordingOAuth() {
            super(new ObjectMapper(), MicrosoftOAuth.DEFAULT_AUTHORITY);
        }

        @Override
        public Tokens refresh(String tenantId, String clientId, String refreshToken) {
            refreshes.incrementAndGet();
            return next;
        }
    }

    private static MailTriggerSpec spec(String authMode) {
        return MailTriggerSpec.from(Map.of(
                "mailHost", "outlook.office365.com",
                "mailUsername", "buzon@empresa.com",
                "mailCredentialId", "cred-1",
                "mailAuthMode", authMode,
                "mailTenantId", "tenant-1",
                "mailClientId", "client-1"));
    }

    @BeforeEach
    void setUp() {
        credentials = mock(CredentialStore.class);
        oauth = new RecordingOAuth();
        provider = new MailAuthProvider(credentials, oauth);
    }

    @Test
    void aPasswordIsSentAsIsWithoutXoauth2() {
        when(credentials.reveal("org", "cred-1")).thenReturn(Optional.of("hunter2"));

        MailAuthProvider.MailAuth auth = provider.resolve(spec("password"), "org");

        assertThat(auth.secret()).isEqualTo("hunter2");
        assertThat(auth.useXoauth2()).isFalse();
        assertThat(oauth.refreshes).hasValue(0);
    }

    @Test
    void microsoftModeExchangesTheRefreshTokenForAnAccessToken() {
        when(credentials.reveal("org", "cred-1")).thenReturn(Optional.of("stored-rt"));

        MailAuthProvider.MailAuth auth = provider.resolve(spec("microsoft-oauth"), "org");

        // The stored credential is a refresh token; sending it over IMAP would fail.
        assertThat(auth.secret()).isEqualTo("access-1");
        assertThat(auth.useXoauth2()).isTrue();
    }

    @Test
    void aMissingCredentialResolvesToNullRatherThanAnAttempt() {
        when(credentials.reveal("org", "cred-1")).thenReturn(Optional.empty());

        assertThat(provider.resolve(spec("microsoft-oauth"), "org")).isNull();
        assertThat(oauth.refreshes).hasValue(0);
    }

    @Test
    void aLiveAccessTokenIsReusedAcrossPolls() {
        when(credentials.reveal("org", "cred-1")).thenReturn(Optional.of("stored-rt"));

        provider.resolve(spec("microsoft-oauth"), "org");
        provider.resolve(spec("microsoft-oauth"), "org");
        provider.resolve(spec("microsoft-oauth"), "org");

        // Tokens last an hour and the poller runs every minute — refreshing each time would be
        // sixty times the traffic for nothing, and eventually throttled.
        assertThat(oauth.refreshes).hasValue(1);
    }

    @Test
    void anExpiringTokenIsRefreshedBeforeItLapses() {
        when(credentials.reveal("org", "cred-1")).thenReturn(Optional.of("stored-rt"));
        // Inside the safety margin: valid now, but not for the length of a poll.
        oauth.next = new MicrosoftOAuth.Tokens("access-1", "same-rt", Instant.now().plusSeconds(60));

        provider.resolve(spec("microsoft-oauth"), "org");
        provider.resolve(spec("microsoft-oauth"), "org");

        assertThat(oauth.refreshes).hasValue(2);
    }

    @Test
    void aRotatedRefreshTokenIsWrittenBack() {
        when(credentials.reveal("org", "cred-1")).thenReturn(Optional.of("old-rt"));
        oauth.next = new MicrosoftOAuth.Tokens("access-1", "rotated-rt", Instant.now().plusSeconds(3600));

        provider.resolve(spec("microsoft-oauth"), "org");

        // Losing a rotated token is unrecoverable without a fresh interactive sign-in.
        verify(credentials).updateSecret("org", "cred-1", "rotated-rt");
    }

    @Test
    void anUnrotatedTokenIsNotRewritten() {
        when(credentials.reveal("org", "cred-1")).thenReturn(Optional.of("same-rt"));

        provider.resolve(spec("microsoft-oauth"), "org");

        verify(credentials, never()).updateSecret(any(), any(), any());
    }

    @Test
    void aFailedWriteBackStillYieldsAUsableToken() {
        when(credentials.reveal("org", "cred-1")).thenReturn(Optional.of("old-rt"));
        oauth.next = new MicrosoftOAuth.Tokens("access-1", "rotated-rt", Instant.now().plusSeconds(3600));
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(credentials).updateSecret(eq("org"), eq("cred-1"), any());

        // This poll can still run; only the next restart would need a new sign-in.
        assertThat(provider.resolve(spec("microsoft-oauth"), "org").secret()).isEqualTo("access-1");
    }

    @Test
    void invalidatingForcesTheNextResolveToRefresh() {
        when(credentials.reveal("org", "cred-1")).thenReturn(Optional.of("stored-rt"));

        provider.resolve(spec("microsoft-oauth"), "org");
        provider.invalidate("cred-1");
        provider.resolve(spec("microsoft-oauth"), "org");

        assertThat(oauth.refreshes).hasValue(2);
    }
}
