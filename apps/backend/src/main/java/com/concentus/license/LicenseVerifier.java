package com.concentus.license;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * Parses and verifies license tokens: CONCENTUS.<b64url payload>.<b64url Ed25519 signature>.
 *
 * <p>The signature is checked over the ASCII bytes of the payload SEGMENT, not over re-serialized
 * JSON — so this class never has to produce byte-identical JSON to the Node code that minted the
 * token. And the key is chosen by the tier the payload claims: an "enterprise" payload signed by
 * the individual key fails here, which is the whole point of having two keys.
 *
 * <p>Expiry is deliberately NOT checked here — an expired license is still authentically ours,
 * and grace-period policy belongs to LicenseService.
 */
public final class LicenseVerifier {

    private static final Logger log = LoggerFactory.getLogger(LicenseVerifier.class);

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .findAndRegisterModules();  // for LocalDate

    /**
     * When set, names a JSON file shaped exactly like the committed test fixture
     * {@code apps/backend/src/test/resources/license/test-keys.json} — {@code
     * {individual:{publicKeySpkiBase64},enterprise:{publicKeySpkiBase64}}} — whose keys REPLACE the
     * embedded production trust root wholesale. See {@link #forProduction}.
     */
    public static final String ENV_TEST_KEYS = "CONCENTUS_LICENSE_TEST_KEYS";

    private final Map<String, PublicKey> publicKeysByTier;

    public LicenseVerifier(Map<String, PublicKey> publicKeysByTier) {
        this.publicKeysByTier = Map.copyOf(publicKeysByTier);
    }

    /** The verifier trusting the shipped production keys. */
    public static LicenseVerifier production() {
        return new LicenseVerifier(Map.of(
                License.TIER_INDIVIDUAL, publicKey(LicenseKeys.INDIVIDUAL_SPKI_BASE64),
                License.TIER_ENTERPRISE, publicKey(LicenseKeys.ENTERPRISE_SPKI_BASE64)));
    }

    /**
     * The verifier a production entry point should use: the real embedded keys, unless {@code
     * envTestKeys} names a test-keys JSON file (see {@link #ENV_TEST_KEYS}) — in which case licenses
     * are checked against THAT trust root instead, loudly, for the rest of this process's life.
     *
     * <p>Why a hook exists at all: the app embeds the real public keys, and the e2e suite runs the
     * real jar. A license that verifies there would have to be signed with the real enterprise
     * private key — and committing such a license (or the key that made it) to a public repository
     * hands everyone a valid enterprise license. So the alternative is this: an explicit, documented,
     * loudly-logged TEST hook, not a second door. It replaces the trust root WHOLESALE — anyone who
     * sets it loses real-license verification entirely — which is what keeps it honest: nobody sets
     * it by accident and keeps working normally. This is honest-by-default enforcement, not DRM.
     *
     * <p>A pure function of its parameter, deliberately, so the hook is testable without touching the
     * real environment: only the outermost production entry points ({@link LicenseService}'s Spring
     * constructor, {@link LicenseCheck}'s one-arg entry) may read {@code System.getenv}/{@code
     * @Value} and pass the result in here; every test passes a literal string instead.
     */
    public static LicenseVerifier forProduction(String envTestKeys) {
        if (envTestKeys == null || envTestKeys.isBlank()) {
            return production();
        }
        log.warn("TEST LICENSE KEYS ACTIVE ({}) — licenses signed with the production keys will NOT verify.",
                envTestKeys);
        return fromTestKeysFile(Path.of(envTestKeys));
    }

    /** Loads a verifier trusting the keys in a JSON file shaped like the committed fixture. */
    private static LicenseVerifier fromTestKeysFile(Path jsonFile) {
        try {
            JsonNode keys = JSON.readTree(jsonFile.toFile());
            return new LicenseVerifier(Map.of(
                    License.TIER_INDIVIDUAL,
                    publicKey(keys.get("individual").get("publicKeySpkiBase64").asText()),
                    License.TIER_ENTERPRISE,
                    publicKey(keys.get("enterprise").get("publicKeySpkiBase64").asText())));
        } catch (Exception e) {
            throw new IllegalStateException("Could not read test license keys from " + jsonFile, e);
        }
    }

    private static PublicKey publicKey(String spkiBase64) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(spkiBase64)));
        } catch (Exception e) {
            throw new IllegalStateException("The embedded license public key is unreadable.", e);
        }
    }

    public License verify(String token) throws InvalidLicenseException {
        if (token == null || token.isBlank()) throw new InvalidLicenseException("No license text.");
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3 || !"CONCENTUS".equals(parts[0])) {
            throw new InvalidLicenseException("This is not a Concentus license.");
        }
        License license;
        try {
            license = JSON.readValue(Base64.getUrlDecoder().decode(parts[1]), License.class);
        } catch (Exception e) {
            throw new InvalidLicenseException("The license payload is unreadable.", e);
        }
        PublicKey key = publicKeysByTier.get(license.tier());
        if (key == null) throw new InvalidLicenseException("Unknown license tier: " + license.tier());
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(parts[1].getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                throw new InvalidLicenseException("The license signature does not match its tier's key.");
            }
        } catch (InvalidLicenseException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidLicenseException("The license signature is unreadable.", e);
        }
        return license;
    }
}
