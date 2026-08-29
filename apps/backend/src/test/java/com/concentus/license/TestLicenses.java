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
import java.util.HashMap;
import java.util.List;
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

    /**
     * Filesystem path to the committed {@code test-keys.json} fixture — for hooks that take a
     * FILE path rather than a classpath resource, namely {@link LicenseVerifier#forProduction} via
     * the {@code CONCENTUS_LICENSE_TEST_KEYS} env var. Resolved off the classloader (not a relative
     * path from the working directory) so it finds the fixture the same way whether tests run from
     * an IDE or from {@code mvn test}.
     */
    public static Path testKeysPath() throws Exception {
        return Path.of(TestLicenses.class.getResource("/license/test-keys.json").toURI());
    }

    /** A {@link LicenseVerifier} trusting the committed test keys (all three tiers), not the production ones. */
    public static LicenseVerifier verifier() throws Exception {
        KeyFactory kf = KeyFactory.getInstance("Ed25519");
        Map<String, PublicKey> byTier = new HashMap<>();
        for (String tier : List.of(License.TIER_INDIVIDUAL, License.TIER_ENTERPRISE, License.TIER_TEAM)) {
            byTier.put(tier, kf.generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(publicKeySpkiBase64(tier)))));
        }
        return new LicenseVerifier(byTier);
    }

    /**
     * One tier's public key from {@code test-keys.json}, as the SPKI base64 string — the same shape
     * the {@code license.team-public-key} property carries, so a test can hand the fixture team
     * key to {@link LicenseVerifier#forProduction} exactly the way the property would.
     */
    public static String publicKeySpkiBase64(String tier) throws Exception {
        try (InputStream in = TestLicenses.class.getResourceAsStream("/license/test-keys.json")) {
            return new ObjectMapper().readTree(in).get(tier).get("publicKeySpkiBase64").asText();
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
