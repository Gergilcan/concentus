package com.concentus.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * The signed-in principal.
 *
 * <p>Carries the organization id, which is the whole point: {@link OrgContext} reads it straight
 * off the principal, so an endpoint scoping a query to "the caller's organization" never has to
 * accept an organization id as a parameter — and therefore can't be tricked into reading another
 * tenant's rows by passing a different one.
 */
public record ConcentusUserDetails(String userId, String organizationId, String email,
                                   String passwordHash, String role, boolean enabled)
        implements UserDetails {

    public static ConcentusUserDetails of(Accounts.UserAccount user) {
        return new ConcentusUserDetails(user.id(), user.organizationId(), user.email(),
                user.passwordHash(), user.role(), user.enabled());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
