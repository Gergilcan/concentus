package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.mail.MicrosoftOAuth;
import com.concentus.secrets.CredentialStore;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signs a Microsoft 365 mailbox in, using the OAuth2 device code flow.
 *
 * <p>Needed because Microsoft retired Basic authentication for IMAP: a mailbox password is refused
 * before it is evaluated, which is why a correct one returns {@code AUTHENTICATE failed}. A mail
 * app on a phone is not doing anything privileged — it runs an OAuth sign-in and keeps a refresh
 * token. This is the same thing, arranged so it can be completed from any device.
 *
 * <p>Two steps, because the flow is asynchronous by design: {@code /start} returns a short code
 * for a person to enter at Microsoft's page, and {@code /complete} is polled until they have.
 * Nothing here needs a redirect URI or a browser on the server, which is what makes it work from a
 * container.
 *
 * <p>What ends up stored is the <b>refresh token</b>, encrypted like any other credential. The app
 * registration is a public client and has no secret, so that is the only long-lived material.
 */
@RestController
@RequestMapping("/api/mail/oauth/microsoft")
public class MailOAuthController {

    private final MicrosoftOAuth oauth;
    private final CredentialStore credentials;
    private final OrgContext orgContext;

    public MailOAuthController(MicrosoftOAuth oauth, CredentialStore credentials, OrgContext orgContext) {
        this.oauth = oauth;
        this.credentials = credentials;
        this.orgContext = orgContext;
    }

    public record StartRequest(String tenantId, String clientId) {
    }

    /**
     * @param label the name the resulting credential is stored under, so it can be picked on the
     *              node like any other
     */
    public record CompleteRequest(String tenantId, String clientId, String deviceCode, String label,
                                  String credentialId) {
    }

    /**
     * The app registration this deployment uses, so the node does not have to ask for it.
     *
     * <p>One registration serves every mailbox — it identifies the application, not the mailbox —
     * so asking for the same two ids on each node is asking the same question over and over.
     * Neither is a secret: a public-client id is public by design and the tenant id appears in
     * every sign-in URL.
     */
    @org.springframework.web.bind.annotation.GetMapping("/defaults")
    public Map<String, Object> defaults() {
        orgContext.requireAdmin();
        return Map.of("tenantId", oauth.defaultTenantId(),
                "clientId", oauth.defaultClientId(),
                "configured", oauth.hasDefaults());
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody StartRequest body) {
        orgContext.requireAdmin();
        MicrosoftOAuth.DeviceCode code = oauth.startDeviceCode(body.tenantId(), body.clientId());
        Map<String, Object> out = new LinkedHashMap<>();
        // The device code is returned so the browser can poll with it. It is not a credential:
        // on its own it grants nothing, and it expires in minutes.
        out.put("deviceCode", code.deviceCode());
        out.put("userCode", code.userCode());
        out.put("verificationUri", code.verificationUri());
        out.put("message", code.message());
        out.put("expiresIn", code.expiresIn());
        out.put("interval", code.interval());
        return out;
    }

    /**
     * Checks whether the sign-in finished, and on success stores the refresh token.
     *
     * <p>{@code pending: true} is the normal answer while someone is still typing the code — the
     * caller keeps polling rather than treating it as a failure.
     */
    @PostMapping("/complete")
    public Map<String, Object> complete(@RequestBody CompleteRequest body) {
        orgContext.requireAdmin();
        String organizationId = orgContext.requireOrganizationId();

        var tokens = oauth.pollDeviceCode(body.tenantId(), body.clientId(), body.deviceCode());
        if (tokens.isEmpty()) return Map.of("pending", true);

        String refreshToken = tokens.get().refreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            // Without offline_access Entra returns an access token and nothing to renew it with,
            // which would work for an hour and then stop — worth catching here rather than in a
            // poll failure tomorrow.
            return Map.of("pending", false, "ok", false,
                    "error", "Microsoft returned no refresh token. Add the `offline_access` "
                            + "delegated permission to the app registration and sign in again.");
        }

        String label = body.label() == null || body.label().isBlank()
                ? "Microsoft 365 mailbox" : body.label().trim();
        CredentialStore.Credential stored;
        if (body.credentialId() != null && !body.credentialId().isBlank()) {
            credentials.updateSecret(organizationId, body.credentialId(), refreshToken);
            stored = credentials.find(organizationId, body.credentialId()).orElseThrow();
        } else {
            stored = credentials.create(organizationId, label,
                    CredentialStore.Kind.MAIL_PASSWORD, refreshToken);
        }
        return Map.of("pending", false, "ok", true, "credentialId", stored.id(),
                "label", stored.label());
    }
}
