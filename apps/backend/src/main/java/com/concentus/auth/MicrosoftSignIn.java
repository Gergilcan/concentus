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
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signing in with a Microsoft work or school account (Entra ID).
 *
 * <p>The authorization-code flow, driven from here rather than through Spring's OAuth2 client, for
 * the same reason the MCP and credential flows are: this application is a single-page app talking
 * to its own API, and the redirect-and-filter machinery that starter brings assumes a server-rendered
 * login it would then have to be argued out of. What is left is small and explicit — a state value
 * this process issued, a code exchanged over TLS with the client secret, and an identity read from
 * Graph.
 *
 * <p><b>The identity comes from Graph, not from the browser.</b> The token arrives in our own TLS
 * response to our own exchange, and the address is then read by calling Microsoft with it. Nothing
 * the browser sends is trusted to say who the person is, which is the property that makes skipping
 * local JWT validation sound rather than lazy.
 *
 * <p><b>People are matched by their directory id, never by address.</b> Addresses are reassigned
 * when people leave a company; matching on one would eventually hand a leaver's flows, and their
 * role, to whoever inherited the mailbox.
 */
@Service
public class MicrosoftSignIn {

    private static final Logger log = LoggerFactory.getLogger(MicrosoftSignIn.class);

    public static final String CALLBACK_PATH = "/api/account/oidc/microsoft/callback";
    public static final String PROVIDER = "microsoft";

    /** Long enough to sign in and approve consent, short enough that an abandoned attempt expires. */
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);
    private static final String GRAPH_ME = "https://graph.microsoft.com/v1.0/me";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private final AccountStore accounts;
    private final UserIdentityStore identities;
    private final EmailDomainPolicy domains;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private final String tenant;
    private final String clientId;
    private final String clientSecret;
    private final String defaultRole;
    private final String organizationId;

    public MicrosoftSignIn(AccountStore accounts, UserIdentityStore identities,
                           EmailDomainPolicy domains, ObjectMapper mapper,
                           @Value("${app.auth.microsoft.enabled:false}") boolean enabled,
                           @Value("${app.auth.microsoft.tenant:organizations}") String tenant,
                           @Value("${app.auth.microsoft.client-id:}") String clientId,
                           @Value("${app.auth.microsoft.client-secret:}") String clientSecret,
                           @Value("${app.auth.microsoft.default-role:VIEWER}") String defaultRole,
                           @Value("${app.organization-id:default}") String organizationId) {
        this.accounts = accounts;
        this.identities = identities;
        this.domains = domains;
        this.mapper = mapper;
        this.enabled = enabled;
        this.tenant = tenant == null || tenant.isBlank() ? "organizations" : tenant.trim();
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.defaultRole = defaultRole;
        this.organizationId = organizationId;
    }

    /**
     * Whether the button should exist at all. Configured means all three of on, a client id and a
     * secret — an "enabled" provider missing its credentials is a button that fails, which is worse
     * than one that is absent.
     */
    public boolean isConfigured() {
        return enabled && !clientId.isBlank() && !clientSecret.isBlank();
    }

    private record Pending(String redirectUri, Instant expiresAt) {
    }

    /** Where to send the browser, and the state this process will accept back. */
    public String authorizationUrl(String requestBase) {
        String redirectUri = requestBase + CALLBACK_PATH;
        String state = HexFormat.of().formatHex(randomBytes(24));
        sweepExpired();
        pending.put(state, new Pending(redirectUri, Instant.now().plus(PENDING_TTL)));

        return "https://login.microsoftonline.com/" + encode(tenant) + "/oauth2/v2.0/authorize"
                + "?client_id=" + encode(clientId)
                + "&response_type=code"
                + "&redirect_uri=" + encode(redirectUri)
                + "&response_mode=query"
                // User.Read is what makes the Graph lookup below possible; the rest is the identity
                // itself. Nothing here asks for mail, files or anything a person would be right to
                // hesitate over on the consent screen.
                + "&scope=" + encode("openid profile email User.Read")
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
     * Completes the flow: validates the state, exchanges the code, reads the person from Graph,
     * applies the domain policy, and finds or creates their account.
     */
    public Outcome complete(String code, String state, String error) {
        if (error != null && !error.isBlank()) {
            return Outcome.failed("Microsoft refused the sign-in: " + error);
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
            JsonNode me = graphMe(accessToken);
            // mail is the real address when the directory has one; userPrincipalName is what every
            // account has. A guest account's UPN is mangled (name_company.com#EXT#@...), which is
            // exactly why mail comes first.
            String email = text(me, "mail");
            if (email == null) email = text(me, "userPrincipalName");
            String subject = text(me, "id");
            if (email == null || subject == null) {
                return Outcome.failed("Microsoft did not return an address for this account.");
            }
            if (!domains.allows(email)) {
                log.info("Sign-in refused for {} — domain not allowed", email);
                return Outcome.failed("Addresses at " + EmailDomainPolicy.domainOf(email)
                        + " cannot sign in to this deployment.");
            }
            return new Outcome(resolveAccount(subject, email), null);
        } catch (RuntimeException e) {
            log.warn("Microsoft sign-in failed: {}", e.getMessage());
            return Outcome.failed("Microsoft sign-in could not be completed: " + e.getMessage());
        }
    }

    /**
     * The account this directory identity belongs to, creating one the first time.
     *
     * <p>Three cases, in the order they must be asked. A known directory id is the person, whatever
     * their address is today. An unknown id whose address already has a password account is the
     * same human arriving a second way, so the identity is linked rather than a duplicate created —
     * with their existing role, which is the point of linking. Neither means a new account, at the
     * role a first-time arrival gets.
     */
    private Accounts.UserAccount resolveAccount(String subject, String email) {
        Optional<Accounts.UserAccount> linked = identities.find(PROVIDER, subject)
                .flatMap(id -> accounts.findById(id.userId()));
        if (linked.isPresent()) return linked.get();

        Optional<Accounts.UserAccount> byEmail = accounts.findByEmail(email);
        if (byEmail.isPresent()) {
            identities.link(PROVIDER, subject, byEmail.get().id(), byEmail.get().organizationId(), email);
            return byEmail.get();
        }

        String role = Accounts.normalizeRole(defaultRole);
        // A misconfigured role must not silently become an admin, and must not lock the person out
        // either: the safest reading of an unrecognised value is the lowest rung.
        if (role == null) role = Accounts.ROLE_VIEWER;
        // No password: this account cannot be signed into with one. A random hash rather than an
        // empty column, so nothing can ever match it by accident.
        Accounts.UserAccount created = accounts.createUser(organizationId, email,
                "{noop-external}" + HexFormat.of().formatHex(randomBytes(32)), role);
        identities.link(PROVIDER, subject, created.id(), created.organizationId(), email);
        log.info("Provisioned {} from Microsoft sign-in as {}", email, role);
        return created;
    }

    private String exchange(String code, String redirectUri) {
        String body = "client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret)
                + "&grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode("openid profile email User.Read");
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("https://login.microsoftonline.com/" + encode(tenant) + "/oauth2/v2.0/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        JsonNode json = send(request, "the token exchange");
        String token = text(json, "access_token");
        if (token == null) throw new IllegalStateException("no access token in the response");
        return token;
    }

    private JsonNode graphMe(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(GRAPH_ME))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return send(request, "reading the account from Microsoft Graph");
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

    /** Base64 without padding, for the few places Microsoft's documentation uses it. */
    static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
