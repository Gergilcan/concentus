package com.concentus.secrets;

import com.concentus.auth.OrgContext;
import com.concentus.config.AgentSpec;
import com.concentus.llm.OAuthCredentialStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lets {@link AgentSpec} decrypt the credentials its nodes reference.
 *
 * <p>An {@code AgentSpec} is a plain Jackson POJO — deserialized from YAML by the CLI and built by
 * the flow compiler — so it cannot be injected with the credential store. This bean installs a
 * lookup into its static holder at startup, which is the same shape the env-var allowlist it
 * replaced used.
 *
 * <p>Resolution happens against the configured default organization. Flows and their resource
 * definitions are stored per deployment rather than per tenant, so their credentials are looked up
 * the same way; a run has no authenticated principal to take an organization from in any case.
 */
@Component
public class CredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(CredentialResolver.class);

    /**
     * Separates a credential id from the field of it a node wants: {@code <id>:<field>}.
     *
     * <p>Unambiguous because an id is {@code cred_} plus base-36 — a colon can only be the
     * separator. Only OAuth credentials have fields; everything else is one secret and has
     * nothing to select.
     */
    private static final char FIELD_SEPARATOR = ':';

    private final CredentialStore credentials;
    private final OAuthCredentialStore oauthCredentials;
    private final OrgContext orgContext;

    public CredentialResolver(CredentialStore credentials, OAuthCredentialStore oauthCredentials,
                              OrgContext orgContext) {
        this.credentials = credentials;
        this.oauthCredentials = oauthCredentials;
        this.orgContext = orgContext;
    }

    @PostConstruct
    void install() {
        AgentSpec.setCredentialLookup(this::resolve);
    }

    /**
     * @return the decrypted value, or null when the id names nothing in this deployment
     */
    public String resolve(String credentialId) {
        if (credentialId == null || credentialId.isBlank()) return null;
        String reference = credentialId.trim();
        int sep = reference.indexOf(FIELD_SEPARATOR);
        String id = sep < 0 ? reference : reference.substring(0, sep);
        String field = sep < 0 ? null : reference.substring(sep + 1);

        try {
            String organizationId = orgContext.defaultOrganizationId();
            if (oauthCredentials.isOAuth(organizationId, id)) {
                // Field by field, never the stored document: it holds the client secret and the
                // refresh token together, and a node that asked for a bearer must not receive them.
                return oauthCredentials.field(organizationId, id, field).orElse(null);
            }
            if (field != null) {
                // A field of something that has no fields. Null rather than the whole secret,
                // which is what a lenient reading here would hand over by accident.
                log.warn("Credential {} is not an OAuth authorization, so it has no '{}' field.",
                        id, field);
                return null;
            }
            return credentials.reveal(organizationId, id).orElse(null);
        } catch (RuntimeException e) {
            // Typically a master key that changed since the credential was saved. Null rather than
            // a throw, so a node with an unreadable credential behaves like one with none — the
            // caller already reports "no credential configured" in terms a user can act on.
            log.warn("Could not resolve credential {}: {}", credentialId, e.getMessage());
            return null;
        }
    }
}
