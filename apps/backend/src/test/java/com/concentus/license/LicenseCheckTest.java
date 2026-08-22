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
 */
class LicenseCheckTest {

    private static Clock at(String date) {
        return Clock.fixed(Instant.parse(date + "T12:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void noLicense_refuses(@TempDir Path dir) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> LicenseCheck.requireEnterpriseForExternalDatabase(dir, TestLicenses.verifier(), "",
                        at("2026-08-22")));
        assertTrue(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("license"));
    }

    @Test
    void enterpriseFixtureInFile_passes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(LicenseService.FILE_NAME), TestLicenses.token("enterprise-test.license"));
        assertDoesNotThrow(() -> LicenseCheck.requireEnterpriseForExternalDatabase(dir, TestLicenses.verifier(), "",
                at("2026-08-22")));
    }

    @Test
    void expiredBeyondGrace_refuses(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(LicenseService.FILE_NAME),
                TestLicenses.token("enterprise-expired-test.license"));
        // expired 2020-06-01; 2020-06-16 is past expires+14 -> off
        assertThrows(IllegalStateException.class,
                () -> LicenseCheck.requireEnterpriseForExternalDatabase(dir, TestLicenses.verifier(), "",
                        at("2020-06-16")));
    }

    @Test
    void withinGrace_passes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(LicenseService.FILE_NAME),
                TestLicenses.token("enterprise-expired-test.license"));
        // expired 2020-06-01; 2020-06-10 is day 9 of 14 -> still active
        assertDoesNotThrow(() -> LicenseCheck.requireEnterpriseForExternalDatabase(dir, TestLicenses.verifier(), "",
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
                () -> LicenseCheck.requireEnterpriseForExternalDatabase(dir, TestLicenses.verifier(),
                        "bogus-not-a-real-token", at("2026-08-22")));
        assertTrue(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("license"));
    }
}
