package com.concentus.runners;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The registration token a runner presents: minted once, hashed for the table, recognised by
 * its prefix. The same shape as a service account's, with a prefix of its own so one in a log
 * or a compose file says what it is.
 */
public final class RunnerTokens {

    public static final String PREFIX = "crn_";
    static final int RANDOM_CHARS = 40;

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private RunnerTokens() {
    }

    /** A fresh token. Returned to the caller once; only {@link #hash} of it is ever kept. */
    public static String mint() {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < RANDOM_CHARS; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Whether {@code presented} even has the shape of one of ours, before any lookup is paid for. */
    public static boolean looksLike(String presented) {
        return presented != null && presented.startsWith(PREFIX)
                && presented.length() == PREFIX.length() + RANDOM_CHARS;
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
}
