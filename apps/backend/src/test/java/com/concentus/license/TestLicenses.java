package com.concentus.license;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;

/**
 * The fixture-key verifier and the committed {@code /license/} fixtures, shared by every test that
 * needs a real (test-key-signed) license token: LicenseVerifierTest and LicenseServiceTest both
 * verify against the same fixtures, so loading them lived in one place rather than two copies that
 * could quietly drift apart.
 *
 * <p>Public rather than package-private: the enforcement gates it feeds (AccountController's seat
 * limit, OidcRegistry's registration gate) live in other packages, and their tests need the same
 * fixtures — not copies of the loading code.
 */
public final class TestLicenses {

    private TestLicenses() { }

    /** A fixture license token, trimmed, read from {@code /license/<fixtureName>}. */
    public static String token(String fixtureName) throws Exception {
        try (InputStream in = TestLicenses.class.getResourceAsStream("/license/" + fixtureName)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    /** A {@link LicenseVerifier} trusting the committed test keys, not the production ones. */
    public static LicenseVerifier verifier() throws Exception {
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

    /**
     * A {@link LicenseService} reading whatever {@code dataDir} holds — nothing, for an empty
     * directory (the seat-limit-of-one every free installation gets), or a fixture a caller wrote
     * there first with {@link #installFixture}. Built against the fixture verifier, never the
     * production one.
     *
     * <p>Uses {@link LicenseService}'s package-private test constructor, reachable from here
     * because this class shares its package; callers in other packages (AccountController's and
     * OidcRegistry's tests) go through this factory rather than needing that access themselves.
     */
    public static LicenseService serviceOn(Path dataDir) throws Exception {
        return new LicenseService(verifier(), dataDir, "", Clock.systemUTC());
    }

    /** Writes {@code fixtureName} into {@code dataDir} as {@code license.key}, for {@link #serviceOn}. */
    public static void installFixture(Path dataDir, String fixtureName) throws Exception {
        Files.writeString(dataDir.resolve(LicenseService.FILE_NAME), token(fixtureName));
    }
}
