package com.concentus.auth;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The desktop shell's credential for the first-run wizard.
 *
 * <p>What the tests are about is the shape of the grant, not the comparison: the wizard asks which
 * database to use before an account exists, so something has to answer for it — and the whole
 * safety of that answer is in how narrow it is. Three routes, one token, no account.
 */
class ShellTokenFilterTest {

    private static final String TOKEN = "a-launch-token";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** The principal seen INSIDE the chain — the only place the filter's grant exists. */
    private static Authentication run(ShellTokenFilter filter, String method, String path,
                                      String presented) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (presented != null) request.addHeader("Authorization", "Bearer " + presented);
        Authentication[] seen = new Authentication[1];
        FilterChain chain = (req, res) -> seen[0] = SecurityContextHolder.getContext().getAuthentication();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return seen[0];
    }

    @Test
    void the_three_calls_the_wizard_makes_are_authenticated_by_the_launch_token() throws Exception {
        ShellTokenFilter filter = new ShellTokenFilter(TOKEN);

        assertThat(run(filter, "GET", "/api/storage", TOKEN)).isNotNull();
        assertThat(run(filter, "PUT", "/api/storage", TOKEN)).isNotNull();
        assertThat(run(filter, "POST", "/api/storage/test", TOKEN)).isNotNull();
    }

    // The rule guarding those routes is hasAnyRole(MEMBER, ADMIN); MEMBER is the least that passes
    // it, and asking for more would be granting more than the wizard needs.
    @Test
    void and_granted_the_least_role_those_routes_accept() throws Exception {
        Authentication auth = run(new ShellTokenFilter(TOKEN), "PUT", "/api/storage", TOKEN);

        assertThat(auth.getAuthorities()).extracting(Object::toString)
                .containsExactly("ROLE_" + Accounts.ROLE_MEMBER);
    }

    /**
     * The point of the bare principal: it is not an account, so everything that asks WHO is calling
     * refuses it — {@code /api/storage/contents} and {@code /api/storage/migrate} among them, which
     * read and copy the stored data itself.
     */
    @Test
    void the_caller_is_never_an_account_so_admin_only_work_still_refuses_it() throws Exception {
        OrgContext orgContext = new OrgContext("local");
        ShellTokenFilter filter = new ShellTokenFilter(TOKEN);
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/storage");
        request.addHeader("Authorization", "Bearer " + TOKEN);

        boolean[] inside = new boolean[2];
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            inside[0] = orgContext.currentUser().isPresent();
            inside[1] = orgContext.isAdmin();
        });

        assertThat(inside[0]).isFalse();
        assertThat(inside[1]).isFalse();
    }

    @Test
    void a_wrong_or_missing_token_authenticates_nothing() throws Exception {
        ShellTokenFilter filter = new ShellTokenFilter(TOKEN);

        assertThat(run(filter, "PUT", "/api/storage", "a-launch-toke")).isNull();
        assertThat(run(filter, "PUT", "/api/storage", "")).isNull();
        assertThat(run(filter, "PUT", "/api/storage", null)).isNull();
    }

    // A leaked token must not become an API key, so the grant is a list of routes rather than a
    // caller identity: everything the wizard does not call is outside it, including the two storage
    // endpoints that touch the data.
    @Test
    void it_answers_for_the_wizards_routes_and_no_others() throws Exception {
        ShellTokenFilter filter = new ShellTokenFilter(TOKEN);

        assertThat(run(filter, "POST", "/api/storage/migrate", TOKEN)).isNull();
        assertThat(run(filter, "GET", "/api/storage/contents", TOKEN)).isNull();
        assertThat(run(filter, "DELETE", "/api/storage", TOKEN)).isNull();
        assertThat(run(filter, "GET", "/api/flows", TOKEN)).isNull();
        assertThat(run(filter, "POST", "/api/runs", TOKEN)).isNull();
    }

    // A server deployment, or a dev backend the shell merely adopted: nothing was configured, so
    // there is nothing to present and these endpoints need a signed-in account like everything else.
    @Test
    void with_no_token_configured_the_whole_thing_is_off() throws Exception {
        assertThat(run(new ShellTokenFilter(""), "PUT", "/api/storage", TOKEN)).isNull();
        assertThat(run(new ShellTokenFilter(null), "PUT", "/api/storage", "")).isNull();
    }

    // The desktop build also serves the SPA, where the same endpoints are reached by a signed-in
    // person. That request keeps its own identity — and its own role — rather than being answered
    // for by the shell.
    @Test
    void a_signed_in_caller_is_never_replaced() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("viewer@example.com", null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + Accounts.ROLE_VIEWER))));

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/storage");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        Authentication[] seen = new Authentication[1];
        new ShellTokenFilter(TOKEN).doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen[0] = SecurityContextHolder.getContext().getAuthentication());

        assertThat(seen[0].getName()).isEqualTo("viewer@example.com");
    }

    // Container threads are pooled, so a principal left on one would be the next request's.
    @Test
    void the_grant_does_not_outlive_the_request() throws Exception {
        run(new ShellTokenFilter(TOKEN), "PUT", "/api/storage", TOKEN);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
