package com.concentus.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Which database the application stores its own data in.
 *
 * <p>Not to be confused with {@link DatabaseDef}, which is a database an <em>agent</em> reads from
 * as RAG context. This is where Concentus keeps runs, credentials, flow history and processed
 * mail — its own storage, not a data source.
 *
 * <p>Two modes:
 *
 * <ul>
 *   <li>{@code embedded} — the PostgreSQL that ships inside the app, started and stopped with it.
 *       The default, and what a single user on a laptop wants: nothing to install or administer.</li>
 *   <li>{@code external} — a PostgreSQL somewhere else. For a team that needs the data backed up,
 *       audited, or shared between installs, none of which a database living in one person's
 *       app-data folder can offer.</li>
 * </ul>
 *
 * @param mode     {@code embedded} or {@code external}
 * @param url      JDBC URL, external mode only
 * @param username external mode only
 * @param password <b>sealed</b>, never plaintext once it leaves the API — see
 *                 {@link com.concentus.secrets.SecretCipher}. Blank when the database needs none.
 */
// Unknown fields are ignored rather than rejected, so a file written by an older build still loads.
// The alternative is refusing to start over a field that has since been dropped, and the only place
// to fix this setting is inside the running application.
@JsonIgnoreProperties(ignoreUnknown = true)
public record StorageSettings(String mode, String url, String username, String password) {

    public static final String EMBEDDED = "embedded";
    public static final String EXTERNAL = "external";

    /** What an installation that has never been configured uses. */
    public static StorageSettings embedded() {
        return new StorageSettings(EMBEDDED, "", "", "");
    }

    /** Derived from {@link #mode}; annotated so it is not written to the file as a second field. */
    @JsonIgnore
    public boolean isExternal() {
        return EXTERNAL.equalsIgnoreCase(mode);
    }

    /**
     * Normalised, and treated as embedded unless external is both asked for and usable.
     *
     * <p>Falling back rather than failing is deliberate: an external mode with no URL is a
     * half-finished edit or a hand-corrupted file, and starting on the embedded database is
     * recoverable — the user can open the app and fix the setting. Refusing to start would leave
     * them with no way in, because the only place to correct it is inside the application.
     */
    public StorageSettings normalized() {
        if (!isExternal() || url == null || url.isBlank()) {
            return embedded();
        }
        return new StorageSettings(EXTERNAL, url.trim(),
                username == null ? "" : username.trim(),
                password == null ? "" : password);
    }
}
