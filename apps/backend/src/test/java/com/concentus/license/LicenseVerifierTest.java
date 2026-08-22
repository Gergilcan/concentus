package com.concentus.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies AGAINST THE COMMITTED FIXTURES, which were minted by the Node module — this test is
 * the interop contract: a license signed over there parses and verifies over here.
 */
class LicenseVerifierTest {

    private static String fixture(String name) throws Exception {
        try (InputStream in = LicenseVerifierTest.class.getResourceAsStream("/license/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static LicenseVerifier verifier() throws Exception {
        try (InputStream in = LicenseVerifierTest.class.getResourceAsStream("/license/test-keys.json")) {
            var keys = new ObjectMapper().readTree(in);
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PublicKey ind = kf.generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(keys.get("individual").get("publicKeySpkiBase64").asText())));
            PublicKey ent = kf.generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(keys.get("enterprise").get("publicKeySpkiBase64").asText())));
            return new LicenseVerifier(Map.of(License.TIER_INDIVIDUAL, ind, License.TIER_ENTERPRISE, ent));
        }
    }

    @Test
    void individualFixtureVerifies() throws Exception {
        License l = verifier().verify(fixture("individual-test.license"));
        assertEquals(License.TIER_INDIVIDUAL, l.tier());
        assertEquals("Test Person", l.licensee());
        assertNull(l.expires());
    }

    @Test
    void enterpriseFixtureCarriesSeatsAndExpiry() throws Exception {
        License l = verifier().verify(fixture("enterprise-test.license"));
        assertEquals(5, l.seats());
        assertEquals("2099-01-01", l.expires().toString());
    }

    @Test
    void expiredFixtureStillVerifies_expiryIsPolicyNotSignature() throws Exception {
        // The verifier answers "is this really one of ours"; WHEN it is valid is LicenseService's
        // question. An expired license must parse, or grace periods could not exist.
        License l = verifier().verify(fixture("enterprise-expired-test.license"));
        assertEquals("2020-06-01", l.expires().toString());
    }

    @Test
    void tamperedPayloadIsRefused() throws Exception {
        String token = fixture("individual-test.license");
        String[] parts = token.split("\\.");
        String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("individual", "enterprise");
        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];
        assertThrows(InvalidLicenseException.class, () -> verifier().verify(forged));
    }

    @Test
    void garbageIsRefusedNotCrashedOn() throws Exception {
        assertThrows(InvalidLicenseException.class, () -> verifier().verify("hello"));
        assertThrows(InvalidLicenseException.class, () -> verifier().verify("CONCENTUS.??.!!"));
        assertThrows(InvalidLicenseException.class, () -> verifier().verify(""));
    }
}
