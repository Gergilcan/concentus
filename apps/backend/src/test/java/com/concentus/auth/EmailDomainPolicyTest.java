package com.concentus.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which addresses may hold an account.
 *
 * <p>A directory sign-in proves who somebody is, not that they belong in this deployment: with a
 * multi-directory tenant, a valid Microsoft account from any company in the world otherwise
 * reaches the sign-in screen and gets in.
 */
class EmailDomainPolicyTest {

    private static EmailDomainPolicy policy(String configured) {
        return new EmailDomainPolicy(configured);
    }

    @Test
    void an_empty_list_allows_everyone_which_is_the_single_company_install() {
        EmailDomainPolicy open = policy("");

        assertThat(open.allowedDomains()).isEmpty();
        assertThat(open.allows("anyone@anywhere.example")).isTrue();
    }

    @Test
    void only_the_listed_domains_get_in() {
        EmailDomainPolicy p = policy("tecnovent.com, concentus.app");

        assertThat(p.allows("gerard@tecnovent.com")).isTrue();
        assertThat(p.allows("someone@concentus.app")).isTrue();
        assertThat(p.allows("someone@gmail.com")).isFalse();
    }

    // The lookalike that a naive "ends with" check lets through.
    @Test
    void a_domain_that_merely_starts_the_same_is_not_the_domain() {
        EmailDomainPolicy p = policy("company.com");

        assertThat(p.allows("victim@company.com.attacker.net")).isFalse();
        assertThat(p.allows("victim@notcompany.com")).isFalse();
    }

    @Test
    void case_and_an_at_sign_are_how_people_write_it_and_both_work() {
        EmailDomainPolicy p = policy("@Tecnovent.COM");

        assertThat(p.allowedDomains()).containsExactly("tecnovent.com");
        assertThat(p.allows("Gerard@TECNOVENT.com")).isTrue();
    }

    @Test
    void an_address_that_is_not_one_is_refused_rather_than_parsed_generously() {
        EmailDomainPolicy p = policy("company.com");

        assertThat(p.allows("no-at-sign")).isFalse();
        assertThat(p.allows("trailing@")).isFalse();
        assertThat(p.allows(null)).isFalse();
    }
}
