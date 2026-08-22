package com.concentus.license;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * The fixture-key verifier and the committed {@code /license/} fixtures, shared by every test that
 * needs a real (test-key-signed) license token: LicenseVerifierTest and LicenseServiceTest both
 * verify against the same fixtures, so loading them lived in one place rather than two copies that
 * could quietly drift apart.
 */
final class TestLicenses {

    private TestLicenses() { }

    /** A fixture license token, trimmed, read from {@code /license/<fixtureName>}. */
    static String token(String fixtureName) throws Exception {
        try (InputStream in = TestLicenses.class.getResourceAsStream("/license/" + fixtureName)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    /** A {@link LicenseVerifier} trusting the committed test keys, not the production ones. */
    static LicenseVerifier verifier() throws Exception {
        try (InputStream in = TestLicenses.class.getResourceAsStream("/license/test-keys.json")) {
            var keys = new ObjectMapper().readTree(in);
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PublicKey individual = kf.generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(keys.get("individual").get("publicKeySpkiBase64").asText())));
            PublicKey enterprise = kf.generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(keys.get("enterprise").get("publicKeySpkiBase64").asText())));
            return new LicenseVerifier(Map.of(
                    License.TIER_INDIVIDUAL, individual, License.TIER_ENTERPRISE, enterprise));
        }
    }
}
