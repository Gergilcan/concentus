package com.concentus.license;

/**
 * The two public keys the app trusts, DER SPKI as base64. TWO on purpose: the individual key's
 * private half lives in Vercel (automatic free issuance), the enterprise key's only on the
 * author's machine — so compromising the web tier can mint nothing sellable. Which key verifies
 * a license is cross-checked against the tier the payload CLAIMS, in LicenseVerifier.
 */
public final class LicenseKeys {
    public static final String INDIVIDUAL_SPKI_BASE64 = "MCowBQYDK2VwAyEArVAL0gF4Y6GLAzVibr2gcDX8hfExi4QFmaniEQzr1V8=";
    public static final String ENTERPRISE_SPKI_BASE64 = "MCowBQYDK2VwAyEACA+XWuTEt4S/w7VMNS2ODoac/d4pe8fhww5EO0MkPSw=";
    private LicenseKeys() { }
}
