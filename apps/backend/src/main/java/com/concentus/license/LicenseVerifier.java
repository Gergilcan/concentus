package com.concentus.license;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
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

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .findAndRegisterModules();  // for LocalDate

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
