package com.concentus.license;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class LicenseServiceTest {

    // Fixture tokens and the fixture-key verifier, loaded exactly as in LicenseVerifierTest —
    // reuse via a small package-private TestLicenses helper class created alongside this test.

    private static Clock at(String date) {
        return Clock.fixed(Instant.parse(date + "T12:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void noLicense_statusSaysSoAndSeatLimitIsOne(@TempDir Path dir) throws Exception {
        LicenseService s = new LicenseService(TestLicenses.verifier(), dir, "", at("2026-08-22"));
        assertFalse(s.status().valid());
        assertEquals(1, s.seatLimit());
        assertFalse(s.enterpriseActive());
    }

    @Test
    void fileLicense_isReadAndReported(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("license.key"), TestLicenses.token("enterprise-test.license"));
        LicenseService s = new LicenseService(TestLicenses.verifier(), dir, "", at("2026-08-22"));
        assertTrue(s.enterpriseActive());
        assertEquals(5, s.seatLimit());
        assertEquals("Test Corp", s.status().licensee());
    }

    @Test
    void envBeatsFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("license.key"), TestLicenses.token("enterprise-test.license"));
        LicenseService s = new LicenseService(TestLicenses.verifier(), dir,
                TestLicenses.token("individual-test.license"), at("2026-08-22"));
        assertEquals(License.TIER_INDIVIDUAL, s.status().tier());
        assertEquals(1, s.seatLimit());
    }

    @Test
    void graceWindow_activeWithCountdown_thenOver(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("license.key"), TestLicenses.token("enterprise-expired-test.license"));
        // expired 2020-06-01; 2020-06-10 is day 9 of 14 -> still active, 5 days left
        LicenseService inGrace = new LicenseService(TestLicenses.verifier(), dir, "", at("2020-06-10"));
        assertTrue(inGrace.enterpriseActive());
        assertEquals(5, inGrace.status().graceDaysLeft());
        // 2020-06-16 is past expires+14 -> off
        LicenseService over = new LicenseService(TestLicenses.verifier(), dir, "", at("2020-06-16"));
        assertFalse(over.enterpriseActive());
        assertFalse(over.status().valid());
    }

    @Test
    void install_validatesWritesAndRereads(@TempDir Path dir) throws Exception {
        LicenseService s = new LicenseService(TestLicenses.verifier(), dir, "", at("2026-08-22"));
        s.install(TestLicenses.token("enterprise-test.license"));
        assertTrue(Files.exists(dir.resolve("license.key")));
        assertTrue(s.enterpriseActive());
        assertThrows(InvalidLicenseException.class, () -> s.install("garbage"));
    }

    @Test
    void install_refusesWhenEnvWouldShadowIt(@TempDir Path dir) throws Exception {
        LicenseService s = new LicenseService(TestLicenses.verifier(), dir,
                TestLicenses.token("individual-test.license"), at("2026-08-22"));
        InvalidLicenseException e = assertThrows(InvalidLicenseException.class,
                () -> s.install(TestLicenses.token("enterprise-test.license")));
        assertTrue(e.getMessage().contains("CONCENTUS_LICENSE"));
    }
}
