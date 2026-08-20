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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Signing in with an identity provider the organization already has.
 *
 * <p>Provider-agnostic on purpose. Anything that speaks OpenID Connect works — Entra ID, Google,
 * Auth0, a self-hosted Keycloak — because which supplier holds a company's identities is their
 * decision and not something this application should have baked in. The endpoints are discovered
 * from the issuer, so configuration is a preset (or an issuer), a client id and a secret.
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
 * whoever inherited the mailbox.
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
    /** Discovered once and kept: an issuer's endpoints do not move between requests. */
    private final AtomicReference<Endpoints> endpoints = new AtomicReference<>();

    private final AccountStore accounts;
    private final UserIdentityStore identities;
    private final EmailDomainPolicy domains;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final OidcProvider provider;
    private final String clientId;
    private final String clientSecret;
    private final String defaultRole;
    private final String organizationId;

    public OidcSignIn(AccountStore accounts, UserIdentityStore identities,
                      EmailDomainPolicy domains, ObjectMapper mapper,
                      @Value("${app.auth.oidc.enabled:false}") boolean enabled,
                      @Value("${app.auth.oidc.provider:microsoft}") String preset,
                      @Value("${app.auth.oidc.issuer:}") String issuer,
                      @Value("${app.auth.oidc.tenant:organizations}") String tenant,
                      @Value("${app.auth.oidc.display-name:}") String displayName,
                      @Value("${app.auth.oidc.scope:}") String scope,
                      @Value("${app.auth.oidc.client-id:}") String clientId,
                      @Value("${app.auth.oidc.client-secret:}") String clientSecret,
                      @Value("${app.auth.oidc.default-role:VIEWER}") String defaultRole,
                      @Value("${app.organization-id:default}") String organizationId) {
        this.accounts = accounts;
        this.identities = identities;
        this.domains = domains;
        this.mapper = mapper;
        this.enabled = enabled;
        this.provider = OidcProvider.of(preset, issuer, tenant, displayName, scope);
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.defaultRole = defaultRole;
        this.organizationId = organizationId;
    }

    /**
     * Whether the button should exist at all: switched on, with an issuer, a client id and a
     * secret. An "enabled" provider missing its credentials is a button that fails, which is worse
     * than one that is absent.
     */
    public boolean isConfigured() {
        return enabled && provider.isUsable() && !clientId.isBlank() && !clientSecret.isBlank();
    }

    /** What to write on the button. */
    public String displayName() {
        return provider.displayName();
    }

    private record Pending(String redirectUri, Instant expiresAt) {
    }

    /** The three endpoints this flow needs, as the provider itself publishes them. */
    record Endpoints(String authorization, String token, String userinfo) {
    }

    /** Where to send the browser, and the state this process will accept back. */
    public String authorizationUrl(String requestBase) {
        String redirectUri = requestBase + CALLBACK_PATH;
        String state = HexFormat.of().formatHex(randomBytes(24));
        sweepExpired();
        pending.put(state, new Pending(redirectUri, Instant.now().plus(PENDING_TTL)));

        return discover().authorization()
                + (discover().authorization().contains("?") ? "&" : "?")
                + "client_id=" + encode(clientId)
                + "&response_type=code"
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_mode=query"
                + "&scope=" + encode(provider.scope())
                + "&state=" + encode(state);
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
        if (error != null && !error.isBlank()) {
            return Outcome.failed(provider.displayName() + " refused the sign-in: " + error);
        }
        if (code == null || code.isBlank() || state == null) {
            return Outcome.failed("The sign-in did not come back with a code.");
        }
        Pending started = pending.remove(state);
        if (started == null || started.expiresAt().isBefore(Instant.now())) {
            // Either a state this process never issued, or one from an attempt left open too long.
            // Both answer the same way: start again. Distinguishing them tells an attacker which.
            return Outcome.failed("This sign-in link is no longer valid. Try again.");
        }

        try {
            String accessToken = exchange(code, started.redirectUri());
            JsonNode me = userinfo(accessToken);
            String subject = text(me, "sub");
            // email where the directory has one; preferred_username is the UPN on Entra and is
            // what a work account always carries. Both are standard OIDC claims, which is why no
            // provider-specific API is called here any more.
            String email = text(me, "email");
            if (email == null) email = text(me, "preferred_username");
            if (subject == null || email == null || !email.contains("@")) {
                return Outcome.failed(provider.displayName()
                        + " did not return an address for this account.");
            }
            if (!domains.allows(email)) {
                log.info("Sign-in refused for {} — domain not allowed", email);
                return Outcome.failed("Addresses at " + EmailDomainPolicy.domainOf(email)
                        + " cannot sign in to this deployment.");
            }
            return new Outcome(resolveAccount(subject, email), null);
        } catch (RuntimeException e) {
            log.warn("Sign-in through {} failed: {}", provider.id(), e.getMessage());
            return Outcome.failed("Sign-in could not be completed: " + e.getMessage());
        }
    }

    /**
     * The account this identity belongs to, creating one the first time.
     *
     * <p>Three cases, in the order they must be asked. A known subject is the person, whatever
     * their address is today. An unknown subject whose address already has a password account is
     * the same human arriving a second way, so the identity is linked rather than a duplicate
     * created — with their existing role, which is the point of linking. Neither means a new
     * account, at the role a first-time arrival gets.
     */
    private Accounts.UserAccount resolveAccount(String subject, String email) {
        Optional<Accounts.UserAccount> linked = identities.find(provider.id(), subject)
                .flatMap(id -> accounts.findById(id.userId()));
        if (linked.isPresent()) return linked.get();

        Optional<Accounts.UserAccount> byEmail = accounts.findByEmail(email);
        if (byEmail.isPresent()) {
            identities.link(provider.id(), subject, byEmail.get().id(),
                    byEmail.get().organizationId(), email);
            return byEmail.get();
        }

        String role = Accounts.normalizeRole(defaultRole);
        // A misconfigured role must not silently become an admin, and must not lock the person out
        // either: the safest reading of an unrecognised value is the lowest rung.
        if (role == null) role = Accounts.ROLE_VIEWER;
        // No usable password: this account cannot be signed into with one. A random value rather
        // than an empty column, so nothing can ever match it by accident.
        Accounts.UserAccount created = accounts.createUser(organizationId, email,
                "{external}" + HexFormat.of().formatHex(randomBytes(32)), role);
        identities.link(provider.id(), subject, created.id(), created.organizationId(), email);
        log.info("Provisioned {} from {} sign-in as {}", email, provider.id(), role);
        return created;
    }

    /**
     * The provider's endpoints, from its own discovery document.
     *
     * <p>Fetched once. Three URLs copied by hand into configuration are three chances to paste the
     * wrong one, and the symptom of a wrong token endpoint is a sign-in that fails after the person
     * has already typed their password.
     */
    Endpoints discover() {
        Endpoints known = endpoints.get();
        if (known != null) return known;
        JsonNode document = send(HttpRequest.newBuilder(URI.create(provider.discoveryUrl()))
                .timeout(Duration.ofSeconds(20)).GET().build(), "reading the provider's configuration");
        Endpoints found = new Endpoints(
                require(document, "authorization_endpoint"),
                require(document, "token_endpoint"),
                require(document, "userinfo_endpoint"));
        endpoints.set(found);
        return found;
    }

    private String exchange(String code, String redirectUri) {
        String body = "client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(provider.scope());
        HttpRequest request = HttpRequest.newBuilder(URI.create(discover().token()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        JsonNode json = send(request, "the token exchange");
        String token = text(json, "access_token");
        if (token == null) throw new IllegalStateException("no access token in the response");
        return token;
    }

    private JsonNode userinfo(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(discover().userinfo()))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return send(request, "reading the account from " + provider.displayName());
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

    private static String require(JsonNode document, String field) {
        String value = text(document, field);
        if (value == null) {
            throw new IllegalStateException("the provider's configuration has no " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
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
