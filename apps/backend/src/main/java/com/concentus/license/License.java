package com.concentus.license;

import java.time.LocalDate;

/**
 * A verified license's contents. Plain data: no derived methods, because a bean-style getter on
 * a record becomes a Jackson property and this record is parsed FROM Jackson — the withId trap,
 * twice paid for elsewhere in this codebase, not to be paid a third time.
 *
 * @param seats   enterprise and team only; null on individual licenses
 * @param expires null on the free perpetual license; never null on a team license
 */
public record License(int v, String tier, String licensee, String email,
                      Integer seats, LocalDate issued, LocalDate expires, String id) {
    public static final String TIER_INDIVIDUAL = "individual";
    public static final String TIER_ENTERPRISE = "enterprise";
    /**
     * Sold self-serve from the website, so signed by a key that lives in Vercel. What makes that
     * safe to sell is the ceiling: {@link LicenseVerifier} refuses a team license with more than
     * {@link #TEAM_MAX_SEATS} seats or with no expiry date — so the most a compromised web tier
     * can mint is a small, time-limited license, never an enterprise one.
     */
    public static final String TIER_TEAM = "team";
    public static final int TEAM_MAX_SEATS = 10;
}
