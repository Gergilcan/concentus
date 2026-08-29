package com.concentus.license;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gate {@link com.concentus.store.EmbeddedPostgresConfig} calls before opening a connection to
 * an external database. Same fixtures and verifier as LicenseServiceTest — this only exercises the
 * static, Spring-free wrapper that a bean-less startup path can call.
 *
 * <p>Three-way contract: true = a paid license (enterprise or team) covers external; false = valid
 * individual license, the caller falls back to the embedded database (the app runs, WITH the
 * limitation); throws = no valid license, or a paid one past its grace.
 */
class LicenseCheckTest {

    private static Clock at(String date) {
        return Clock.fixed(Instant.parse(date + "T12:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void noLicense_refuses(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LicenseCheck.enterpriseCoversExternalDatabase(dir, TestLicenses.verifier(), "",
                        at("2026-08-22")));
        assertTrue(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("license"));
        // The desktop shell keys its license wall on this exact phrase; see main.ts.
        assertTrue(e.getMessage().contains("is an enterprise feature"));
    }

    @Test
    void enterpriseFixtureInFile_coversExternal(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(LicenseService.FILE_NAME), TestLicenses.token("enterprise-test.license"));
        assertTrue(LicenseCheck.enterpriseCoversExternalDatabase(dir, TestLicenses.verifier(), "",
                at("2026-08-22")));
    }

    @Test
    void teamFixtureInFile_coversExternalExactlyLikeEnterprise(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(LicenseService.FILE_NAME), TestLicenses.token("team-test.license"));
        assertTrue(LicenseCheck.enterpriseCoversExternalDatabase(dir, TestLicenses.verifier(), "",
                at("2026-08-22")));
    }

    @Test
    void individualLicense_fallsBackInsteadOfRefusing(@TempDir Path dir) throws Exception {
        // The free license must never wall the app off — external config quietly means embedded.
        Files.writeString(dir.resolve(LicenseService.FILE_NAME), TestLicenses.token("individual-test.license"));
        assertFalse(LicenseCheck.enterpriseCoversExternalDatabase(dir, TestLicenses.verifier(), "",
                at("2026-08-22")));
    }

    @Test
    void expiredBeyondGrace_refusesNamingRenewal(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(LicenseService.FILE_NAME),
                TestLicenses.token("enterprise-expired-test.license"));
        // expired 2020-06-01; 2020-06-16 is past expires+14 -> off. Deliberately NOT a fallback:
        // the team's data lives in the shared database, and an empty embedded one would read as
        // data loss. The wall asks for a renewal instead.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LicenseCheck.enterpriseCoversExternalDatabase(dir, TestLicenses.verifier(), "",
                        at("2020-06-16")));
        assertTrue(e.getMessage().contains("Renew"));
        assertTrue(e.getMessage().contains("is an enterprise feature"));
    }

    @Test
    void teamExpiredBeyondGrace_refusesTheSameWay_namingTheTeamLicense(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(LicenseService.FILE_NAME), TestLicenses.token("team-expired-test.license"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LicenseCheck.enterpriseCoversExternalDatabase(dir, TestLicenses.verifier(), "",
                        at("2020-06-16")));
        assertTrue(e.getMessage().contains("team license"), e.getMessage());
        assertTrue(e.getMessage().contains("Renew"));
        // Still the shell's phrase: the wall is the same wall whichever paid tier lapsed.
        assertTrue(e.getMessage().contains("is an enterprise feature"));
    }

    @Test
    void withinGrace_coversExternal(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(LicenseService.FILE_NAME),
                TestLicenses.token("enterprise-expired-test.license"));
        // expired 2020-06-01; 2020-06-10 is day 9 of 14 -> still active
        assertTrue(LicenseCheck.enterpriseCoversExternalDatabase(dir, TestLicenses.verifier(), "",
                at("2020-06-10")));
    }

    // A production process with CONCENTUS_LICENSE set proves nothing about the fixture-file cases
    // above unless the test itself controls the env value passed in — this is what the four-arg
    // overload existing at all is FOR. A bogus env token must lose to nothing at all, on its own
    // terms (an unverifiable env token refuses), never by accidentally reading the real environment
    // and being right for the wrong reason.
    @Test
    void anEnvLicenseParameter_isWhatIsChecked_notTheRealProcessEnvironment(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(LicenseService.FILE_NAME), TestLicenses.token("enterprise-test.license"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LicenseCheck.enterpriseCoversExternalDatabase(dir, TestLicenses.verifier(),
                        "bogus-not-a-real-token", at("2026-08-22")));
        assertTrue(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("license"));
    }

    // The same CONCENTUS_LICENSE_TEST_KEYS hook LicenseServiceTest exercises, through this class's
    // own testable overload: LicenseVerifier.forProduction(envTestKeys, teamKey) is what the
    // two-arg production entry point now passes as the verifier instead of
    // LicenseVerifier.production().
    @Test
    void verifierFromEnvTestKeys_acceptsAFixtureSignedLicense(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(LicenseService.FILE_NAME), TestLicenses.token("enterprise-test.license"));
        LicenseVerifier v = LicenseVerifier.forProduction(TestLicenses.testKeysPath().toString(), "");
        assertTrue(LicenseCheck.enterpriseCoversExternalDatabase(dir, v, "", at("2026-08-22")));
    }
}
