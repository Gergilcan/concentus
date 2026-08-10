package com.concentus.model;

/**
 * Which credential the backend will use to call Anthropic.
 *
 * @param mode          configured auth mode: auto | api-key | oauth
 * @param source        effective source: api-key | auth-token | oauth | none
 * @param authenticated whether a usable credential was found
 * @param detail        human-readable detail (env var name, or OAuth type)
 * @param hint          guidance shown when not authenticated
 * @param appVersion    the installed release's version, for the header chip; null when nothing
 *                      launched by the shell is running (dev, hand-run backend) — riding on this
 *                      status because it is the one endpoint the header already polls
 */
public record AuthStatus(String mode, String source, boolean authenticated,
                         String detail, String hint, String appVersion) {

    /** The pre-version shape, kept so the provider and tests stay untouched. */
    public AuthStatus(String mode, String source, boolean authenticated,
                      String detail, String hint) {
        this(mode, source, authenticated, detail, hint, null);
    }

    /** A copy carrying the installed version, or this instance when there is none to carry. */
    public AuthStatus withAppVersion(String version) {
        if (version == null || version.isBlank()) return this;
        return new AuthStatus(mode, source, authenticated, detail, hint, version);
    }
}
