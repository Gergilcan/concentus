package com.concentus.license;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies AGAINST THE COMMITTED FIXTURES, which were minted by the Node module — this test is
 * the interop contract: a license signed over there parses and verifies over here.
 *
 * <p>Fixture loading itself lives in {@link TestLicenses}, shared with LicenseServiceTest.
 */
class LicenseVerifierTest {

    @Test
    void individualFixtureVerifies() throws Exception {
        License l = TestLicenses.verifier().verify(TestLicenses.token("individual-test.license"));
        assertEquals(License.TIER_INDIVIDUAL, l.tier());
        assertEquals("Test Person", l.licensee());
        assertNull(l.expires());
    }

    @Test
    void enterpriseFixtureCarriesSeatsAndExpiry() throws Exception {
        License l = TestLicenses.verifier().verify(TestLicenses.token("enterprise-test.license"));
        assertEquals(5, l.seats());
        assertEquals("2099-01-01", l.expires().toString());
    }

    @Test
    void expiredFixtureStillVerifies_expiryIsPolicyNotSignature() throws Exception {
        // The verifier answers "is this really one of ours"; WHEN it is valid is LicenseService's
        // question. An expired license must parse, or grace periods could not exist.
        License l = TestLicenses.verifier().verify(TestLicenses.token("enterprise-expired-test.license"));
        assertEquals("2020-06-01", l.expires().toString());
    }

    @Test
    void tamperedPayloadIsRefused() throws Exception {
        String token = TestLicenses.token("individual-test.license");
        String[] parts = token.split("\\.");
        String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("individual", "enterprise");
        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];
        assertThrows(InvalidLicenseException.class, () -> TestLicenses.verifier().verify(forged));
    }

    @Test
    void garbageIsRefusedNotCrashedOn() throws Exception {
        assertThrows(InvalidLicenseException.class, () -> TestLicenses.verifier().verify("hello"));
        assertThrows(InvalidLicenseException.class, () -> TestLicenses.verifier().verify("CONCENTUS.??.!!"));
        assertThrows(InvalidLicenseException.class, () -> TestLicenses.verifier().verify(""));
    }

    // The team tier: a third key, and a ceiling on what that key may sign. The ceiling is the
    // security property — the private half lives in Vercel next to the free issuer's, so the
    // verifier, not the key's location, is what keeps a web-tier compromise from minting anything
    // sellable beyond a small, expiring license.

    @Test
    void teamFixtureVerifies_withSeatsAndExpiry() throws Exception {
        License l = TestLicenses.verifier().verify(TestLicenses.token("team-test.license"));
        assertEquals(License.TIER_TEAM, l.tier());
        assertEquals("Test Team", l.licensee());
        assertEquals(3, l.seats());
        assertEquals("2099-01-01", l.expires().toString());
    }

    @Test
    void teamPayloadSignedByTheIndividualOrEnterpriseKey_isRefused() throws Exception {
        for (String fixture : new String[] {"team-signed-by-individual-test.license",
                                            "team-signed-by-enterprise-test.license"}) {
            InvalidLicenseException e = assertThrows(InvalidLicenseException.class,
                    () -> TestLicenses.verifier().verify(TestLicenses.token(fixture)), fixture);
            assertTrue(e.getMessage().contains("signature"), fixture + ": " + e.getMessage());
        }
    }

    @Test
    void teamLicenseOverTenSeats_isRefusedEvenThoughGenuinelySigned() throws Exception {
        InvalidLicenseException e = assertThrows(InvalidLicenseException.class,
                () -> TestLicenses.verifier().verify(TestLicenses.token("team-eleven-seats-test.license")));
        assertTrue(e.getMessage().contains("1 to 10 seats"), e.getMessage());
        assertTrue(e.getMessage().contains("11"), e.getMessage());
    }

    @Test
    void teamLicenseWithoutExpiry_isRefused() throws Exception {
        InvalidLicenseException e = assertThrows(InvalidLicenseException.class,
                () -> TestLicenses.verifier().verify(TestLicenses.token("team-perpetual-test.license")));
        assertTrue(e.getMessage().contains("expiry"), e.getMessage());
    }

    @Test
    void forProduction_blankTeamKey_teamTierIsOff_andTheRefusalNamesTheProperty() throws Exception {
        // The property's default: the tier does not exist for this build. The fixture token is
        // signed by the fixture team key, but no key at all is consulted — the message must point
        // at the property, because "unknown tier" would send an owner looking for a typo.
        LicenseVerifier v = LicenseVerifier.forProduction("", "");
        InvalidLicenseException e = assertThrows(InvalidLicenseException.class,
                () -> v.verify(TestLicenses.token("team-test.license")));
        assertTrue(e.getMessage().contains("does not accept team licenses"), e.getMessage());
        assertTrue(e.getMessage().contains(LicenseVerifier.PROPERTY_TEAM_PUBLIC_KEY), e.getMessage());
    }

    @Test
    void forProduction_withTheTeamKeyFromTheProperty_verifiesTeamTokens_whileTheOtherTiersStayProduction()
            throws Exception {
        // What the property does: adds the third key on top of the embedded two. The fixture
        // individual token must STILL fail here — the team property is not a back door for the
        // other tiers' trust roots.
        LicenseVerifier v = LicenseVerifier.forProduction("", TestLicenses.publicKeySpkiBase64(License.TIER_TEAM));
        License l = v.verify(TestLicenses.token("team-test.license"));
        assertEquals(3, l.seats());
        assertThrows(InvalidLicenseException.class,
                () -> v.verify(TestLicenses.token("individual-test.license")));
    }

    // forProduction is the CONCENTUS_LICENSE_TEST_KEYS hook's pure core: a production entry point
    // passes it whatever the environment says (or doesn't), and only this function decides which
    // trust root results. Testing it directly, with the env value as an ordinary parameter, is what
    // keeps LicenseService/LicenseCheck from needing to fake System.getenv at all.

    @Test
    void forProduction_blank_isTheEmbeddedProductionKeys_fixtureTokensDoNotVerify() throws Exception {
        LicenseVerifier v = LicenseVerifier.forProduction("", "");
        // The fixture is signed with the TEST private key, never the real embedded one — production
        // trusting it would mean the test hook leaked into the default path.
        assertThrows(InvalidLicenseException.class,
                () -> v.verify(TestLicenses.token("individual-test.license")));
    }

    @Test
    void forProduction_null_isAlsoTheEmbeddedProductionKeys() throws Exception {
        LicenseVerifier v = LicenseVerifier.forProduction(null, null);
        assertThrows(InvalidLicenseException.class,
                () -> v.verify(TestLicenses.token("individual-test.license")));
    }

    @Test
    void forProduction_pointingAtTheFixtureKeysFile_verifiesFixtureTokens_teamIncluded() throws Exception {
        // The test-keys file replaces the trust root wholesale — the team property is ignored too,
        // which is why a blank one here still verifies the fixture team token.
        LicenseVerifier v = LicenseVerifier.forProduction(TestLicenses.testKeysPath().toString(), "");
        License l = v.verify(TestLicenses.token("enterprise-test.license"));
        assertEquals("Test Corp", l.licensee());
        assertEquals(5, l.seats());
        assertEquals(3, v.verify(TestLicenses.token("team-test.license")).seats());
    }
}
