package com.concentus.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The role ladder.
 *
 * <p>Two rungs were added BELOW the two that existed, so nobody's account changed meaning the day
 * they appeared. What is new is that an account can be given less: someone who reads what the
 * automation did without being able to make it do anything, and someone who runs the daily cycle
 * without being free to rewrite what it does to the ad account.
 */
class RolesTest {

    @Test
    void the_ladder_runs_from_reading_to_administering() {
        assertThat(Accounts.ROLES).containsExactly("VIEWER", "OPERATOR", "MEMBER", "ADMIN");
    }

    @Test
    void a_role_covers_everything_below_it() {
        assertThat(Accounts.atLeast(Accounts.ROLE_ADMIN, Accounts.ROLE_MEMBER)).isTrue();
        assertThat(Accounts.atLeast(Accounts.ROLE_MEMBER, Accounts.ROLE_OPERATOR)).isTrue();
        assertThat(Accounts.atLeast(Accounts.ROLE_OPERATOR, Accounts.ROLE_VIEWER)).isTrue();
        assertThat(Accounts.atLeast(Accounts.ROLE_VIEWER, Accounts.ROLE_VIEWER)).isTrue();
    }

    @Test
    void and_nothing_above_it() {
        assertThat(Accounts.atLeast(Accounts.ROLE_VIEWER, Accounts.ROLE_OPERATOR)).isFalse();
        assertThat(Accounts.atLeast(Accounts.ROLE_OPERATOR, Accounts.ROLE_MEMBER)).isFalse();
        assertThat(Accounts.atLeast(Accounts.ROLE_MEMBER, Accounts.ROLE_ADMIN)).isFalse();
    }

    // The existing accounts keep exactly the powers they had: MEMBER still edits, ADMIN still
    // administers. The addition is downward, which is why no migration was needed.
    @Test
    void the_roles_that_already_existed_still_sit_at_the_top() {
        assertThat(Accounts.rank(Accounts.ROLE_MEMBER)).isGreaterThan(Accounts.rank(Accounts.ROLE_OPERATOR));
        assertThat(Accounts.rank(Accounts.ROLE_ADMIN)).isEqualTo(Accounts.ROLES.size() - 1);
    }

    // A typo must not hand someone a working account with fewer powers than intended, to be
    // discovered as a puzzle a week later.
    @Test
    void an_unknown_name_is_not_a_role_at_all() {
        assertThat(Accounts.rank("editor")).isEqualTo(-1);
        assertThat(Accounts.normalizeRole("editor")).isNull();
        assertThat(Accounts.atLeast("editor", Accounts.ROLE_VIEWER)).isFalse();
        assertThat(Accounts.atLeast(null, Accounts.ROLE_VIEWER)).isFalse();
    }

    @Test
    void a_role_is_recognised_however_it_was_typed() {
        assertThat(Accounts.normalizeRole("  viewer ")).isEqualTo(Accounts.ROLE_VIEWER);
        assertThat(Accounts.normalizeRole("Operator")).isEqualTo(Accounts.ROLE_OPERATOR);
    }
}
