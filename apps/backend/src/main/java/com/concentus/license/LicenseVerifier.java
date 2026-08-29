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
import java.util.HashMap;
import java.util.Map;

/**
 * Parses and verifies license tokens: CONCENTUS.<b64url payload>.<b64url Ed25519 signature>.
 *
 * <p>The signature is checked over the ASCII bytes of the payload SEGMENT, not over re-serialized
 * JSON — so this class never has to produce byte-identical JSON to the Node code that minted the
 * token. And the key is chosen by the tier the payload claims: an "enterprise" payload signed by
 * the individual key fails here, which is the whole point of having separate keys.
 *
 * <p>Three keys, three trust levels. Individual and enterprise are embedded constants. The team
 * key is a property ({@code license.team-public-key}) because it may legitimately not exist yet —
 * and because its private half lives in Vercel, where the free issuer's does, this class also
 * enforces the CEILING that makes that acceptable: a team license claiming more than {@link
 * License#TEAM_MAX_SEATS} seats, or no expiry at all, is refused even when its signature is
 * genuine. Whoever gets into the web tier can mint small, expiring licenses and nothing more.
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
     * {individual:{publicKeySpkiBase64},enterprise:{publicKeySpkiBase64},team:{...}}} — whose keys
     * REPLACE the embedded production trust root wholesale. See {@link #forProduction}.
     */
    public static final String ENV_TEST_KEYS = "CONCENTUS_LICENSE_TEST_KEYS";

    /** The property that carries the team public key; blank switches the tier off. */
    public static final String PROPERTY_TEAM_PUBLIC_KEY = "license.team-public-key";

    private final Map<String, PublicKey> publicKeysByTier;

    public LicenseVerifier(Map<String, PublicKey> publicKeysByTier) {
        this.publicKeysByTier = Map.copyOf(publicKeysByTier);
    }

    /**
     * The verifier trusting the shipped production keys, plus the team key when {@code
     * teamPublicKeySpki} carries one. Blank means "no such tier here": a team token then fails with
     * a message naming the property rather than a bare "unknown tier".
     */
    public static LicenseVerifier production(String teamPublicKeySpki) {
        Map<String, PublicKey> keys = new HashMap<>();
        keys.put(License.TIER_INDIVIDUAL, publicKey(LicenseKeys.INDIVIDUAL_SPKI_BASE64));
        keys.put(License.TIER_ENTERPRISE, publicKey(LicenseKeys.ENTERPRISE_SPKI_BASE64));
        if (teamPublicKeySpki != null && !teamPublicKeySpki.isBlank()) {
            keys.put(License.TIER_TEAM, publicKey(teamPublicKeySpki.trim()));
        }
        return new LicenseVerifier(keys);
    }

    /**
     * The verifier a production entry point should use: the real embedded keys (and the configured
     * team key), unless {@code envTestKeys} names a test-keys JSON file (see {@link #ENV_TEST_KEYS})
     * — in which case licenses are checked against THAT trust root instead, loudly, for the rest of
     * this process's life.
     *
     * <p>Why a hook exists at all: the app embeds the real public keys, and the e2e suite runs the
     * real jar. A license that verifies there would have to be signed with the real enterprise
     * private key — and committing such a license (or the key that made it) to a public repository
     * hands everyone a valid enterprise license. So the alternative is this: an explicit, documented,
     * loudly-logged TEST hook, not a second door. It replaces the trust root WHOLESALE — anyone who
     * sets it loses real-license verification entirely, the team property included — which is what
     * keeps it honest: nobody sets it by accident and keeps working normally. This is
     * honest-by-default enforcement, not DRM.
     *
     * <p>A pure function of its parameters, deliberately, so the hook is testable without touching
     * the real environment: only the outermost production entry points ({@link LicenseService}'s
     * Spring constructor, {@link LicenseCheck}'s two-arg entry) may read {@code System.getenv}/{@code
     * @Value} and pass the result in here; every test passes literal strings instead.
     */
    public static LicenseVerifier forProduction(String envTestKeys, String teamPublicKeySpki) {
        if (envTestKeys == null || envTestKeys.isBlank()) {
            return production(teamPublicKeySpki);
        }
        log.warn("TEST LICENSE KEYS ACTIVE ({}) — licenses signed with the production keys will NOT verify.",
                envTestKeys);
        return fromTestKeysFile(Path.of(envTestKeys));
    }

    /**
     * Loads a verifier trusting the keys in a JSON file shaped like the committed fixture. The
     * {@code team} entry is optional there, so a keys file from before the tier existed still loads.
     */
    private static LicenseVerifier fromTestKeysFile(Path jsonFile) {
        try {
            JsonNode keys = JSON.readTree(jsonFile.toFile());
            Map<String, PublicKey> byTier = new HashMap<>();
            for (String tier : new String[] {License.TIER_INDIVIDUAL, License.TIER_ENTERPRISE, License.TIER_TEAM}) {
                JsonNode entry = keys.get(tier);
                if (entry != null) byTier.put(tier, publicKey(entry.get("publicKeySpkiBase64").asText()));
            }
            return new LicenseVerifier(byTier);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read test license keys from " + jsonFile, e);
        }
    }

    private static PublicKey publicKey(String spkiBase64) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(spkiBase64)));
        } catch (Exception e) {
            throw new IllegalStateException("A license public key is unreadable.", e);
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
        if (key == null) {
            if (License.TIER_TEAM.equals(license.tier())) {
                throw new InvalidLicenseException("This installation does not accept team licenses ("
                        + PROPERTY_TEAM_PUBLIC_KEY + " is not set).");
            }
            throw new InvalidLicenseException("Unknown license tier: " + license.tier());
        }
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
        if (License.TIER_TEAM.equals(license.tier())) requireTeamCeiling(license);
        return license;
    }

    /**
     * The ceiling on what a genuinely-signed team license may claim. Checked AFTER the signature,
     * on purpose: a forged token is refused as a forgery, and only a real one gets to be told it
     * asks for too much.
     */
    private static void requireTeamCeiling(License license) throws InvalidLicenseException {
        if (license.seats() == null || license.seats() < 1 || license.seats() > License.TEAM_MAX_SEATS) {
            throw new InvalidLicenseException("A team license covers 1 to " + License.TEAM_MAX_SEATS
                    + " seats; this one claims " + license.seats()
                    + ". Teams past that need an enterprise license.");
        }
        if (license.expires() == null) {
            throw new InvalidLicenseException("A team license must carry an expiry date; this one is perpetual.");
        }
    }
}
