package com.concentus.auth;

import org.junit.jupiter.api.Test;
import com.concentus.config.Settings;
import com.concentus.config.SettingsStore;
import com.concentus.license.LicenseService;
import com.concentus.license.TestLicenses;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which identity providers a deployment offers.
 *
 * <p>More than one, because "which provider" is not a question a company answers once: staff on
 * the corporate directory, a contractor with a Google account, and whoever installed it with a
 * password. What matters here is that a provider is offered only when it would actually work, and
 * that the single-provider configuration written before this kept its meaning.
 */
class OidcRegistryTest {

    /**
     * The configuration these providers are read from.
     *
     * <p>A MockEnvironment behind a real Settings, rather than the environment directly: what the
     * registry reads now is the resolver, so a deployment's configuration and what somebody typed
     * into the app go through the same door. Building it this way keeps these tests about the
     * provider entries and not about which of the two won.
     */
    private static Settings settingsFrom(MockEnvironment env) {
        return new Settings(new SettingsStore(null, null) {
            @Override
            public java.util.Optional<String> get(String organizationId, String key) {
                return java.util.Optional.empty();
            }
        }, env, new OrgContext("default"));
    }

    private static MockEnvironment env() {
        return new MockEnvironment();
    }

    /**
     * An {@link OidcRegistry} reading {@code env}, on an unlicensed installation — the license
     * question is not what these reading tests are about, so every one of them gets the same
     * seat-limit-of-one service the gate tests below build their "no license" case from too.
     */
    private static OidcRegistry registryFrom(MockEnvironment env) throws Exception {
        return new OidcRegistry(settingsFrom(env), TestLicenses.serviceOn(Files.createTempDirectory("oidc-registry-test")));
    }

    @Test
    void several_providers_are_offered_in_the_order_they_were_named() throws Exception {
        MockEnvironment env = env()
                .withProperty("app.auth.oidc.providers", "microsoft,google")
                .withProperty("app.auth.oidc.microsoft.client-id", "ms-id")
                .withProperty("app.auth.oidc.microsoft.client-secret", "ms-secret")
                .withProperty("app.auth.oidc.google.client-id", "g-id")
                .withProperty("app.auth.oidc.google.client-secret", "g-secret");

        OidcRegistry registry = registryFrom(env);

        assertThat(registry.all()).extracting(OidcRegistry.Configured::id)
                .containsExactly("microsoft", "google");
        assertThat(registry.all()).extracting(OidcRegistry.Configured::displayName)
                .containsExactly("Microsoft", "Google");
    }

    // A button that fails at the redirect is worse than one that is absent: the person has already
    // decided to sign in by the time they find out.
    @Test
    void a_provider_without_credentials_is_not_offered() throws Exception {
        MockEnvironment env = env()
                .withProperty("app.auth.oidc.providers", "microsoft,google")
                .withProperty("app.auth.oidc.microsoft.client-id", "ms-id")
                .withProperty("app.auth.oidc.microsoft.client-secret", "ms-secret")
                .withProperty("app.auth.oidc.google.client-id", "g-id");

        assertThat(registryFrom(env).all()).extracting(OidcRegistry.Configured::id)
                .containsExactly("microsoft");
    }

    // The shape this application had before it could hold more than one. An upgrade that quietly
    // switched off a company's sign-in would lock out everyone whose account has no password.
    @Test
    void the_single_provider_configuration_still_means_what_it_did() throws Exception {
        MockEnvironment env = env()
                .withProperty("app.auth.oidc.enabled", "true")
                .withProperty("app.auth.oidc.provider", "microsoft")
                .withProperty("app.auth.oidc.tenant", "11112222-3333-4444-5555-666677778888")
                .withProperty("app.auth.oidc.client-id", "id")
                .withProperty("app.auth.oidc.client-secret", "secret");

        OidcRegistry registry = registryFrom(env);

        assertThat(registry.all()).hasSize(1);
        assertThat(registry.all().getFirst().provider().issuer())
                .contains("11112222-3333-4444-5555-666677778888");
    }

    @Test
    void nothing_configured_offers_nothing() throws Exception {
        assertThat(registryFrom(env()).any()).isFalse();
    }

    // A provider that is OAuth2 rather than OpenID Connect publishes nothing to discover and names
    // its fields differently. Configuration, not code.
    @Test
    void a_provider_that_does_not_publish_its_endpoints_states_them_instead() throws Exception {
        MockEnvironment env = env()
                .withProperty("app.auth.oidc.providers", "discord")
                .withProperty("app.auth.oidc.discord.client-id", "id")
                .withProperty("app.auth.oidc.discord.client-secret", "secret");

        OidcProvider discord = registryFrom(env).byId("discord").orElseThrow().provider();

        assertThat(discord.hasStatedEndpoints()).isTrue();
        assertThat(discord.isUsable()).isTrue();
        // "sub" is the OpenID Connect claim; Discord answers with "id", and reading the wrong one
        // would match every account to nobody.
        assertThat(discord.subjectClaim()).isEqualTo("id");
    }

    @Test
    void any_provider_can_have_its_endpoints_and_claims_stated() throws Exception {
        MockEnvironment env = env()
                .withProperty("app.auth.oidc.providers", "internal")
                .withProperty("app.auth.oidc.internal.display-name", "Staff directory")
                .withProperty("app.auth.oidc.internal.authorization-url", "https://id.example/auth")
                .withProperty("app.auth.oidc.internal.token-url", "https://id.example/token")
                .withProperty("app.auth.oidc.internal.userinfo-url", "https://id.example/me")
                .withProperty("app.auth.oidc.internal.subject-claim", "user_id")
                .withProperty("app.auth.oidc.internal.email-claim", "mail")
                .withProperty("app.auth.oidc.internal.client-id", "id")
                .withProperty("app.auth.oidc.internal.client-secret", "secret");

        OidcRegistry.Configured configured = registryFrom(env).byId("internal").orElseThrow();

        assertThat(configured.displayName()).isEqualTo("Staff directory");
        assertThat(configured.provider().userinfoUrl()).isEqualTo("https://id.example/me");
        assertThat(configured.provider().emailClaim()).isEqualTo("mail");
    }

    // Said once, under the shared key, rather than repeated beneath every provider.
    @Test
    void the_role_a_first_time_arrival_gets_is_shared_unless_a_provider_overrides_it() throws Exception {
        MockEnvironment env = env()
                .withProperty("app.auth.oidc.providers", "microsoft,google")
                .withProperty("app.auth.oidc.default-role", "OPERATOR")
                .withProperty("app.auth.oidc.microsoft.client-id", "a")
                .withProperty("app.auth.oidc.microsoft.client-secret", "b")
                .withProperty("app.auth.oidc.google.client-id", "c")
                .withProperty("app.auth.oidc.google.client-secret", "d")
                .withProperty("app.auth.oidc.google.default-role", "VIEWER");

        OidcRegistry registry = registryFrom(env);

        assertThat(registry.byId("microsoft").orElseThrow().defaultRole()).isEqualTo("OPERATOR");
        assertThat(registry.byId("google").orElseThrow().defaultRole()).isEqualTo("VIEWER");
    }

    // The gate: reading a provider is unaffected by licensing (above), but writing a NEW one is.

    @Test
    void registering_a_provider_is_refused_without_an_enterprise_license() throws Exception {
        OidcRegistry registry = registryFrom(env());

        assertThatThrownBy(registry::requireEnterpriseToRegister)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enterprise");
    }

    @Test
    void registering_a_provider_is_allowed_with_the_enterprise_fixture_installed() throws Exception {
        Path dir = Files.createTempDirectory("oidc-registry-test-enterprise");
        TestLicenses.installFixture(dir, "enterprise-test.license");
        LicenseService enterprise = TestLicenses.serviceOn(dir);
        OidcRegistry registry = new OidcRegistry(settingsFrom(env()), enterprise);

        assertThatCode(registry::requireEnterpriseToRegister).doesNotThrowAnyException();
    }
}
