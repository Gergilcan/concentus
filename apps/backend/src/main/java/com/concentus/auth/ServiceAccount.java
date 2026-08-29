package com.concentus.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * A token for a machine: a name, a role, and the hash of a secret shown exactly once.
 *
 * <p>The row a CI job, a cron entry or another system acts as. It has everything a request needs
 * to be scoped — an organization, a role — and nothing a person has: no password, no sign-in, no
 * seat. {@code tokenHash} is {@link JsonIgnore}d so the record can leave a controller as-is; the
 * hash is not the secret, but a list of hashes is still nobody's business.
 *
 * <p>Plain data, no bean-style getters: a {@code isActive()} here would merge with a record
 * component in Jackson's eyes and this record IS what {@code GET /api/service-accounts} answers
 * with. Whether it is active is {@code revokedAt == null}, asked where it matters.
 *
 * @param role        VIEWER, OPERATOR or MEMBER — never ADMIN, see {@link #ROLE_CEILING}
 * @param createdBy   the email of the admin who minted it, for the audit line; null when unknown
 * @param lastUsedAt  epoch millis of the last request that presented the token, at minute
 *                    resolution; null until the first
 * @param revokedAt   epoch millis, null while the token still works
 */
public record ServiceAccount(String id, String organizationId, String name, String role,
                             @JsonIgnore String tokenHash, String createdBy, long createdAt,
                             Long lastUsedAt, Long revokedAt) {

    /**
     * The highest role a service account may hold. A token that could administer could mint more
     * tokens — one leak would become any number of them — so the ladder stops one rung short.
     */
    public static final String ROLE_CEILING = Accounts.ROLE_MEMBER;

    /** What every token starts with, so one in a log or a config file is recognisable for what it is. */
    public static final String TOKEN_PREFIX = "csa_";

    /** Random characters after the prefix. 40 of 62 symbols is 238 bits — nothing to guess. */
    static final int TOKEN_RANDOM_CHARS = 40;

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A fresh token. Returned to the caller once; only {@link #hash} of it is ever kept. */
    public static String mintToken() {
        StringBuilder sb = new StringBuilder(TOKEN_PREFIX);
        for (int i = 0; i < TOKEN_RANDOM_CHARS; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Whether {@code presented} even has the shape of one of ours, before any lookup is paid for. */
    public static boolean looksLikeToken(String presented) {
        return presented != null && presented.startsWith(TOKEN_PREFIX)
                && presented.length() == TOKEN_PREFIX.length() + TOKEN_RANDOM_CHARS;
    }

    /** Hex SHA-256 of the whole token — what the table holds and what a presented token is matched by. */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }

    /**
     * The role a token is granted for {@code stored}: itself, unless it sits above the ceiling.
     * Applied where the token is read as well as where the row is written, so a row promoted by
     * hand in the database still authenticates as a MEMBER at most.
     */
    public static String effectiveRole(String stored) {
        String role = Accounts.normalizeRole(stored);
        if (role == null) return Accounts.ROLE_VIEWER;
        return Accounts.atLeast(role, ROLE_CEILING) ? ROLE_CEILING : role;
    }

    /** The principal a request presenting this token runs as. */
    public ConcentusUserDetails principal() {
        // The "email" is what the run list credits a run to, so it names the account and says
        // what it is: "nightly-report (service account)" rather than an address nobody can reply to.
        return new ConcentusUserDetails(id, organizationId, name + " (service account)", null,
                effectiveRole(role), revokedAt == null);
    }
}
