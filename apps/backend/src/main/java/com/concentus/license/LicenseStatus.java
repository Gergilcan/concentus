package com.concentus.license;

/**
 * What the UI shows for the installed license: enough of {@link License} to display, plus the
 * policy questions {@link License} itself deliberately doesn't answer.
 *
 * <p>Plain data: no derived methods, because a bean-style getter on a record becomes a Jackson
 * property and this record IS what {@code GET /api/license} serializes — the withId trap, already
 * paid for once in this codebase, not to be paid a second time here.
 *
 * <p>No license installed (or an unverifiable one) is every field null/false except {@code problem},
 * which always names the fix rather than just the failure.
 *
 * @param seats          the license's raw seat count; enterprise only, null otherwise
 * @param expires        ISO date string, or null on a perpetual (individual) license
 * @param graceDaysLeft  null unless the license has expired; the days left in the grace window
 *                       otherwise, clamped to zero once the window is over
 * @param problem        null when {@code valid}; otherwise what's wrong AND what to do about it
 */
public record LicenseStatus(String tier, String licensee, Integer seats, String expires,
                            Integer graceDaysLeft, boolean valid, String problem) {
}
