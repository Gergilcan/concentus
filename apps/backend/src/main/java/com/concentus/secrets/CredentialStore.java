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
 * Credentials entered in the app, sealed at rest when this installation has a key, and referenced
 * by id.
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
 * <h2>Locked is a state, not a failure</h2>
 * A row sealed under a key this installation does not have — a reinstall that lost its keyring, a
 * second machine on a shared database — is still a credential: it keeps its id, its name and its
 * place in every flow, and it is listed with {@link Credential#locked()} set so the screen and the
 * flow doctor can say "enter it again". That is the difference from the version that first
 * encrypted, where the same row surfaced as "not configured" and a run that did nothing.
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
    private final RowMapper<Credential> mapper;
    private volatile boolean available;

    public CredentialStore(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        // Locked is decided per row, on the way out: a sealed value this installation cannot open.
        // Trying the key costs a few microseconds per credential and lets the list say which ones
        // need re-entering, rather than making somebody find out at run time.
        this.mapper = (rs, i) -> new Credential(
                rs.getString("id"), rs.getString("organization_id"), rs.getString("label"),
                rs.getString("kind"), rs.getString("hint"), rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getObject("last_used_at") == null ? null : rs.getLong("last_used_at"),
                cipher.open(rs.getString("secret")).locked());
    }

    @PostConstruct
    void init() {
        // Created by the migrations; this only checks it arrived.
        try {
            jdbc.queryForObject("select count(*) from credentials", Integer.class);
            available = true;
            log.info("Credential store ready (PostgreSQL). Values are {}.", cipher.hasKey()
                    ? "encrypted at rest"
                    : "stored in the clear: anyone who can read this database can read them");
        } catch (Exception e) {
            available = false;
            log.error("Credential store unavailable: {}", e.getMessage());
        }
    }

    /**
     * Usable as soon as the table is there.
     *
     * <p>A missing key does not make the store unavailable — it used to, and "the credential
     * store is unavailable" described the symptom while hiding the cause. Without a key the
     * store writes in the clear and says so through {@link #isEncrypting()}.
     */
    public boolean isAvailable() {
        return available;
    }

    /** Whether values written from here on are sealed, for the screen that should say so. */
    public boolean isEncrypting() {
        return cipher.hasKey();
    }

    /**
     * A credential as everything outside this class sees it: metadata only.
     *
     * @param hint   a few trailing characters of the value, so a person can tell two apart without
     *               the value being recoverable from them
     * @param locked true when the stored value is sealed under a key this installation does not
     *               have; the credential still exists, and entering its value again unlocks it
     */
    public record Credential(String id, String organizationId, String label, String kind,
                             String hint, long createdAt, long updatedAt, Long lastUsedAt,
                             boolean locked) {
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
                    id, organizationId, label.trim(), kind, cipher.wrap(plaintext),
                    hintFor(plaintext), now, now);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalStateException("A credential named '" + label.trim() + "' already exists.");
        }
        log.info("Stored a new credential '{}' ({}).", label.trim(), kind);
        return new Credential(id, organizationId, label.trim(), kind, hintFor(plaintext), now, now,
                null, false);
    }

    /**
     * Re-creates an exported credential as a placeholder, under its ORIGINAL id.
     *
     * <p>The plain export carries credential metadata only — a secret written to a file is a
     * secret leaked eventually — so an import has ids and labels but no values. Inserting
     * placeholders under the same ids is what keeps every flow's {@code credentialId} pointing at
     * a credential with a recognisable name; the person then re-enters each value once, through
     * the same panel as always. The placeholder secret is a random value no service will accept,
     * never empty: empty would break the "a credential always opens to something" invariant
     * everywhere.
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
                    cipher.wrap(PLACEHOLDER_PREFIX + java.util.UUID.randomUUID()),
                    PLACEHOLDER_HINT, now, now);
            if (inserted > 0) log.info("Imported credential '{}' as a placeholder — value must be re-entered.", label.trim());
            return inserted > 0;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // Same label, different id: this machine already has its own credential by that name.
            throw new IllegalStateException("a credential named '" + label.trim()
                    + "' already exists here with a different id; flows from the import that "
                    + "reference the old id need re-pointing.");
        }
    }

    /** What a placeholder's value starts with, so an import can tell one from a real secret. */
    private static final String PLACEHOLDER_PREFIX = "re-enter-after-import-";
    /** The hint a placeholder shows: not a fragment of anything, because there is nothing yet. */
    private static final String PLACEHOLDER_HINT = "re-enter";

    /** What {@link #importSecret} did with one credential. */
    public enum ImportOutcome {
        /** No credential had that id; it now exists with the file's value. */
        CREATED,
        /** The id existed but held a placeholder or a locked value; the file's value replaced it. */
        REPLACED,
        /** The id existed with a value this machine can open. That value won; the file's did not. */
        KEPT
    }

    /**
     * Restores an exported credential WITH its value, under its original id.
     *
     * <p>Only an export made with secrets asked for explicitly carries values, and the point of
     * one is recovery: a reinstall that lost its key, or a move to a machine that never had one.
     * So a value from the file replaces what is unusable here — a placeholder from an earlier
     * plain import, or a row locked under a key this installation does not have — and never a
     * value this machine can already open, which is the same rule the placeholder import
     * follows: the real secret already here wins.
     */
    public ImportOutcome importSecret(String organizationId, String id, String label, String kind,
                                      String plaintext) {
        requireUsable();
        if (id == null || id.isBlank() || label == null || label.isBlank()) {
            throw new IllegalArgumentException("A credential needs an id and a label.");
        }
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("A credential needs a value.");
        }
        long now = System.currentTimeMillis();
        try {
            int inserted = jdbc.update("""
                    insert into credentials (id, organization_id, label, kind, secret, hint,
                      created_at, updated_at)
                    values (?,?,?,?,?,?,?,?)
                    on conflict (id) do nothing
                    """,
                    id, organizationId, label.trim(), kind, cipher.wrap(plaintext),
                    hintFor(plaintext), now, now);
            if (inserted > 0) {
                log.info("Imported credential '{}' with its value.", label.trim());
                return ImportOutcome.CREATED;
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalStateException("a credential named '" + label.trim()
                    + "' already exists here with a different id; flows from the import that "
                    + "reference the old id need re-pointing.");
        }

        List<String> stored = jdbc.queryForList(
                "select secret from credentials where id = ? and organization_id = ?",
                String.class, id, organizationId);
        if (stored.isEmpty()) return ImportOutcome.KEPT;
        SecretCipher.Reading existing = cipher.open(stored.get(0));
        boolean placeholder = !existing.locked() && existing.plaintext() != null
                && existing.plaintext().startsWith(PLACEHOLDER_PREFIX);
        if (!existing.locked() && !placeholder) return ImportOutcome.KEPT;

        jdbc.update("""
                update credentials set secret = ?, hint = ?, updated_at = ?
                where id = ? and organization_id = ?
                """,
                cipher.wrap(plaintext), hintFor(plaintext), now, id, organizationId);
        log.info("Imported credential '{}' over the {} value that was here.", label.trim(),
                existing.locked() ? "locked" : "placeholder");
        return ImportOutcome.REPLACED;
    }

    /**
     * Replaces a credential's value.
     *
     * <p>Only ever called with a real new value. "Save the form without touching the password
     * field" must not reach here — otherwise the masked placeholder gets stored as the secret,
     * which is the classic way this pattern breaks.
     *
     * <p>Also the way out of {@link Credential#locked()}: a new value is sealed under this
     * installation's key, whatever the old row was sealed with.
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
                cipher.wrap(plaintext), hintFor(plaintext), System.currentTimeMillis(),
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
        // A save is a moment this row is being written anyway; a value still in the clear from
        // before this installation had a key goes sealed with it.
        sealIfClear(organizationId, id);
    }

    public List<Credential> list(String organizationId) {
        if (!available) return List.of();
        return jdbc.query("select * from credentials where organization_id = ? order by lower(label)",
                mapper, organizationId);
    }

    public Optional<Credential> find(String organizationId, String id) {
        if (!available || id == null || id.isBlank()) return Optional.empty();
        return jdbc.query("select * from credentials where id = ? and organization_id = ?",
                mapper, id, organizationId).stream().findFirst();
    }

    public boolean delete(String organizationId, String id) {
        return jdbc.update("delete from credentials where id = ? and organization_id = ?",
                id, organizationId) == 1;
    }

    /**
     * The usable value.
     *
     * <p>The only path to plaintext, and it is called by connection code, never by a controller —
     * there is no endpoint that returns a credential's value, by construction rather than by
     * remembering not to write one.
     *
     * <p>A row still in the clear is sealed here when a key has since appeared: the value is in
     * hand, so this is the cheapest moment to do it, and it means an upgrade that brought a key
     * finishes its own conversion one credential at a time even if the startup pass was skipped.
     *
     * @return empty when the credential does not exist in this organization, or is locked — a
     *         locked one is logged by name, because "not configured" was the failure that cost a
     *         morning the last time encryption lived here
     */
    public Optional<String> reveal(String organizationId, String id) {
        if (!isAvailable() || id == null || id.isBlank()) return Optional.empty();
        List<String[]> rows = jdbc.query(
                "select label, secret from credentials where id = ? and organization_id = ?",
                (rs, n) -> new String[] { rs.getString("label"), rs.getString("secret") },
                id, organizationId);
        if (rows.isEmpty()) return Optional.empty();
        String label = rows.get(0)[0];
        String stored = rows.get(0)[1];
        SecretCipher.Reading reading = cipher.open(stored);
        if (reading.locked()) {
            log.warn("Credential '{}' ({}) is locked: it was encrypted with a key this installation "
                    + "does not have. Enter its value again under Resources → Credentials.", label, id);
            return Optional.empty();
        }
        if (reading.state() == SecretCipher.Reading.State.CLEAR && cipher.hasKey()) {
            sealInPlace(id, stored, reading.plaintext());
        }
        // Recorded so an operator can see which credentials are actually in use, and spot one that
        // silently stopped being reached.
        jdbc.update("update credentials set last_used_at = ? where id = ?",
                System.currentTimeMillis(), id);
        return Optional.of(reading.plaintext());
    }

    /**
     * The value for an export that was explicitly asked to carry secrets.
     *
     * <p>Separate from {@link #reveal} because an export is not a use: it must not move
     * {@code last_used_at}, which is what tells an operator whether a credential is still reached
     * by anything. Empty when locked — the export marks those, and the import re-creates them as
     * placeholders to re-enter.
     */
    public Optional<String> revealForExport(String organizationId, String id) {
        if (!isAvailable() || id == null || id.isBlank()) return Optional.empty();
        List<String> stored = jdbc.queryForList(
                "select secret from credentials where id = ? and organization_id = ?",
                String.class, id, organizationId);
        if (stored.isEmpty()) return Optional.empty();
        return Optional.ofNullable(cipher.open(stored.get(0)).orNull());
    }

    private void sealIfClear(String organizationId, String id) {
        if (!cipher.hasKey()) return;
        List<String> stored = jdbc.queryForList(
                "select secret from credentials where id = ? and organization_id = ?",
                String.class, id, organizationId);
        if (stored.isEmpty() || SecretCipher.isSealed(stored.get(0))) return;
        sealInPlace(id, stored.get(0), stored.get(0));
    }

    /**
     * Rewrites one clear row sealed. Compare-and-set on the stored text, so a concurrent
     * {@link #updateSecret} — which already writes sealed — is never overwritten with a stale value.
     */
    private void sealInPlace(String id, String storedClear, String plaintext) {
        int sealed = jdbc.update("update credentials set secret = ? where id = ? and secret = ?",
                cipher.wrap(plaintext), id, storedClear);
        if (sealed > 0) log.info("Credential {} was stored in the clear and is now encrypted.", id);
    }

    private void requireUsable() {
        if (!available) {
            throw new IllegalStateException("The credential store is unavailable; the database "
                    + "could not be reached.");
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
}
