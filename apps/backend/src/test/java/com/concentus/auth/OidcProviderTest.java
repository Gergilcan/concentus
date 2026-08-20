package com.concentus.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which identity provider a deployment signs in against.
 *
 * <p>Presets exist so the common cases need a client id and a secret and nothing else; the generic
 * case exists so the choice of supplier is never this application's to make. Endpoints are always
 * discovered from the issuer — three URLs copied by hand are three chances to paste the wrong one,
 * and a wrong token endpoint fails after the person has already typed their password.
 */
class OidcProviderTest {

    @Test
    void the_microsoft_preset_points_at_the_configured_directory() {
        OidcProvider p = OidcProvider.of("microsoft", null, "11112222-3333-4444-5555-666677778888",
                null, null);

        assertThat(p.issuer()).isEqualTo(
                "https://login.microsoftonline.com/11112222-3333-4444-5555-666677778888/v2.0");
        assertThat(p.discoveryUrl()).endsWith("/.well-known/openid-configuration");
        assertThat(p.displayName()).isEqualTo("Microsoft");
    }

    // A directory id restricts sign-in to that directory, which is what an organization wants.
    // Without one, "organizations" is the safer of the two catch-alls: work and school accounts,
    // not every personal Outlook.com address in the world.
    @Test
    void microsoft_without_a_tenant_still_excludes_personal_accounts() {
        assertThat(OidcProvider.of("microsoft", null, null, null, null).issuer())
                .contains("/organizations/");
    }

    @Test
    void google_knows_its_own_issuer() {
        OidcProvider p = OidcProvider.of("google", null, null, null, null);

        assertThat(p.issuer()).isEqualTo("https://accounts.google.com");
        assertThat(p.displayName()).isEqualTo("Google");
    }

    // Auth0, Keycloak, Authentik, Okta: anything that publishes a discovery document.
    @Test
    void anything_else_is_named_by_its_issuer() {
        OidcProvider p = OidcProvider.of("generic", "https://tecnovent.eu.auth0.com/", null,
                "Tecnovent", null);

        assertThat(p.issuer()).isEqualTo("https://tecnovent.eu.auth0.com");
        assertThat(p.discoveryUrl())
                .isEqualTo("https://tecnovent.eu.auth0.com/.well-known/openid-configuration");
        assertThat(p.displayName()).isEqualTo("Tecnovent");
    }

    // The button must not exist without somewhere to send people: a sign-in that fails at the
    // redirect is worse than one that is absent.
    @Test
    void a_generic_provider_with_no_issuer_is_not_usable() {
        assertThat(OidcProvider.of("generic", "", null, null, null).isUsable()).isFalse();
        assertThat(OidcProvider.of("microsoft", null, null, null, null).isUsable()).isTrue();
    }

    @Test
    void the_scope_defaults_to_the_claims_this_flow_actually_reads() {
        assertThat(OidcProvider.of("google", null, null, null, null).scope())
                .isEqualTo("openid profile email");
        assertThat(OidcProvider.of("google", null, null, null, "openid email").scope())
                .isEqualTo("openid email");
    }

    // Behaves as generic — endpoints discovered from the issuer, nothing assumed about the
    // supplier — but keeps the name it was given. A deployment can configure two of these, and
    // folding both into one "generic" would have the second shadow the first: one button, and it
    // sends people to the other company's directory.
    @Test
    void an_unknown_preset_is_discovered_from_its_issuer_and_keeps_its_name() {
        OidcProvider p = OidcProvider.of("okta", "https://tecnovent.okta.com", null, null, null);

        assertThat(p.id()).isEqualTo("okta");
        assertThat(p.issuer()).isEqualTo("https://tecnovent.okta.com");
        assertThat(p.hasStatedEndpoints()).isFalse();
        assertThat(p.subjectClaim()).isEqualTo("sub");
    }

    // The id is written into user_identities beside every subject and into a URL, and it comes
    // from configuration.
    @Test
    void a_name_that_is_not_url_safe_is_reduced_rather_than_carried_through() {
        assertThat(OidcProvider.of("Corp Directory!", "https://id.example", null, null, null).id())
                .isEqualTo("corpdirectory");
    }
}
