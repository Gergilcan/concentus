package com.concentus.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Which email addresses may hold an account here.
 *
 * <p>A directory sign-in proves who somebody is; it does not prove they belong in this
 * deployment. With Entra's {@code organizations} tenant, or any provider that accepts more than
 * one directory, the difference is the whole control: without it, a valid Microsoft account from
 * any company in the world reaches the sign-in screen and gets in.
 *
 * <p>Deliberately applied to every sign-in path rather than only to the provider ones, so it also
 * answers "nobody outside the company" on a deployment still issuing passwords.
 *
 * <p>Configuration, not a column, for now — one deployment is one company, and that is what a
 * self-hosted install is. The shape it would take as a product is per organization, and this class
 * is the seam: the decision is asked here, once, by everything that creates or authenticates an
 * account, so moving it to a stored per-organization list changes this file and nothing else.
 */
@Component
public class EmailDomainPolicy {

    private final List<String> allowed;

    public EmailDomainPolicy(@Value("${app.auth.allowed-email-domains:}") String configured) {
        this.allowed = parse(configured);
    }

    static List<String> parse(String configured) {
        if (configured == null || configured.isBlank()) return List.of();
        return java.util.Arrays.stream(configured.split(","))
                .map(String::trim)
                // A list written as "@company.com" is what somebody types first, and refusing it
                // over an @ would be a configuration error whose symptom is "nobody can sign in".
                .map(d -> d.startsWith("@") ? d.substring(1) : d)
                .map(d -> d.toLowerCase(Locale.ROOT))
                .filter(d -> !d.isBlank())
                .toList();
    }

    /** Empty configuration allows every domain — the single-company install, unchanged. */
    public boolean allowsEveryone() {
        return allowed.isEmpty();
    }

    public List<String> allowedDomains() {
        return allowed;
    }

    public boolean allows(String email) {
        if (allowed.isEmpty()) return true;
        String domain = domainOf(email);
        if (domain == null) return false;
        // A subdomain is not the domain: allowing "company.com" must not admit
        // "company.com.attacker.net", which is why this compares the whole label rather than
        // asking whether the address ends with the value.
        return allowed.contains(domain);
    }

    /**
     * @throws IllegalArgumentException naming the domains that ARE allowed — the person reading it
     *         is usually an operator who mistyped one, and "not allowed" alone sends them guessing
     */
    public void require(String email) {
        if (allows(email)) return;
        throw new IllegalArgumentException("Addresses at " + domainOf(email)
                + " cannot sign in to this deployment. Allowed: " + String.join(", ", allowed) + ".");
    }

    static String domainOf(String email) {
        if (email == null) return null;
        int at = email.lastIndexOf('@');
        if (at < 0 || at == email.length() - 1) return null;
        return email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }
}
