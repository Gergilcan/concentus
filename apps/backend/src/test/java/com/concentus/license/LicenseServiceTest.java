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

    // A hand-minted (or otherwise malformed) enterprise license with no seat count at all — not
    // one mint-license.mjs would ever produce, since it requires --seats, but the parser accepts
    // it (seats is nullable on the record) and seatLimit() used to hand that null straight back,
    // NPEing every caller that unboxes it into an int. It must clamp to one instead: a license
    // that names no seats grants none extra.
    @Test
    void enterpriseLicenseWithoutSeats_clampsToOne(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("license.key"), TestLicenses.token("enterprise-no-seats-test.license"));
        LicenseService s = new LicenseService(TestLicenses.verifier(), dir, "", at("2026-08-22"));
        assertTrue(s.enterpriseActive());
        assertEquals(1, s.seatLimit());
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

    // CONCENTUS_LICENSE_TEST_KEYS hook: LicenseVerifier.forProduction(envTestKeys) is what a
    // production entry point (the Spring constructor here, LicenseCheck's one-arg entry) builds its
    // verifier from instead of LicenseVerifier.production(). Passing that same function's result in
    // here — the way the Spring constructor does — is what proves the hook actually reaches
    // LicenseService, not just that forProduction itself works (LicenseVerifierTest covers that).

    @Test
    void envTestKeys_pointingAtTheFixture_aFixtureSignedLicensePasses(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("license.key"), TestLicenses.token("enterprise-test.license"));
        LicenseVerifier v = LicenseVerifier.forProduction(TestLicenses.testKeysPath().toString());
        LicenseService s = new LicenseService(v, dir, "", at("2026-08-22"));
        assertTrue(s.enterpriseActive());
        assertEquals("Test Corp", s.status().licensee());
        assertEquals(5, s.seatLimit());
    }

    @Test
    void withoutEnvTestKeys_productionKeysAreUsed_aFixtureSignedLicenseIsRejected(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("license.key"), TestLicenses.token("enterprise-test.license"));
        LicenseService s = new LicenseService(LicenseVerifier.forProduction(""), dir, "", at("2026-08-22"));
        assertFalse(s.status().valid());
        assertEquals(1, s.seatLimit());
    }
}
