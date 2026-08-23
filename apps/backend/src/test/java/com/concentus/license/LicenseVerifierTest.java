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

    // forProduction is the CONCENTUS_LICENSE_TEST_KEYS hook's pure core: a production entry point
    // passes it whatever the environment says (or doesn't), and only this function decides which
    // trust root results. Testing it directly, with the env value as an ordinary parameter, is what
    // keeps LicenseService/LicenseCheck from needing to fake System.getenv at all.

    @Test
    void forProduction_blank_isTheEmbeddedProductionKeys_fixtureTokensDoNotVerify() throws Exception {
        LicenseVerifier v = LicenseVerifier.forProduction("");
        // The fixture is signed with the TEST private key, never the real embedded one — production
        // trusting it would mean the test hook leaked into the default path.
        assertThrows(InvalidLicenseException.class,
                () -> v.verify(TestLicenses.token("individual-test.license")));
    }

    @Test
    void forProduction_null_isAlsoTheEmbeddedProductionKeys() throws Exception {
        LicenseVerifier v = LicenseVerifier.forProduction(null);
        assertThrows(InvalidLicenseException.class,
                () -> v.verify(TestLicenses.token("individual-test.license")));
    }

    @Test
    void forProduction_pointingAtTheFixtureKeysFile_verifiesFixtureTokens() throws Exception {
        LicenseVerifier v = LicenseVerifier.forProduction(TestLicenses.testKeysPath().toString());
        License l = v.verify(TestLicenses.token("enterprise-test.license"));
        assertEquals("Test Corp", l.licensee());
        assertEquals(5, l.seats());
    }
}
