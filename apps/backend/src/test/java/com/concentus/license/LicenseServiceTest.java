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

    // The team tier through the service: the same answers an enterprise license gets — the gates
    // (AccountController, OidcRegistry, LicenseCheck) all ask enterpriseActive()/seatLimit() and
    // nothing else, so proving those two here is proving every gate.

    @Test
    void enterpriseFeatures_areEnterpriseOnly_andTheRefusalNamesTheTier(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("license.key"), TestLicenses.token("team-test.license"));
        LicenseService team = new LicenseService(TestLicenses.verifier(), dir, "", at("2026-08-22"));
        assertTrue(team.teamTier());
        assertFalse(team.enterpriseTier());
        assertFalse(team.allows(Feature.ORG_POLICIES));
        assertTrue(team.refusal(Feature.ORG_POLICIES).contains("Organization policies is an Enterprise feature"));
        assertTrue(team.refusal(Feature.ORG_POLICIES).contains("upgrade"));

        Files.writeString(dir.resolve("license.key"), TestLicenses.token("enterprise-test.license"));
        LicenseService enterprise = new LicenseService(TestLicenses.verifier(), dir, "", at("2026-08-22"));
        assertTrue(enterprise.enterpriseTier());
        assertFalse(enterprise.teamTier());
        assertTrue(enterprise.allows(Feature.ORG_POLICIES));
        assertNull(enterprise.refusal(Feature.ORG_POLICIES));

        // A free installation is one person on their own machine: no cap applies, no feature either.
        Files.deleteIfExists(dir.resolve("license.key"));
        LicenseService free = new LicenseService(TestLicenses.verifier(), dir, "", at("2026-08-22"));
        assertFalse(free.teamTier());
        assertFalse(free.allows(Feature.AUDIT_EXPORT));
        assertTrue(free.refusal(Feature.AUDIT_EXPORT).contains("Install an enterprise license"));
    }

    @Test
    void teamLicense_unlocksTheSameGatesAsEnterprise(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("license.key"), TestLicenses.token("team-test.license"));
        LicenseService s = new LicenseService(TestLicenses.verifier(), dir, "", at("2026-08-22"));
        assertTrue(s.enterpriseActive());
        assertEquals(3, s.seatLimit());
        LicenseStatus status = s.status();
        assertTrue(status.valid());
        assertEquals(License.TIER_TEAM, status.tier());
        assertEquals("Test Team", status.licensee());
        assertEquals(3, status.seats());
        assertEquals("2099-01-01", status.expires());
    }

    @Test
    void teamLicense_getsTheSameGrace_andTheExpiredProblemNamesItsTier(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("license.key"), TestLicenses.token("team-expired-test.license"));
        // expired 2020-06-01; day 9 of 14 -> still active, with the countdown
        LicenseService inGrace = new LicenseService(TestLicenses.verifier(), dir, "", at("2020-06-10"));
        assertTrue(inGrace.enterpriseActive());
        assertEquals(5, inGrace.status().graceDaysLeft());
        // past expires+14 -> one seat again, and the message says "team", not "enterprise": the
        // owner renews the thing they bought, from the card they bought it on.
        LicenseService over = new LicenseService(TestLicenses.verifier(), dir, "", at("2020-06-16"));
        assertFalse(over.enterpriseActive());
        assertEquals(1, over.seatLimit());
        assertTrue(over.status().problem().contains("team license"), over.status().problem());
    }

    // The trial: a team license to every gate, a trial to the status the UI reads. `trial` is the
    // only thing that differs — the same seats, the same grace, the same seat limit.

    @Test
    void trialLicense_isActiveLikeAnyTeamLicense_andTheStatusSaysTrial(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("license.key"), TestLicenses.token("team-trial-test.license"));
        LicenseService s = new LicenseService(TestLicenses.verifier(), dir, "", at("2026-08-25"));
        assertTrue(s.enterpriseActive());
        assertEquals(3, s.seatLimit());
        LicenseStatus status = s.status();
        assertTrue(status.valid());
        assertTrue(status.trial());
        assertEquals(License.TIER_TEAM, status.tier());
        assertEquals("2026-09-05", status.expires());
        assertNull(status.graceDaysLeft());
    }

    @Test
    void boughtTeamLicense_andEveryOtherTier_reportTrialFalse(@TempDir Path dir) throws Exception {
        for (String fixture : new String[] {"team-test.license", "enterprise-test.license", "individual-test.license"}) {
            Files.writeString(dir.resolve("license.key"), TestLicenses.token(fixture));
            assertFalse(new LicenseService(TestLicenses.verifier(), dir, "", at("2026-08-25")).status().trial(), fixture);
        }
        assertFalse(new LicenseService(TestLicenses.verifier(), Files.createTempDirectory(dir, "empty"), "",
                at("2026-08-25")).status().trial());
    }

    @Test
    void trialLicense_afterExpiry_getsTheSameGrace_thenAProblemThatPointsAtBuying(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("license.key"), TestLicenses.token("team-trial-test.license"));
        // ended 2026-09-05; 2026-09-10 is day 5 of 14 -> still active, 9 days of grace left
        LicenseService inGrace = new LicenseService(TestLicenses.verifier(), dir, "", at("2026-09-10"));
        assertTrue(inGrace.enterpriseActive());
        assertEquals(9, inGrace.status().graceDaysLeft());
        assertTrue(inGrace.status().trial());
        // past 2026-09-19 -> off; a trial is not renewed, so the message says "trial" and points at the license cards
        LicenseService over = new LicenseService(TestLicenses.verifier(), dir, "", at("2026-09-20"));
        assertFalse(over.enterpriseActive());
        assertEquals(1, over.seatLimit());
        assertTrue(over.status().problem().contains("trial"), over.status().problem());
        assertTrue(over.status().problem().contains("team or enterprise license"), over.status().problem());
    }

    @Test
    void teamLicense_whenTheTierIsOff_isUnverifiable_andTheProblemNamesTheProperty(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("license.key"), TestLicenses.token("team-test.license"));
        LicenseService s = new LicenseService(LicenseVerifier.forProduction("", ""), dir, "", at("2026-08-22"));
        assertFalse(s.status().valid());
        assertEquals(1, s.seatLimit());
        assertTrue(s.status().problem().contains(LicenseVerifier.PROPERTY_TEAM_PUBLIC_KEY), s.status().problem());
    }

    // CONCENTUS_LICENSE_TEST_KEYS hook: LicenseVerifier.forProduction(envTestKeys, teamKey) is what
    // a production entry point (the Spring constructor here, LicenseCheck's two-arg entry) builds
    // its verifier from instead of LicenseVerifier.production(). Passing that same function's
    // result in here — the way the Spring constructor does — is what proves the hook actually
    // reaches LicenseService, not just that forProduction itself works (LicenseVerifierTest covers
    // that).

    @Test
    void envTestKeys_pointingAtTheFixture_aFixtureSignedLicensePasses(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("license.key"), TestLicenses.token("enterprise-test.license"));
        LicenseVerifier v = LicenseVerifier.forProduction(TestLicenses.testKeysPath().toString(), "");
        LicenseService s = new LicenseService(v, dir, "", at("2026-08-22"));
        assertTrue(s.enterpriseActive());
        assertEquals("Test Corp", s.status().licensee());
        assertEquals(5, s.seatLimit());
    }

    @Test
    void withoutEnvTestKeys_productionKeysAreUsed_aFixtureSignedLicenseIsRejected(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("license.key"), TestLicenses.token("enterprise-test.license"));
        LicenseService s = new LicenseService(LicenseVerifier.forProduction("", ""), dir, "", at("2026-08-22"));
        assertFalse(s.status().valid());
        assertEquals(1, s.seatLimit());
    }
}
