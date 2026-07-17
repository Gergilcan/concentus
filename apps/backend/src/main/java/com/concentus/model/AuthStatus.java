package com.concentus.model;

/**
 * Which credential the backend will use to call Anthropic.
 *
 * @param mode          configured auth mode: auto | api-key | oauth
 * @param source        effective source: api-key | auth-token | oauth | none
 * @param authenticated whether a usable credential was found
 * @param detail        human-readable detail (env var name, or OAuth type)
 * @param hint          guidance shown when not authenticated
 */
public record AuthStatus(String mode, String source, boolean authenticated,
                         String detail, String hint) {
}
