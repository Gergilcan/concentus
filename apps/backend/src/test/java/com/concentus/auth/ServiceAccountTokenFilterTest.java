package com.concentus.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A machine's token becoming a principal.
 *
 * <p>What the tests are about is the grant: that a working token is exactly its account — its
 * organization, its role, and no higher — that a revoked or unknown one is refused outright rather
 * than allowed to continue as nobody, and that the bookkeeping never costs a request.
 */
class ServiceAccountTokenFilterTest {

    private static final String TOKEN = ServiceAccount.mintToken();

    private final ServiceAccountStore store = mock(ServiceAccountStore.class);
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final ServiceAccountTokenFilter filter =
            new ServiceAccountTokenFilter(store, new ObjectMapper(), now::get);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static ServiceAccount account(String role, Long revokedAt) {
        return new ServiceAccount("sa_1", "org_acme", "nightly-report", role, ServiceAccount.hash(TOKEN),
                "gerard@tecnovent.com", 0L, null, revokedAt);
    }

    /** What the chain sees, and what the response was; the principal exists only inside the chain. */
    private record Outcome(Authentication seen, boolean reachedChain, MockHttpServletResponse response) {
    }

    private Outcome run(String method, String path, String presented) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (presented != null) request.addHeader("Authorization", "Bearer " + presented);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication[] seen = new Authentication[1];
        boolean[] reached = new boolean[1];
        FilterChain chain = (req, res) -> {
            reached[0] = true;
            seen[0] = SecurityContextHolder.getContext().getAuthentication();
        };
        filter.doFilter(request, response, chain);
        return new Outcome(seen[0], reached[0], response);
    }

    @Test
    void a_working_token_authenticates_as_its_account_with_its_role_and_organization() throws Exception {
        when(store.findByTokenHash(ServiceAccount.hash(TOKEN))).thenReturn(Optional.of(account("OPERATOR", null)));

        Outcome out = run("POST", "/api/runs", TOKEN);

        assertThat(out.reachedChain()).isTrue();
        ConcentusUserDetails principal = (ConcentusUserDetails) out.seen().getPrincipal();
        assertThat(principal.organizationId()).isEqualTo("org_acme");
        assertThat(principal.role()).isEqualTo("OPERATOR");
        assertThat(principal.userId()).isEqualTo("sa_1");
        assertThat(principal.email()).contains("nightly-report").contains("service account");
        assertThat(out.seen().getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OPERATOR");
    }

    // OrgContext is what every endpoint scopes by, and it reads the principal — so the account is
    // a real caller there, unlike the shell's bare name.
    @Test
    void and_org_context_sees_it_as_a_real_caller_that_is_not_an_admin() throws Exception {
        when(store.findByTokenHash(anyString())).thenReturn(Optional.of(account("MEMBER", null)));
        OrgContext orgContext = new OrgContext("default");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/flows");
        request.addHeader("Authorization", "Bearer " + TOKEN);

        String[] org = new String[1];
        boolean[] admin = new boolean[1];
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            org[0] = orgContext.requireOrganizationId();
            admin[0] = orgContext.isAdmin();
        });

        assertThat(org[0]).isEqualTo("org_acme");
        assertThat(admin[0]).isFalse();
    }

    // A token must never be able to mint tokens. The controller refuses ADMIN when a row is made;
    // this is the second lock, for a row promoted by hand in the database.
    @Test
    void the_role_ceiling_holds_even_for_a_row_that_says_admin() throws Exception {
        when(store.findByTokenHash(anyString())).thenReturn(Optional.of(account("ADMIN", null)));

        Outcome out = run("POST", "/api/service-accounts", TOKEN);

        assertThat(((ConcentusUserDetails) out.seen().getPrincipal()).role()).isEqualTo(Accounts.ROLE_MEMBER);
        assertThat(out.seen().getAuthorities()).extracting(Object::toString).containsExactly("ROLE_MEMBER");
    }

    @Test
    void a_revoked_token_is_401_and_the_request_goes_no_further() throws Exception {
        when(store.findByTokenHash(anyString())).thenReturn(Optional.of(account("OPERATOR", 5L)));

        Outcome out = run("POST", "/api/runs", TOKEN);

        assertThat(out.reachedChain()).isFalse();
        assertThat(out.response().getStatus()).isEqualTo(401);
        assertThat(out.response().getContentAsString()).contains("revoked");
        verify(store, never()).touchLastUsed(anyString(), anyLong());
    }

    @Test
    void an_unknown_token_is_401_too() throws Exception {
        when(store.findByTokenHash(anyString())).thenReturn(Optional.empty());

        Outcome out = run("GET", "/api/flows", ServiceAccount.mintToken());

        assertThat(out.reachedChain()).isFalse();
        assertThat(out.response().getStatus()).isEqualTo(401);
        assertThat(out.response().getContentAsString()).contains("Unknown service account token");
    }

    // Anything not shaped like one of ours is not this filter's business: the session, the shell
    // token and a published flow's token all travel as bearers too, and each has its own reader.
    @Test
    void a_bearer_that_is_not_a_service_account_token_is_left_alone() throws Exception {
        Outcome plain = run("GET", "/api/flows", "3f1c2b6e-0d7a-4c11-9a58-3d4a1c9e7b20");
        Outcome none = run("GET", "/api/flows", null);
        Outcome shortOne = run("GET", "/api/flows", "csa_tooshort");

        assertThat(plain.reachedChain()).isTrue();
        assertThat(plain.seen()).isNull();
        assertThat(none.seen()).isNull();
        assertThat(shortOne.reachedChain()).isTrue();
        assertThat(shortOne.seen()).isNull();
        verify(store, never()).findByTokenHash(anyString());
        assertThat(filter.presentsToken(new MockHttpServletRequest())).isFalse();
    }

    @Test
    void the_shape_check_the_csrf_exemption_rests_on_needs_no_lookup() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/runs");
        request.addHeader("Authorization", "Bearer " + TOKEN);

        assertThat(filter.presentsToken(request)).isTrue();
        verify(store, never()).findByTokenHash(anyString());
    }

    // A pipeline polling a run every second is one row write a minute, not sixty.
    @Test
    void last_used_is_written_at_most_once_a_minute_per_account() throws Exception {
        when(store.findByTokenHash(anyString())).thenReturn(Optional.of(account("OPERATOR", null)));

        run("GET", "/api/runs/r1", TOKEN);
        now.addAndGet(10_000);
        run("GET", "/api/runs/r1", TOKEN);
        now.addAndGet(40_000);
        run("GET", "/api/runs/r1", TOKEN);
        verify(store, times(1)).touchLastUsed(eq("sa_1"), anyLong());

        now.addAndGet(10_001);   // past the minute
        run("GET", "/api/runs/r1", TOKEN);
        verify(store, times(2)).touchLastUsed(eq("sa_1"), anyLong());
    }

    // The SPA served by the same backend: a signed-in person on the same route keeps their own
    // identity, and their own role, rather than being answered for by a token.
    @Test
    void a_signed_in_caller_is_never_replaced() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("viewer@example.com", null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + Accounts.ROLE_VIEWER))));

        Outcome out = run("GET", "/api/flows", TOKEN);

        assertThat(out.seen().getName()).isEqualTo("viewer@example.com");
        verify(store, never()).findByTokenHash(anyString());
    }

    // Container threads are pooled, so a principal left on one would be the next request's.
    @Test
    void the_grant_does_not_outlive_the_request() throws Exception {
        when(store.findByTokenHash(anyString())).thenReturn(Optional.of(account("OPERATOR", null)));

        run("GET", "/api/flows", TOKEN);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void a_token_is_prefixed_random_and_never_stored_as_itself() {
        String a = ServiceAccount.mintToken();
        String b = ServiceAccount.mintToken();

        assertThat(a).startsWith("csa_").hasSize(44).matches("csa_[A-Za-z0-9]{40}");
        assertThat(a).isNotEqualTo(b);
        assertThat(ServiceAccount.hash(a)).hasSize(64).isNotEqualTo(ServiceAccount.hash(b)).doesNotContain(a);
        assertThat(ServiceAccount.looksLikeToken(a)).isTrue();
        assertThat(ServiceAccount.looksLikeToken("csa_" + a)).isFalse();
    }
}
