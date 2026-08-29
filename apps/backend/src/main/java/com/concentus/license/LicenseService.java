package com.concentus.license;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * The answer to "is this installation licensed": where the license comes from, whether an expired
 * paid license is still inside its grace window, and how many seats that buys.
 *
 * <p>Two sources, first match wins: the {@code CONCENTUS_LICENSE} environment variable, then
 * {@code license.key} in the data directory. An environment variable is how a container or a
 * scripted deployment is licensed without a writable disk; the file is what {@link #install}
 * writes for everyone else, from the Settings screen. Whichever wins is read once at construction
 * and cached — a license does not change mid-request, and every endpoint on every request asking
 * "am I licensed" must not mean a filesystem read each time.
 *
 * <p><b>Two paid tiers, one set of gates.</b> Enterprise and team licenses unlock the same things
 * (the shared database, members up to {@code seats}, SSO); they differ in who signs them and how
 * big they may be, which is {@link LicenseVerifier}'s concern. Everything in here asks "is a paid
 * license active" and never "which one" — so a gate written for enterprise covers team without a
 * second condition to forget.
 *
 * <p><b>Grace, not a cliff.</b> A paid license that has expired keeps working for
 * {@link #GRACE_DAYS} more days. Renewal is a purchase order, not a click, and a flow that stops
 * running the moment a date rolls over — mid-afternoon, on whoever is on call — is a worse failure
 * than a warning banner for two weeks. An individual license never expires at all: it is free, so
 * there is nothing to lapse.
 */
@Service
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);

    /** Days an expired paid license keeps working before it stops counting as active. */
    public static final int GRACE_DAYS = 14;

    /** The environment variable that, when set, wins over any installed license file. */
    public static final String ENV_VAR = "CONCENTUS_LICENSE";

    /** The license file's name inside the data directory. */
    public static final String FILE_NAME = "license.key";

    private static final String LICENSE_URL = "https://www.concentus-ai.com/#license";
    private static final String FIX_HINT = "Individuals can request a free one at " + LICENSE_URL
            + "; the shared database, extra members and SSO need a team or enterprise license.";
    private static final String NO_LICENSE_PROBLEM = "No license installed. " + FIX_HINT;

    private final LicenseVerifier verifier;
    private final Path dataDir;
    private final String envLicense;
    private final Clock clock;

    private volatile Loaded loaded;

    // @Autowired is load-bearing: the constructor below is a second one, and Spring faced with two
    // picks neither — "No default constructor found", a mistake already made more than once here.
    @Autowired
    public LicenseService(@Value("${app.data-dir}") String dataDir,
                          @Value("${" + ENV_VAR + ":}") String envLicense,
                          @Value("${" + LicenseVerifier.ENV_TEST_KEYS + ":}") String envTestKeys,
                          @Value("${" + LicenseVerifier.PROPERTY_TEAM_PUBLIC_KEY + ":}") String teamPublicKey) {
        this(LicenseVerifier.forProduction(envTestKeys, teamPublicKey), Path.of(dataDir), envLicense,
                Clock.systemUTC());
    }

    /** For tests: an injectable verifier (fixture keys), directory and clock. */
    LicenseService(LicenseVerifier verifier, Path dataDir, String envLicense, Clock clock) {
        this.verifier = verifier;
        this.dataDir = dataDir;
        this.envLicense = envLicense == null ? "" : envLicense;
        this.clock = clock;
        this.loaded = load();
    }

    /** The verified license in effect, if any source held one that checked out. */
    public Optional<License> current() {
        return Optional.ofNullable(loaded.license);
    }

    /** What the UI shows: the license (if any), whether it's currently good, and why not. */
    public LicenseStatus status() {
        License license = loaded.license;
        if (license == null) {
            return new LicenseStatus(null, null, null, null, null, false, loaded.problem, false);
        }
        String expires = license.expires() == null ? null : license.expires().toString();
        Integer graceDaysLeft = graceDaysLeft(license);
        boolean valid = License.TIER_INDIVIDUAL.equals(license.tier()) || enterpriseActive();
        String problem = valid ? null : expiredBeyondGraceProblem(license);
        return new LicenseStatus(license.tier(), license.licensee(), license.seats(), expires,
                graceDaysLeft, valid, problem, isTrial(license));
    }

    /** The trial flag as a plain boolean: absent on every license minted before trials existed. */
    static boolean isTrial(License license) {
        return Boolean.TRUE.equals(license.trial());
    }

    /**
     * A verified paid license — enterprise or team — not yet past its expiry date plus
     * {@link #GRACE_DAYS}. The name says "enterprise" because that is what the FEATURES it unlocks
     * are called everywhere a person sees them; a team license is a smaller way to buy the same
     * ones, not a different set.
     */
    public boolean enterpriseActive() {
        License license = loaded.license;
        if (license == null || !isPaidTier(license)) return false;
        if (license.expires() == null) return true;
        return !LocalDate.now(clock).isAfter(license.expires().plusDays(GRACE_DAYS));
    }

    /**
     * How many seats this installation may use. Never null and never "unlimited": no license, an
     * unverifiable one, an individual license, or a paid one past its grace window all mean exactly
     * one — a single-user installation is always allowed to keep working.
     *
     * <p>An active enterprise license with no seat count on it — hand-minted rather than issued by
     * {@code mint-license.mjs}, or otherwise malformed — clamps to one too: a license that names no
     * seats grants none extra, and the alternative (returning null here) would NPE every caller
     * that unboxes this into an {@code int}, which is all of them. (A team license cannot reach
     * here seatless: the verifier refuses it.)
     */
    public Integer seatLimit() {
        if (!enterpriseActive()) return 1;
        Integer seats = loaded.license.seats();
        return seats == null ? 1 : seats;
    }

    /**
     * Verifies {@code token}, writes it to {@code license.key} in the data directory, and re-reads
     * — so a caller sees the same {@link #status()} the next request would.
     *
     * <p>Refuses up front, before touching the disk, when {@code CONCENTUS_LICENSE} is set: writing
     * the file would do nothing (the environment variable keeps winning), so installing here would
     * silently succeed at nothing. Better to say so than to leave someone editing a file that is
     * never read.
     */
    public synchronized void install(String token) throws InvalidLicenseException {
        if (!envLicense.isBlank()) {
            throw new InvalidLicenseException("The " + ENV_VAR + " environment variable is set and "
                    + "takes precedence over any installed license file; unset " + ENV_VAR
                    + " to install one here instead.");
        }
        verifier.verify(token);   // validates before anything is written
        try {
            Files.writeString(dataDir.resolve(FILE_NAME), token.trim(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InvalidLicenseException("Could not write the license file: " + e.getMessage(), e);
        }
        this.loaded = load();
    }

    /** Resolves the winning source and verifies it, or explains why there's nothing to show. */
    private Loaded load() {
        String token = !envLicense.isBlank() ? envLicense : fileToken();
        if (token == null || token.isBlank()) {
            return new Loaded(null, NO_LICENSE_PROBLEM);
        }
        try {
            return new Loaded(verifier.verify(token), null);
        } catch (InvalidLicenseException e) {
            return new Loaded(null, invalidLicenseProblem(e.getMessage()));
        }
    }

    private String fileToken() {
        Path file = dataDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) return null;
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not read {}: {}", file, e.getMessage());
            return null;
        }
    }

    /** Null unless expiry has passed; otherwise days left in the grace window, clamped to zero. */
    private Integer graceDaysLeft(License license) {
        if (license.expires() == null) return null;
        LocalDate today = LocalDate.now(clock);
        if (!today.isAfter(license.expires())) return null;
        long daysSinceExpiry = ChronoUnit.DAYS.between(license.expires(), today);
        return (int) Math.max(0, GRACE_DAYS - daysSinceExpiry);
    }

    /**
     * The message shown when adding one more person would push an organization past {@link
     * #seatLimit()} — reused everywhere that gate applies: {@code AccountController#createMember}
     * refusing a new member, and {@code OidcSignIn} refusing to provision a brand-new account for
     * an arrival through a directory. One wording, so the two paths that enforce the same rule
     * never drift into explaining it differently.
     */
    public String seatLimitReachedMessage(int limit) {
        String licensee = status().licensee();
        String licensedAs = licensee == null ? "no license installed" : "licensed to " + licensee;
        return "This installation is limited to " + limit + (limit == 1 ? " member" : " members")
                + " (" + licensedAs + "). A team or enterprise license — or a bigger one — raises "
                + "the limit; get one at " + LICENSE_URL + ".";
    }

    /** Enterprise or team: the tiers that unlock the paid features and carry an expiry to lapse. */
    static boolean isPaidTier(License license) {
        return License.TIER_ENTERPRISE.equals(license.tier()) || License.TIER_TEAM.equals(license.tier());
    }

    private static String invalidLicenseProblem(String reason) {
        return "The installed license could not be verified (" + reason + "). " + FIX_HINT;
    }

    private static String expiredBeyondGraceProblem(License license) {
        if (isTrial(license)) {
            // A trial is not renewed, it is followed by a purchase — the fix is a different card.
            return "The trial for " + license.licensee() + " ended on " + license.expires() + " and its "
                    + GRACE_DAYS + "-day grace period is over. A team or enterprise license at "
                    + LICENSE_URL + " restores the shared database, members and SSO.";
        }
        return "The " + license.tier() + " license for " + license.licensee() + " expired on "
                + license.expires() + " and its " + GRACE_DAYS + "-day grace period is over. Renew at "
                + LICENSE_URL + " to restore enterprise features.";
    }

    /** What construction (or {@link #install}) resolved: a license, or why there isn't one. */
    private record Loaded(License license, String problem) { }
}
