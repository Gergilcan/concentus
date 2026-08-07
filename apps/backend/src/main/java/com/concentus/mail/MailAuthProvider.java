package com.concentus.mail;

import com.concentus.secrets.CredentialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Produces the secret an IMAP connection authenticates with, whichever way the node is configured.
 *
 * <p>Two modes, and the reader does not need to know which is which — it receives a string and a
 * mechanism:
 *
 * <ul>
 *   <li><b>password</b> — the stored credential, used as-is. Gmail app passwords, self-hosted IMAP.</li>
 *   <li><b>Microsoft OAuth</b> — the stored credential is a <em>refresh token</em>, exchanged here
 *       for a short-lived access token. Required for Microsoft 365, where Basic authentication for
 *       IMAP is retired and a password is refused before it is evaluated.</li>
 * </ul>
 *
 * <p>Access tokens are cached until shortly before expiry. They last about an hour and the poller
 * runs every minute, so fetching one per poll would be sixty times the traffic for no benefit and
 * would eventually be throttled.
 *
 * <p>Rotated refresh tokens are written back to the credential store. Entra rotates them, and
 * keeping the old one works right up until it doesn't — at which point the mailbox quietly stops
 * being polled with nothing obvious to point at.
 */
@Component
public class MailAuthProvider {

    private static final Logger log = LoggerFactory.getLogger(MailAuthProvider.class);

    /** How a node authenticates. Stored on the Input node as {@code mailAuthMode}. */
    public static final String MODE_PASSWORD = "password";
    public static final String MODE_MICROSOFT = "microsoft-oauth";

    private final CredentialStore credentials;
    private final MicrosoftOAuth oauth;
    private final Map<String, MicrosoftOAuth.Tokens> cache = new ConcurrentHashMap<>();

    public MailAuthProvider(CredentialStore credentials, MicrosoftOAuth oauth) {
        this.credentials = credentials;
        this.oauth = oauth;
    }

    /**
     * What to send as the IMAP secret.
     *
     * @param secret    the password, or an OAuth access token
     * @param useXoauth2 whether the connection must use the XOAUTH2 mechanism
     */
    public record MailAuth(String secret, boolean useXoauth2) {
    }

    /**
     * @return null when the node has no usable credential, so the caller reports "not configured"
     *         rather than attempting a connection that cannot succeed
     */
    public MailAuth resolve(MailTriggerSpec spec, String organizationId) {
        String stored = credentials.reveal(organizationId, spec.credentialId()).orElse(null);
        if (stored == null) return null;

        if (!MODE_MICROSOFT.equalsIgnoreCase(spec.authMode())) {
            return new MailAuth(stored, false);
        }
        return new MailAuth(accessToken(spec, organizationId, stored), true);
    }

    /** A live access token, refreshed and re-cached when the current one is near expiry. */
    private String accessToken(MailTriggerSpec spec, String organizationId, String refreshToken) {
        String key = spec.credentialId();
        MicrosoftOAuth.Tokens cached = cache.get(key);
        if (cached != null && cached.isFresh()) return cached.accessToken();

        MicrosoftOAuth.Tokens fresh = oauth.refresh(spec.tenantId(), spec.clientId(), refreshToken);
        cache.put(key, fresh);

        if (!fresh.refreshToken().equals(refreshToken)) {
            // Rotated. Persist it now: losing a rotated refresh token is unrecoverable without a
            // fresh interactive sign-in.
            try {
                credentials.updateSecret(organizationId, key, fresh.refreshToken());
                log.debug("Stored a rotated Microsoft refresh token.");
            } catch (RuntimeException e) {
                log.warn("Could not store the rotated refresh token — the next restart may need a "
                        + "new sign-in: {}", e.getMessage());
            }
        }
        return fresh.accessToken();
    }

    /** Drops a cached access token, so the next resolve fetches a new one. */
    public void invalidate(String credentialId) {
        if (credentialId != null) cache.remove(credentialId);
    }
}
