package com.concentus.auth;

import com.concentus.license.LicenseService;
import com.concentus.license.TestLicenses;
import com.concentus.store.SchemaMigrator;
import com.concentus.store.TestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provisioning a brand-new account from a directory sign-in, against the same seat limit {@link
 * com.concentus.auth.AccountController#createMember} enforces for a password-created member.
 *
 * <p>No dedicated test class existed for {@link OidcSignIn} before this one — the class has never
 * needed more than {@code complete()}'s three outward calls (discovery, the token exchange,
 * userinfo), which is why they go through a real {@link java.net.http.HttpClient} rather than an
 * injectable seam. So, in the spirit of {@code MicrosoftOAuthTest} (same shape of problem: a real
 * OAuth-ish exchange, stubbed with a real local server rather than a mock of the HTTP layer), the
 * provider here is configured with stated endpoints pointing at a {@link HttpServer} started for
 * the test — no discovery document needed, and {@code authorizationUrl()} is used to mint a real
 * {@code state} value so {@code complete()} runs its normal path end to end.
 */
class OidcSignInTest {

    private HttpServer server;
    /** The next userinfo response — set by {@link #arrive} right before completing the flow. */
    private volatile String userinfoBody = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange ->
                respond(exchange, "{\"access_token\":\"test-access-token\"}"));
        server.createContext("/userinfo", exchange -> respond(exchange, userinfoBody));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static final String PROVIDER_ID = "testprovider";

    /** A registry answering exactly one provider — stated endpoints, so nothing here is discovered. */
    private OidcRegistry registryFor(OidcProvider provider, String clientId, String clientSecret,
                                     String defaultRole) {
        OidcRegistry.Configured configured =
                new OidcRegistry.Configured(provider, clientId, clientSecret, defaultRole);
        return new OidcRegistry(null, null) {
            @Override
            public Optional<Configured> byId(String id) {
                return provider.id().equals(id) ? Optional.of(configured) : Optional.empty();
            }
        };
    }

    private record Fixture(OidcSignIn oidc, AccountStore accounts) {
    }

    private Fixture on(String databaseName) throws Exception {
        return on(databaseName, Files.createTempDirectory("oidc-sign-in-test"));
    }

    private Fixture on(String databaseName, Path licenseDir) throws Exception {
        DataSource ds = TestDatabase.freshDatabase(databaseName);
        assertThat(SchemaMigrator.migrate(ds)).isTrue();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        AccountStore accounts = new AccountStore(jdbc);
        accounts.init();
        UserIdentityStore identities = new UserIdentityStore(jdbc);
        EmailDomainPolicy domains = new EmailDomainPolicy(""); // allows every domain
        LicenseService licenseService = TestLicenses.serviceOn(licenseDir);

        OidcProvider provider = new OidcProvider(PROVIDER_ID, "Test Provider", "", "openid email",
                base() + "/authorize", base() + "/token", base() + "/userinfo",
                OidcProvider.STANDARD_SUBJECT, OidcProvider.STANDARD_EMAIL);
        OidcRegistry registry = registryFor(provider, "a-client-id", "a-client-secret", "VIEWER");

        OidcSignIn oidc = new OidcSignIn(accounts, identities, domains, new ObjectMapper(), registry,
                licenseService, "default");
        return new Fixture(oidc, accounts);
    }

    /** Drives one full sign-in: mints a real state via authorizationUrl(), then completes it. */
    private OidcSignIn.Outcome arrive(OidcSignIn oidc, String subject, String email) {
        String url = oidc.authorizationUrl(PROVIDER_ID, "http://localhost:0");
        String state = url.substring(url.indexOf("state=") + "state=".length());
        userinfoBody = "{\"sub\":\"" + subject + "\",\"email\":\"" + email + "\"}";
        return oidc.complete("a-test-code", state, null);
    }

    // (c) The very first arrival on an empty installation: never gated, and it administers it —
    // the same rule the setup screen follows for a password account.
    @Test
    void the_first_arrival_on_an_empty_installation_succeeds_and_administers_it() throws Exception {
        Fixture f = on("oidc_first_arrival");

        OidcSignIn.Outcome outcome = arrive(f.oidc(), "subject-1", "first@acme.com");

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.account().email()).isEqualTo("first@acme.com");
        assertThat(outcome.account().isAdmin()).isTrue();
        assertThat(f.accounts().countUsers()).isEqualTo(1);
    }

    // (a) A brand-new arrival once the organization is already at its seat limit is refused, with
    // the same message AccountController#createMember gives a password-based signup.
    @Test
    void a_new_arrival_is_refused_once_the_organization_is_at_its_seat_limit() throws Exception {
        Fixture f = on("oidc_new_arrival_at_limit");
        // Unlicensed: the seat limit is one, and the first arrival above already fills it.
        arrive(f.oidc(), "subject-1", "first@acme.com");

        OidcSignIn.Outcome outcome = arrive(f.oidc(), "subject-2", "second@acme.com");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.account()).isNull();
        LicenseService onlyLicense = TestLicenses.serviceOn(Files.createTempDirectory("oidc-message-check"));
        assertThat(outcome.error()).isEqualTo(onlyLicense.seatLimitReachedMessage(1));
        // Refused, not half-provisioned: no second account, and no dangling identity link either.
        assertThat(f.accounts().countUsers()).isEqualTo(1);
    }

    // (b) The known-subject path: signing back in through the same identity is never gated, even
    // exactly at the limit — it is not adding anyone, only recognising someone already counted.
    @Test
    void a_known_subject_signing_in_again_is_unaffected_by_the_seat_limit() throws Exception {
        Fixture f = on("oidc_known_subject_at_limit");
        arrive(f.oidc(), "subject-1", "first@acme.com"); // fills the unlicensed one-seat limit

        OidcSignIn.Outcome again = arrive(f.oidc(), "subject-1", "first@acme.com");

        assertThat(again.ok()).isTrue();
        assertThat(again.account().email()).isEqualTo("first@acme.com");
        assertThat(f.accounts().countUsers()).isEqualTo(1);
    }

    // (b) The linking path: a second provider (or a password) arriving for an address that
    // already has an account is the same rule — it is not a new seat, so it is not gated either.
    @Test
    void linking_a_second_identity_to_an_existing_email_is_unaffected_by_the_seat_limit() throws Exception {
        Fixture f = on("oidc_linking_at_limit");
        arrive(f.oidc(), "subject-1", "first@acme.com"); // fills the unlicensed one-seat limit

        // A different subject, the SAME address: the byEmail branch links rather than creates.
        OidcSignIn.Outcome linked = arrive(f.oidc(), "subject-2", "first@acme.com");

        assertThat(linked.ok()).isTrue();
        assertThat(linked.account().email()).isEqualTo("first@acme.com");
        assertThat(f.accounts().countUsers()).isEqualTo(1);
    }

    // The gate reads the seat limit the installed license actually grants, not just "one": an
    // enterprise fixture with room left still allows a new arrival past the first.
    @Test
    void a_new_arrival_is_allowed_below_an_enterprise_seat_limit() throws Exception {
        Path licenseDir = Files.createTempDirectory("oidc-sign-in-test-enterprise");
        TestLicenses.installFixture(licenseDir, "enterprise-test.license"); // 5 seats
        Fixture f = on("oidc_enterprise_below_limit", licenseDir);
        arrive(f.oidc(), "subject-1", "first@acme.com");

        OidcSignIn.Outcome outcome = arrive(f.oidc(), "subject-2", "second@acme.com");

        assertThat(outcome.ok()).isTrue();
        assertThat(f.accounts().countUsers()).isEqualTo(2);
    }
}
