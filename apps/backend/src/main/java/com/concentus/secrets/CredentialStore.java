package com.concentus.secrets;

import com.concentus.support.Ids;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Credentials entered in the app, encrypted at rest and referenced by id.
 *
 * <h2>Why a table, and not a field on the node</h2>
 * A flow node would be the obvious place, and it is the wrong one. Every save snapshots the flow's
 * JSON into the version history, and duplicating a flow copies its nodes — so a credential stored
 * on a node, even encrypted, would fan out into every past revision and every copy. A node stores
 * an <b>id</b>; the secret lives here, once.
 *
 * <p>That also means deleting a credential really removes it, rather than leaving copies behind in
 * revisions nobody thinks to look at.
 *
 * <h2>What leaves this class</h2>
 * {@link Credential} carries no secret — it is what the API and the UI see. The plaintext is only
 * ever produced by {@link #reveal}, which exists for the code that actually opens a connection and
 * is deliberately not reachable from any controller.
 */
@Component
public class CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(CredentialStore.class);

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private volatile boolean available;

    public CredentialStore(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @PostConstruct
    void init() {
        // Created by the migrations; this only checks it arrived.
        try {
            jdbc.queryForObject("select count(*) from credentials", Integer.class);
            available = true;
            log.info("Credential store ready (PostgreSQL){}.",
                    cipher.isAvailable() ? "" : " — but CONCENTUS_SECRET_KEY is unset, so it is unusable");
        } catch (Exception e) {
            available = false;
            log.error("Credential store unavailable: {}", e.getMessage());
        }
    }

    /** Usable only when both the table exists and a master key is configured. */
    public boolean isAvailable() {
        return available && cipher.isAvailable();
    }

    /**
     * A credential as everything outside this class sees it: metadata only.
     *
     * @param hint a few trailing characters of the value, so a person can tell two apart without
     *             the value being recoverable from them
     */
    public record Credential(String id, String organizationId, String label, String kind,
                             String hint, long createdAt, long updatedAt, Long lastUsedAt) {
    }

    /** Kinds, so a picker can offer only the credentials that make sense for a field. */
    public static final class Kind {
        public static final String MAIL_PASSWORD = "mail-password";
        public static final String API_TOKEN = "api-token";

        private Kind() {
        }
    }

    public Credential create(String organizationId, String label, String kind, String plaintext) {
        requireUsable();
        if (label == null || label.isBlank()) throw new IllegalArgumentException("A label is required.");
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("A value is required.");
        }
        long now = System.currentTimeMillis();
        String id = Ids.generate("cred_", 10);
        try {
            jdbc.update("""
                    insert into credentials (id, organization_id, label, kind, secret, hint,
                      created_at, updated_at)
                    values (?,?,?,?,?,?,?,?)
                    """,
                    id, organizationId, label.trim(), kind, cipher.seal(plaintext),
                    hintFor(plaintext), now, now);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalStateException("A credential named '" + label.trim() + "' already exists.");
        }
        log.info("Stored a new credential '{}' ({}).", label.trim(), kind);
        return new Credential(id, organizationId, label.trim(), kind, hintFor(plaintext), now, now, null);
    }

    /**
     * Re-creates an exported credential as a placeholder, under its ORIGINAL id.
     *
     * <p>The export carries credential metadata only — a secret written to a file is a secret
     * leaked eventually — so an import has ids and labels but no values. Inserting placeholders
     * under the same ids is what keeps every flow's {@code credentialId} pointing at a credential
     * with a recognisable name; the person then re-enters each value once, through the same panel
     * as always. The placeholder secret is a random value no service will accept, never empty:
     * empty would break the "a credential always decrypts to something" invariant everywhere.
     *
     * <p>{@code ON CONFLICT DO NOTHING} on the id: if this machine already has that credential,
     * its REAL secret must win over a placeholder, silently.
     *
     * @return true when a placeholder was created; false when the id already existed
     */
    public boolean importPlaceholder(String organizationId, String id, String label, String kind) {
        requireUsable();
        if (id == null || id.isBlank() || label == null || label.isBlank()) {
            throw new IllegalArgumentException("A credential needs an id and a label.");
        }
        long now = System.currentTimeMillis();
        try {
            int inserted = jdbc.update("""
                    insert into credentials (id, organization_id, label, kind, secret, hint,
                      created_at, updated_at)
                    values (?,?,?,?,?,?,?,?)
                    on conflict (id) do nothing
                    """,
                    id, organizationId, label.trim(), kind,
                    cipher.seal("re-enter-after-import-" + java.util.UUID.randomUUID()),
                    "re-enter", now, now);
            if (inserted > 0) log.info("Imported credential '{}' as a placeholder — value must be re-entered.", label.trim());
            return inserted > 0;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Same label, different id: this machine already has its own credential by that name.
            throw new IllegalStateException("a credential named '" + label.trim()
                    + "' already exists here with a different id; flows from the import that "
                    + "reference the old id need re-pointing.");
        }
    }

    /**
     * Replaces a credential's value.
     *
     * <p>Only ever called with a real new value. "Save the form without touching the password
     * field" must not reach here — otherwise the masked placeholder gets stored as the secret,
     * which is the classic way this pattern breaks.
     */
    public void updateSecret(String organizationId, String id, String plaintext) {
        requireUsable();
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("A value is required.");
        }
        int updated = jdbc.update("""
                update credentials set secret = ?, hint = ?, updated_at = ?
                where id = ? and organization_id = ?
                """,
                cipher.seal(plaintext), hintFor(plaintext), System.currentTimeMillis(),
                id, organizationId);
        if (updated == 0) throw new IllegalArgumentException("No such credential.");
    }

    public void rename(String organizationId, String id, String label) {
        if (label == null || label.isBlank()) throw new IllegalArgumentException("A label is required.");
        try {
            jdbc.update("update credentials set label = ?, updated_at = ? where id = ? and organization_id = ?",
                    label.trim(), System.currentTimeMillis(), id, organizationId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalStateException("A credential named '" + label.trim() + "' already exists.");
        }
    }

    public List<Credential> list(String organizationId) {
        if (!available) return List.of();
        return jdbc.query("select * from credentials where organization_id = ? order by lower(label)",
                MAPPER, organizationId);
    }

    public Optional<Credential> find(String organizationId, String id) {
        if (!available || id == null || id.isBlank()) return Optional.empty();
        return jdbc.query("select * from credentials where id = ? and organization_id = ?",
                MAPPER, id, organizationId).stream().findFirst();
    }

    public boolean delete(String organizationId, String id) {
        return jdbc.update("delete from credentials where id = ? and organization_id = ?",
                id, organizationId) == 1;
    }

    /**
     * The decrypted value.
     *
     * <p>The only path to plaintext, and it is called by connection code, never by a controller —
     * there is no endpoint that returns a credential's value, by construction rather than by
     * remembering not to write one.
     *
     * @return empty when the credential does not exist in this organization
     */
    public Optional<String> reveal(String organizationId, String id) {
        if (!isAvailable() || id == null || id.isBlank()) return Optional.empty();
        List<String> sealed = jdbc.queryForList(
                "select secret from credentials where id = ? and organization_id = ?",
                String.class, id, organizationId);
        if (sealed.isEmpty()) return Optional.empty();
        String plaintext = cipher.open(sealed.get(0));
        // Recorded so an operator can see which credentials are actually in use, and spot one that
        // silently stopped being reached.
        jdbc.update("update credentials set last_used_at = ? where id = ?",
                System.currentTimeMillis(), id);
        return Optional.of(plaintext);
    }

    private void requireUsable() {
        if (!available) {
            throw new IllegalStateException("The credential store is unavailable; the database "
                    + "could not be reached.");
        }
        if (!cipher.isAvailable()) {
            throw new IllegalStateException("Credentials cannot be stored because "
                    + "CONCENTUS_SECRET_KEY is not set. Generate one with `openssl rand -base64 32` "
                    + "and restart the backend.");
        }
    }

    /**
     * A recognisable fragment: the last four characters, and only when the value is long enough
     * that revealing them leaves it unguessable.
     */
    static String hintFor(String plaintext) {
        if (plaintext == null || plaintext.length() < 12) return "••••";
        return "••••" + plaintext.substring(plaintext.length() - 4);
    }

    private static final RowMapper<Credential> MAPPER = (rs, i) -> new Credential(
            rs.getString("id"), rs.getString("organization_id"), rs.getString("label"),
            rs.getString("kind"), rs.getString("hint"), rs.getLong("created_at"),
            rs.getLong("updated_at"),
            rs.getObject("last_used_at") == null ? null : rs.getLong("last_used_at"));
}
