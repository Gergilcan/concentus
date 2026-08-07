package com.concentus.secrets;

import com.concentus.auth.OrgContext;
import com.concentus.config.AgentSpec;
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

    private final CredentialStore credentials;
    private final OrgContext orgContext;

    public CredentialResolver(CredentialStore credentials, OrgContext orgContext) {
        this.credentials = credentials;
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
        try {
            return credentials.reveal(orgContext.defaultOrganizationId(), credentialId).orElse(null);
        } catch (RuntimeException e) {
            // Typically a master key that changed since the credential was saved. Null rather than
            // a throw, so a node with an unreadable credential behaves like one with none — the
            // caller already reports "no credential configured" in terms a user can act on.
            log.warn("Could not resolve credential {}: {}", credentialId, e.getMessage());
            return null;
        }
    }
}
