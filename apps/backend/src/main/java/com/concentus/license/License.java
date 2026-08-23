package com.concentus.license;

import java.time.LocalDate;

/**
 * A verified license's contents. Plain data: no derived methods, because a bean-style getter on
 * a record becomes a Jackson property and this record is parsed FROM Jackson — the withId trap,
 * twice paid for elsewhere in this codebase, not to be paid a third time.
 *
 * @param seats   enterprise only; null on individual licenses
 * @param expires null on the free perpetual license
 */
public record License(int v, String tier, String licensee, String email,
                      Integer seats, LocalDate issued, LocalDate expires, String id) {
    public static final String TIER_INDIVIDUAL = "individual";
    public static final String TIER_ENTERPRISE = "enterprise";
}
