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
 * @param seats          the license's raw seat count; enterprise and team only, null otherwise
 * @param expires        ISO date string, or null on a perpetual (individual) license
 * @param graceDaysLeft  null unless the license has expired; the days left in the grace window
 *                       otherwise, clamped to zero once the window is over
 * @param problem        null when {@code valid}; otherwise what's wrong AND what to do about it
 * @param trial          true when this is the 14-day trial the website issues — a team license
 *                       in every way the gates care about, shown as a trial so the countdown is
 *                       to the end of a trial, not to the end of something that was bought
 */
public record LicenseStatus(String tier, String licensee, Integer seats, String expires,
                            Integer graceDaysLeft, boolean valid, String problem, boolean trial) {
}
