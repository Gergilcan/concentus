package com.concentus.web;

import com.concentus.auth.OrgContext;
import com.concentus.secrets.CredentialStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Credentials entered in the app: mailbox passwords, API tokens.
 *
 * <p><b>Write-only.</b> Values go in and never come back out — there is no endpoint that returns
 * one, not even to an administrator. That is a property of the API's shape rather than a rule
 * someone has to remember: {@link CredentialStore.Credential} has no field to put a secret in, so
 * a future handler cannot leak one by accident.
 *
 * <p>Everything is scoped to the caller's own organization, taken from the session rather than
 * from a parameter, so an id belonging to another tenant reads as "not found".
 */
@RestController
@RequestMapping("/api/credentials")
public class CredentialController {

    private final CredentialStore credentials;
    private final OrgContext orgContext;

    public CredentialController(CredentialStore credentials, OrgContext orgContext) {
        this.credentials = credentials;
        this.orgContext = orgContext;
    }

    /**
     * @param value the secret. Present only on the way in; nothing ever sends it back.
     */
    public record NewCredential(String label, String kind, String value) {
    }

    /**
     * @param value a replacement secret, or null/blank to leave it unchanged
     */
    public record UpdateCredential(String label, String value) {
    }

    /** Whether credentials can be stored at all, so the UI can explain why the form is disabled. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "available", credentials.isAvailable(),
                "hint", credentials.isAvailable() ? ""
                        : "Set CONCENTUS_SECRET_KEY (generate one with `openssl rand -base64 32`) "
                          + "and restart the backend.");
    }

    /** Metadata only: label, kind, a masked hint, and when it was last used. */
    @GetMapping
    public List<CredentialStore.Credential> list() {
        return credentials.list(orgContext.requireOrganizationId());
    }

    @PostMapping
    public CredentialStore.Credential create(@RequestBody NewCredential body) {
        orgContext.requireAdmin();
        if (body == null) throw new IllegalArgumentException("A credential is required.");
        return credentials.create(orgContext.requireOrganizationId(), body.label(),
                kindOrDefault(body.kind()), body.value());
    }

    /**
     * Renames a credential, and replaces its value only when a new one was actually typed.
     *
     * <p>A blank {@code value} means "unchanged", which is what makes the masked field in the UI
     * safe: saving the form without touching the password must not store the mask as the secret.
     */
    @PutMapping("/{id}")
    public CredentialStore.Credential update(@PathVariable String id,
                                             @RequestBody UpdateCredential body) {
        orgContext.requireAdmin();
        String organizationId = orgContext.requireOrganizationId();
        credentials.find(organizationId, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such credential."));

        if (body != null && body.label() != null && !body.label().isBlank()) {
            credentials.rename(organizationId, id, body.label());
        }
        if (body != null && body.value() != null && !body.value().isBlank()) {
            credentials.updateSecret(organizationId, id, body.value());
        }
        return credentials.find(organizationId, id).orElseThrow();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        orgContext.requireAdmin();
        if (!credentials.delete(orgContext.requireOrganizationId(), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such credential.");
        }
    }

    private static String kindOrDefault(String kind) {
        return kind == null || kind.isBlank() ? CredentialStore.Kind.API_TOKEN : kind;
    }
}
