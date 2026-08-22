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

    /** Refuses to proceed unless an enterprise license (or its grace window) covers this install. */
    public static void requireEnterpriseForExternalDatabase(Path dataDir) {
        requireEnterpriseForExternalDatabase(dataDir, LicenseVerifier.production(),
                System.getenv(LicenseService.ENV_VAR), Clock.systemUTC());
    }

    /**
     * Testable overload: an injectable verifier (fixture keys), env value and clock. The env value
     * is a parameter here rather than read again from {@code System.getenv} — a test that wants "no
     * env license" must be able to get that regardless of what is actually set in the process
     * running the test, which the one-arg entry point above is the only caller allowed to assume.
     */
    static void requireEnterpriseForExternalDatabase(Path dataDir, LicenseVerifier verifier,
                                                      String envLicense, Clock clock) {
        LicenseService service = new LicenseService(verifier, dataDir, envLicense, clock);
        if (service.enterpriseActive()) return;
        throw new IllegalStateException(
                "The shared database is an enterprise feature. Install a license via the "
                        + LicenseService.ENV_VAR + " environment variable or "
                        + dataDir.resolve(LicenseService.FILE_NAME)
                        + ", or get one at " + LICENSE_URL + ".");
    }
}
