package com.concentus.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signing in with an identity provider somebody already has.
 *
 * <p>Provider-agnostic, and more than one at a time. Which supplier holds a company's identities
 * is their decision, and often it is several: the staff are on the corporate directory, the
 * contractor auditing them has a Google account, and whoever installed the thing wants a
 * password. Endpoints are discovered from the issuer where the provider publishes them, so
 * configuring one is a preset, a client id and a secret.
 *
 * <p>The authorization-code flow, driven from here rather than through Spring's OAuth2 client:
 * this is a single-page app talking to its own API, and that starter's redirect-and-filter
 * machinery assumes a server-rendered login it would then have to be argued out of. What is left
 * is small enough to read — a state value this process issued, a code exchanged over TLS with the
 * client secret, and an identity read from the provider's userinfo endpoint.
 *
 * <p><b>The identity comes from the provider, not from the browser.</b> The token arrives in our
 * own TLS response to our own exchange, and the address is then read by asking the provider with
 * it. Nothing the browser sends is trusted to say who the person is, which is what makes skipping
 * local JWT validation sound rather than lazy.
 *
 * <p><b>People are matched by subject, never by address.</b> Addresses are reassigned when people
 * leave a company; matching on one would eventually hand a leaver's flows, and their role, to
 * whoever inherited the mailbox. The subject is scoped by provider, so the same address arriving
 * through two directories is two identities linked to one account rather than a collision.
 */
@Service
public class OidcSignIn {

    private static final Logger log = LoggerFactory.getLogger(OidcSignIn.class);

    public static final String CALLBACK_PATH = "/api/account/oidc/callback";

    /** Long enough to sign in and approve consent, short enough that an abandoned attempt expires. */
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** Discovered once per provider and kept: an issuer's endpoints do not move between requests. */
    private final Map<String, Endpoints> endpoints = new ConcurrentHashMap<>();

    private final AccountStore accounts;
    private final UserIdentityStore identities;
    private final EmailDomainPolicy domains;
    private final ObjectMapper mapper;
    private final OidcRegistry registry;
    private final String organizationId;

    public OidcSignIn(AccountStore accounts, UserIdentityStore identities,
                      EmailDomainPolicy domains, ObjectMapper mapper, OidcRegistry registry,
                      @Value("${app.organization-id:default}") String organizationId) {
        this.accounts = accounts;
        this.identities = identities;
        this.domains = domains;
        this.mapper = mapper;
        this.registry = registry;
        this.organizationId = organizationId;
    }

    /** Whether any provider is configured at all — whether there is a button to show. */
    public boolean isConfigured() {
        return registry.any();
    }

    /** Every provider somebody may sign in with, for the buttons on the sign-in screen. */
    public List<OidcRegistry.Configured> providers() {
        return registry.all();
    }

    /**
     * What to write on the button when there is one.
     *
     * <p>Kept for the single-provider answer the session endpoint has always given. With several
     * configured it names the first, which is the one a caller that only understands one would
     * have used anyway.
     */
    public String displayName() {
        return registry.all().stream().findFirst()
                .map(OidcRegistry.Configured::displayName).orElse("your organization");
    }

    /** A sign-in that has left for a provider and not come back yet. */
    private record Pending(String providerId, String redirectUri, Instant expiresAt) {
    }

    /** The three endpoints this flow needs, as the provider itself publishes them. */
    record Endpoints(String authorization, String token, String userinfo) {
    }

    /**
     * Where to send the browser, and the state this process will accept back.
     *
     * <p>The state names the provider as well as proving the attempt is ours: the callback is one
     * URL for all of them — every provider's registration points at it — so what comes back has to
     * say which conversation it belongs to, and it must be this process that said so rather than
     * the request.
     */
    public String authorizationUrl(String providerId, String requestBase) {
        OidcRegistry.Configured configured = require(providerId);
        String redirectUri = requestBase + CALLBACK_PATH;
        String state = HexFormat.of().formatHex(randomBytes(24));
        sweepExpired();
        pending.put(state, new Pending(configured.id(), redirectUri, Instant.now().plus(PENDING_TTL)));

        String authorization = endpointsOf(configured).authorization();
        return authorization
                + (authorization.contains("?") ? "&" : "?")
                + "client_id=" + encode(configured.clientId())
                + "&response_type=code"
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_mode=query"
                + "&scope=" + encode(configured.provider().scope())
                + "&state=" + encode(state);
    }

    private OidcRegistry.Configured require(String providerId) {
        return registry.byId(providerId).orElseThrow(() -> new IllegalArgumentException(
                "No sign-in provider called '" + providerId + "' is configured here."));
    }

    /** What the callback produced: an account to sign in as, or a reason it did not. */
    public record Outcome(Accounts.UserAccount account, String error) {

        public boolean ok() {
            return account != null;
        }

        static Outcome failed(String reason) {
            return new Outcome(null, reason);
        }
    }

    /**
     * Completes the flow: validates the state, exchanges the code, reads the person from the
     * provider, applies the domain policy, and finds or creates their account.
     */
    public Outcome complete(String code, String state, String error) {
        Pending started = state == null ? null : pending.remove(state);
        if (started == null || started.expiresAt().isBefore(Instant.now())) {
            // Either a state this process never issued, or one from an attempt left open too long.
            // Both answer the same way: start again. Distinguishing them tells an attacker which.
            // Checked before the provider's own error, because until this passes there is no
            // knowing which provider the error even came from.
            return Outcome.failed("This sign-in link is no longer valid. Try again.");
        }
        OidcRegistry.Configured configured = registry.byId(started.providerId()).orElse(null);
        if (configured == null) {
            return Outcome.failed("That sign-in provider is no longer configured here.");
        }
        if (error != null && !error.isBlank()) {
            return Outcome.failed(configured.displayName() + " refused the sign-in: " + error);
        }
        if (code == null || code.isBlank()) {
            return Outcome.failed("The sign-in did not come back with a code.");
        }

        OidcProvider provider = configured.provider();
        try {
            String accessToken = exchange(configured, code, started.redirectUri());
            JsonNode me = userinfo(configured, accessToken);
            String subject = text(me, provider.subjectClaim());
            // The configured claim, then the OIDC standard fallbacks: preferred_username is the
            // UPN on Entra and is what a work account always carries when email is absent.
            String email = text(me, provider.emailClaim());
            if (email == null) email = text(me, "preferred_username");
            if (subject == null || email == null || !email.contains("@")) {
                return Outcome.failed(configured.displayName()
                        + " did not return an address for this account.");
            }
            if (!domains.allows(email)) {
                log.info("Sign-in refused for {} — domain not allowed", email);
                return Outcome.failed("Addresses at " + EmailDomainPolicy.domainOf(email)
                        + " cannot sign in to this deployment.");
            }
            return new Outcome(resolveAccount(configured, subject, email), null);
        } catch (RuntimeException e) {
            log.warn("Sign-in through {} failed: {}", provider.id(), e.getMessage());
            return Outcome.failed("Sign-in could not be completed: " + e.getMessage());
        }
    }

    /**
     * The account this identity belongs to, creating one the first time.
     *
     * <p>Three cases, in the order they must be asked. A known subject is the person, whatever
     * their address is today. An unknown subject whose address already has an account is the same
     * human arriving a second way — through a second provider, or beside the password they already
     * had — so the identity is linked rather than a duplicate created, with their existing role,
     * which is the point of linking. Neither means a new account, at the role a first-time arrival
     * gets.
     */
    private Accounts.UserAccount resolveAccount(OidcRegistry.Configured configured,
                                                String subject, String email) {
        String providerId = configured.provider().id();
        Optional<Accounts.UserAccount> linked = identities.find(providerId, subject)
                .flatMap(id -> accounts.findById(id.userId()));
        if (linked.isPresent()) return linked.get();

        Optional<Accounts.UserAccount> byEmail = accounts.findByEmail(email);
        if (byEmail.isPresent()) {
            identities.link(providerId, subject, byEmail.get().id(),
                    byEmail.get().organizationId(), email);
            return byEmail.get();
        }

        String role = Accounts.normalizeRole(configured.defaultRole());
        // A misconfigured role must not silently become an admin, and must not lock the person out
        // either: the safest reading of an unrecognised value is the lowest rung.
        if (role == null) role = Accounts.ROLE_VIEWER;
        // Except for the very first account, which administers the installation it just claimed —
        // the same rule the setup screen follows, so signing in with a work account is a way to
        // set the thing up rather than a way to end up locked out as a Viewer with nobody above
        // you. It applies only to a genuinely empty installation; the second arrival is a Viewer
        // like everybody else. Where that window is a risk — a server reachable before anybody has
        // claimed it — CONCENTUS_ADMIN_EMAIL settles the question before the first launch.
        if (accounts.countUsers() == 0) {
            role = Accounts.ROLE_ADMIN;
            log.info("{} is the first account here, so it administers this installation.", email);
        }
        // No usable password: this account cannot be signed into with one. A random value rather
        // than an empty column, so nothing can ever match it by accident.
        Accounts.UserAccount created = accounts.createUser(organizationId, email,
                "{external}" + HexFormat.of().formatHex(randomBytes(32)), role);
        identities.link(providerId, subject, created.id(), created.organizationId(), email);
        log.info("Provisioned {} from {} sign-in as {}", email, providerId, role);
        return created;
    }

    /**
     * A provider's endpoints: as it publishes them, or as configuration states them.
     *
     * <p>Discovery is fetched once. Three URLs copied by hand into configuration are three chances
     * to paste the wrong one, and the symptom of a wrong token endpoint is a sign-in that fails
     * after the person has already typed their password — so it is only done that way for the
     * providers that publish nothing to discover.
     */
    Endpoints endpointsOf(OidcRegistry.Configured configured) {
        OidcProvider provider = configured.provider();
        if (provider.hasStatedEndpoints()) {
            return new Endpoints(provider.authorizationUrl(), provider.tokenUrl(),
                    provider.userinfoUrl());
        }
        return endpoints.computeIfAbsent(provider.id(), id -> {
            JsonNode document = send(HttpRequest.newBuilder(URI.create(provider.discoveryUrl()))
                    .timeout(Duration.ofSeconds(20)).GET().build(),
                    "reading " + configured.displayName() + "'s configuration");
            return new Endpoints(
                    required(document, "authorization_endpoint"),
                    required(document, "token_endpoint"),
                    required(document, "userinfo_endpoint"));
        });
    }

    private String exchange(OidcRegistry.Configured configured, String code, String redirectUri) {
        String body = "client_id=" + encode(configured.clientId())
                + "&client_secret=" + encode(configured.clientSecret())
                + "&grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(configured.provider().scope());
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpointsOf(configured).token()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        JsonNode json = send(request, "the token exchange");
        String token = text(json, "access_token");
        if (token == null) throw new IllegalStateException("no access token in the response");
        return token;
    }

    private JsonNode userinfo(OidcRegistry.Configured configured, String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpointsOf(configured).userinfo()))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return send(request, "reading the account from " + configured.displayName());
    }

    private JsonNode send(HttpRequest request, String what) {
        try {
            HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException(what + " answered " + res.statusCode());
            }
            return mapper.readTree(res.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(what + " was interrupted");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(what + " failed: " + e.getMessage());
        }
    }

    private static String required(JsonNode document, String field) {
        String value = text(document, field);
        if (value == null) {
            throw new IllegalStateException("the provider's configuration has no " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null || field == null ? null : node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        random.nextBytes(bytes);
        return bytes;
    }

    /** Abandoned attempts are dropped rather than accumulating for the life of the process. */
    private void sweepExpired() {
        Instant now = Instant.now();
        pending.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
