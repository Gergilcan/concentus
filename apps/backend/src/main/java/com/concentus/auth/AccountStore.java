package com.concentus.auth;

import com.concentus.support.Ids;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Organizations, users and memberships, in PostgreSQL.
 *
 * <p>Schema comes from the Flyway migrations in {@code db/migration}; this class only checks that
 * they reached the database it was pointed at.
 *
 * <p>The memberships table is the truth about who is in which organization as what; the account
 * row's {@code organization_id} and {@code role} are a copy for the organization the account is
 * currently working in, rewritten by {@link #switchOrganization}. Two places on purpose: every
 * reader of the principal keeps seeing one organization and one role, and only this class knows
 * there are more.
 *
 * <p>Unlike the run store, this one <b>does not degrade to memory</b> when the database is
 * unavailable. Runs falling back to memory costs history; accounts falling back to memory would
 * mean an unauthenticated server. {@link #isAvailable()} reporting false makes every sign-in fail
 * closed instead.
 */
@Component
public class AccountStore {

    private static final Logger log = LoggerFactory.getLogger(AccountStore.class);

    private final JdbcTemplate jdbc;
    private volatile boolean available;

    public AccountStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        // Created by the migrations; this asks whether they reached this database. All three
        // tables are probed because sign-in needs all three, and part of a schema must not read
        // as available.
        try {
            jdbc.queryForObject("select count(*) from organizations", Integer.class);
            jdbc.queryForObject("select count(*) from users", Integer.class);
            jdbc.queryForObject("select count(*) from memberships", Integer.class);
            available = true;
            log.info("Account store ready (PostgreSQL).");
        } catch (Exception e) {
            available = false;
            log.error("Account store unavailable — sign-in will fail closed until the database is reachable: {}",
                    e.getMessage());
        }
    }

    /** False when the database could not be reached; callers must treat that as "deny", not "allow". */
    public boolean isAvailable() {
        return available;
    }

    // ---- organizations ----

    public Accounts.Organization createOrganization(String id, String name) {
        String orgId = (id == null || id.isBlank()) ? Ids.generate("org_", 10) : Ids.sanitize(id, "Invalid organization id: ");
        Accounts.Organization org = new Accounts.Organization(orgId, name, System.currentTimeMillis());
        jdbc.update("insert into organizations (id, name, created_at) values (?,?,?) on conflict (id) do nothing",
                org.id(), org.name(), org.createdAt());
        return findOrganization(orgId).orElse(org);
    }

    public Optional<Accounts.Organization> findOrganization(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return jdbc.query("select * from organizations where id = ?", ORG_MAPPER, id).stream().findFirst();
    }

    public void renameOrganization(String id, String name) {
        jdbc.update("update organizations set name = ? where id = ?", name, id);
    }

    public long countOrganizations() {
        Long n = jdbc.queryForObject("select count(*) from organizations", Long.class);
        return n == null ? 0 : n;
    }

    /** The organizations one account belongs to, oldest membership first. */
    public List<Accounts.Organization> organizationsOf(String userId) {
        return jdbc.query("""
                select o.* from organizations o
                join memberships m on m.organization_id = o.id
                where m.user_id = ?
                order by m.created_at, o.id
                """, ORG_MAPPER, userId);
    }

    // ---- memberships ----

    public Optional<Accounts.Membership> membership(String userId, String organizationId) {
        if (userId == null || organizationId == null) return Optional.empty();
        return jdbc.query("select * from memberships where user_id = ? and organization_id = ?",
                MEMBERSHIP_MAPPER, userId, organizationId).stream().findFirst();
    }

    public List<Accounts.Membership> membershipsOf(String userId) {
        return jdbc.query("select * from memberships where user_id = ? order by created_at",
                MEMBERSHIP_MAPPER, userId);
    }

    /**
     * Puts an account into an organization, or changes its role there if it already is.
     *
     * <p>Idempotent on purpose: inviting somebody who is already a member is a role change, not
     * an error, because the admin doing it is expressing what that person should be able to do —
     * which is the same thing either way.
     */
    public Accounts.Membership addMembership(String userId, String organizationId, String role) {
        String normalized = role == null || role.isBlank() ? Accounts.ROLE_MEMBER : role.toUpperCase(Locale.ROOT);
        jdbc.update("""
                insert into memberships (user_id, organization_id, role, created_at)
                values (?, ?, ?, ?)
                on conflict (user_id, organization_id) do update set role = excluded.role
                """, userId, organizationId, normalized, System.currentTimeMillis());
        // The account row mirrors the membership of the organization it is working in.
        jdbc.update("update users set role = ? where id = ? and organization_id = ?",
                normalized, userId, organizationId);
        return membership(userId, organizationId).orElseThrow();
    }

    /**
     * Makes {@code organizationId} the one this account works in.
     *
     * @return false when the account holds no membership there — nothing is changed, and the
     *         caller answers "not yours" rather than switching anybody into an organization by
     *         naming it
     */
    public boolean switchOrganization(String userId, String organizationId) {
        Optional<Accounts.Membership> target = membership(userId, organizationId);
        if (target.isEmpty()) return false;
        jdbc.update("update users set organization_id = ?, role = ? where id = ?",
                organizationId, target.get().role(), userId);
        return true;
    }

    // ---- users ----

    /**
     * Inserts a user, and their first membership. {@code passwordHash} must already be hashed —
     * this store never sees a plaintext password, so there is no path by which one could be
     * logged or persisted.
     *
     * @throws IllegalStateException if the email is already registered
     */
    public Accounts.UserAccount createUser(String organizationId, String email, String passwordHash, String role) {
        Accounts.UserAccount user = new Accounts.UserAccount(
                Ids.generate("usr_", 10), organizationId, normalizeEmail(email), passwordHash,
                role == null || role.isBlank() ? Accounts.ROLE_MEMBER : role.toUpperCase(Locale.ROOT),
                true, System.currentTimeMillis());
        try {
            jdbc.update("""
                    insert into users (id, organization_id, email, password_hash, role, enabled, created_at)
                    values (?,?,?,?,?,?,?)
                    """,
                    user.id(), user.organizationId(), user.email(), user.passwordHash(),
                    user.role(), user.enabled(), user.createdAt());
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("That email address is already registered.");
        }
        jdbc.update("""
                insert into memberships (user_id, organization_id, role, created_at)
                values (?, ?, ?, ?)
                on conflict (user_id, organization_id) do nothing
                """, user.id(), user.organizationId(), user.role(), user.createdAt());
        return user;
    }

    public Optional<Accounts.UserAccount> findByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        return jdbc.query("select * from users where lower(email) = ?", USER_MAPPER, normalizeEmail(email))
                .stream().findFirst();
    }

    /**
     * Everyone who is in an organization, with the role each holds THERE — which for a person
     * currently working somewhere else is not what their account row says.
     */
    public List<Accounts.UserAccount> listUsers(String organizationId) {
        return jdbc.query("""
                select u.id, m.organization_id, u.email, u.password_hash, m.role, u.enabled, u.created_at
                from users u
                join memberships m on m.user_id = u.id
                where m.organization_id = ?
                order by m.created_at, u.created_at
                """, USER_MAPPER, organizationId);
    }

    /**
     * Distinct accounts on the deployment. This is what a license's seats count: a person in two
     * organizations is one seat, because they are one person.
     */
    public long countUsers() {
        Long n = jdbc.queryForObject("select count(*) from users", Long.class);
        return n == null ? 0 : n;
    }

    public void updatePassword(String userId, String passwordHash) {
        jdbc.update("update users set password_hash = ? where id = ?", passwordHash, userId);
    }

    public Optional<Accounts.UserAccount> findById(String userId) {
        if (userId == null || userId.isBlank()) return Optional.empty();
        return jdbc.query("select * from users where id = ?", USER_MAPPER, userId).stream().findFirst();
    }

    /** Scoped to one organization, so a mistyped id can never reach another tenant's account. */
    public void updateRole(String userId, String organizationId, String role) {
        jdbc.update("update memberships set role = ? where user_id = ? and organization_id = ?",
                role, userId, organizationId);
        jdbc.update("update users set role = ? where id = ? and organization_id = ?",
                role, userId, organizationId);
    }

    /** How many accounts in this organization hold a role — used to refuse the last admin's demotion. */
    public long countByRole(String organizationId, String role) {
        Long n = jdbc.queryForObject("""
                select count(*) from memberships m
                join users u on u.id = m.user_id
                where m.organization_id = ? and upper(m.role) = ? and u.enabled
                """, Long.class, organizationId, role.toUpperCase(Locale.ROOT));
        return n == null ? 0 : n;
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static final RowMapper<Accounts.Organization> ORG_MAPPER = (rs, i) ->
            new Accounts.Organization(rs.getString("id"), rs.getString("name"), rs.getLong("created_at"));

    private static final RowMapper<Accounts.Membership> MEMBERSHIP_MAPPER = (rs, i) ->
            new Accounts.Membership(rs.getString("user_id"), rs.getString("organization_id"),
                    rs.getString("role"), rs.getLong("created_at"));

    private static final RowMapper<Accounts.UserAccount> USER_MAPPER = (rs, i) ->
            new Accounts.UserAccount(rs.getString("id"), rs.getString("organization_id"),
                    rs.getString("email"), rs.getString("password_hash"), rs.getString("role"),
                    rs.getBoolean("enabled"), rs.getLong("created_at"));
}
