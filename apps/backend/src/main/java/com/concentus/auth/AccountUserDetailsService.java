package com.concentus.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Looks a sign-in up by email, for Spring Security's DAO authentication. */
@Service
public class AccountUserDetailsService implements UserDetailsService {

    private final AccountStore accounts;

    public AccountUserDetailsService(AccountStore accounts) {
        this.accounts = accounts;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // With the database down we cannot tell a valid credential from an invalid one, so the
        // only safe answer is "no". Reported as a normal authentication failure rather than a
        // 500, since the caller must not learn anything about server internals from a login form.
        if (!accounts.isAvailable()) {
            throw new UsernameNotFoundException("Account store unavailable.");
        }
        return accounts.findByEmail(email)
                .map(ConcentusUserDetails::of)
                // Same message for "no such user" and "wrong password" so the endpoint can't be
                // used to enumerate which email addresses have accounts.
                .orElseThrow(() -> new UsernameNotFoundException("Bad credentials"));
    }
}
