package com.concentus.license;

/**
 * The two public keys the app embeds, DER SPKI as base64. TWO on purpose: the individual key's
 * private half lives in Vercel (automatic free issuance), the enterprise key's only on the
 * author's machine — so compromising the web tier can mint nothing sellable. Which key verifies
 * a license is cross-checked against the tier the payload CLAIMS, in LicenseVerifier.
 *
 * <p>The team tier's key is deliberately NOT here: it arrives through the {@code
 * license.team-public-key} property, blank until the author has generated it (see
 * {@code apps/website/scripts/keygen.mjs}), and blank means the tier does not exist for this
 * installation. Its private half sits in Vercel next to the individual one, which is why the
 * verifier caps what a team license may claim.
 */
public final class LicenseKeys {
    public static final String INDIVIDUAL_SPKI_BASE64 = "MCowBQYDK2VwAyEArVAL0gF4Y6GLAzVibr2gcDX8hfExi4QFmaniEQzr1V8=";
    public static final String ENTERPRISE_SPKI_BASE64 = "MCowBQYDK2VwAyEACA+XWuTEt4S/w7VMNS2ODoac/d4pe8fhww5EO0MkPSw=";
    private LicenseKeys() { }
}
