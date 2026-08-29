package com.concentus.license;

import java.nio.file.Path;
import java.time.Clock;

/**
 * The one enforcement point that runs before there is a Spring context to ask.
 *
 * <p>{@link com.concentus.store.EmbeddedPostgresConfig} decides whether to open an external
 * PostgreSQL inside a {@code @Bean} method — before {@link LicenseService} exists as a bean, and
 * arguably before it is safe to assume anything about the context is wired yet. Static and
 * Spring-free so that decision doesn't need one.
 *
 * <p>Deliberately thin: this builds a throwaway {@link LicenseService} off the same two sources
 * (the {@code CONCENTUS_LICENSE} environment variable, then {@code license.key} in the data
 * directory) and asks it the same question the real bean would answer later in the same process —
 * so the grace-period math lives in exactly one place, not two that could drift apart.
 */
public final class LicenseCheck {

    private static final String LICENSE_URL = "https://www.concentus-ai.com/#license";

    private LicenseCheck() { }

    /**
     * What an external-database configuration is allowed to mean under the installed license.
     *
     * <p>Three answers, not two, because a free license and no license are different situations:
     * <ul>
     *   <li><b>true</b> — an enterprise or team license (or its 14-day grace) covers it: use the
     *       external database.</li>
     *   <li><b>false</b> — a valid <em>individual</em> license is installed: the app should run,
     *       WITH the limitation — the caller falls back to the embedded database. Refusing to
     *       start over a free license would wall off the whole product from the people it is free
     *       for.</li>
     *   <li><b>throws</b> — no valid license at all, or a paid license past its grace: startup
     *       stops and the message names the fix. An expired team deliberately does NOT fall back —
     *       their data lives in the shared database, and opening an empty embedded one instead
     *       would look like the update ate everything.</li>
     * </ul>
     *
     * @param teamPublicKeySpki the {@code license.team-public-key} property's value — a {@code
     *                          @Bean} method can read it where a static helper cannot; blank means
     *                          the team tier is off here
     */
    public static boolean enterpriseCoversExternalDatabase(Path dataDir, String teamPublicKeySpki) {
        return enterpriseCoversExternalDatabase(dataDir,
                LicenseVerifier.forProduction(System.getenv(LicenseVerifier.ENV_TEST_KEYS), teamPublicKeySpki),
                System.getenv(LicenseService.ENV_VAR), Clock.systemUTC());
    }

    /**
     * Testable overload: an injectable verifier (fixture keys), env value and clock. The env value
     * is a parameter here rather than read again from {@code System.getenv} — a test that wants "no
     * env license" must be able to get that regardless of what is actually set in the process
     * running the test, which the two-arg entry point above is the only caller allowed to assume.
     */
    static boolean enterpriseCoversExternalDatabase(Path dataDir, LicenseVerifier verifier,
                                                    String envLicense, Clock clock) {
        LicenseService service = new LicenseService(verifier, dataDir, envLicense, clock);
        if (service.enterpriseActive()) return true;
        License license = service.current().orElse(null);
        if (license != null && License.TIER_INDIVIDUAL.equals(license.tier())) return false;
        // Both refusal messages carry "is an enterprise feature" on purpose: the desktop shell
        // recognizes that phrase in the log and shows the license wall instead of a stack trace.
        if (license != null) {
            throw new IllegalStateException(
                    "The shared database is an enterprise feature, and the " + license.tier()
                            + " license for \"" + license.licensee() + "\" expired on " + license.expires()
                            + " (14-day grace included). Renew at " + LICENSE_URL + ".");
        }
        throw new IllegalStateException(
                "The shared database is an enterprise feature. Install a license via the "
                        + LicenseService.ENV_VAR + " environment variable or "
                        + dataDir.resolve(LicenseService.FILE_NAME)
                        + ", or get one at " + LICENSE_URL + ".");
    }
}
