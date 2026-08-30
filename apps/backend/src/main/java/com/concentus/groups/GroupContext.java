package com.concentus.groups;

import com.concentus.auth.ConcentusUserDetails;
import com.concentus.auth.OrgContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which groups the current request's caller is in — resolved once per request, then answered
 * from memory.
 *
 * <p>The stores ask this on every read to hide the rows scoped to a group the caller is not in,
 * and a screen may list twenty resources in one request, so the membership query runs once and
 * the answer is kept on the request ({@link RequestContextHolder}); a thread with no request —
 * a test, a scheduler — gets the same answer computed each time, which is correct and merely
 * slower.
 *
 * <p><b>No principal, no filter.</b> A thread with nobody signed in is the machine's own: a cron
 * firing a group's flow, a run's own threads loading that flow's MCP servers and facades, a mail
 * poll. Those must reach group-scoped rows or a group could not own a flow that runs. So
 * {@link #groupIds()} answers empty there, as the design says, and {@link #predicate} — the thing
 * the stores actually apply — answers "nothing is hidden". The filter is a filter on people.
 *
 * <p>An ADMIN of the organization sees every group's rows: {@link #predicate} answers empty for
 * them too, and {@link #sees} is true.
 */
@Component
public class GroupContext {

    private static final String ATTRIBUTE = GroupContext.class.getName() + ".resolved";

    /** What a caller is in: the groups, and which of them they manage. */
    public record Memberships(Set<String> groupIds, Set<String> managed) {
        public static final Memberships NONE = new Memberships(Set.of(), Set.of());

        static Memberships of(Map<String, Boolean> byGroup) {
            Set<String> all = new LinkedHashSet<>();
            Set<String> managed = new LinkedHashSet<>();
            byGroup.forEach((id, manager) -> {
                all.add(id);
                if (Boolean.TRUE.equals(manager)) managed.add(id);
            });
            return new Memberships(Set.copyOf(all), Set.copyOf(managed));
        }
    }

    /** A SQL condition over a {@code group_id} column and the values it binds. */
    public record Predicate(String sql, List<Object> args) {
    }

    /** What was resolved, and for whom — so a principal replaced mid-request is not answered for by the old one. */
    private record Resolved(String userId, String organizationId, Memberships memberships) {
    }

    private final OrgContext orgContext;
    private final GroupStore store;

    public GroupContext(OrgContext orgContext, GroupStore store) {
        this.orgContext = orgContext;
        this.store = store;
    }

    /** The caller's memberships in their current organization; {@link Memberships#NONE} with no principal. */
    public Memberships current() {
        Optional<ConcentusUserDetails> user = orgContext.currentUser();
        if (user.isEmpty()) return Memberships.NONE;
        String userId = user.get().userId();
        String organizationId = user.get().organizationId();
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            Object cached = request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof Resolved r && r.userId().equals(userId)
                    && r.organizationId().equals(organizationId)) {
                return r.memberships();
            }
        }
        Memberships resolved = of(userId, organizationId);
        if (request != null) {
            request.setAttribute(ATTRIBUTE, new Resolved(userId, organizationId, resolved),
                    RequestAttributes.SCOPE_REQUEST);
        }
        return resolved;
    }

    /**
     * One account's memberships in one organization, read fresh — for a caller that holds the
     * principal itself rather than the security context, such as a WebSocket handshake.
     */
    public Memberships of(String userId, String organizationId) {
        return Memberships.of(store.membershipsOf(userId, organizationId));
    }

    /** The ids of the groups the caller is in; empty with no principal. */
    public Set<String> groupIds() {
        return current().groupIds();
    }

    public boolean isMember(String groupId) {
        return groupId != null && current().groupIds().contains(groupId);
    }

    public boolean manages(String groupId) {
        return groupId != null && current().managed().contains(groupId);
    }

    /** Whether the caller administers the organization — and so every group in it. */
    public boolean isAdmin() {
        return orgContext.isAdmin();
    }

    /** Whether the caller may see a row scoped to {@code groupId}: unscoped, one of theirs, or they are an admin. */
    public boolean sees(String groupId) {
        return groupId == null || groupId.isBlank() || isAdmin() || isMember(groupId);
    }

    /**
     * The condition a store adds over its {@code group_id} column for this caller, or empty when
     * nothing is hidden from them — an admin, or no principal at all (see the class comment).
     */
    public Optional<Predicate> predicate(String column) {
        if (orgContext.currentUser().isEmpty() || isAdmin()) return Optional.empty();
        Set<String> mine = current().groupIds();
        if (mine.isEmpty()) return Optional.of(new Predicate("(" + column + " is null)", List.of()));
        StringBuilder sql = new StringBuilder("(").append(column).append(" is null or ").append(column).append(" in (");
        List<Object> args = new ArrayList<>(mine.size());
        for (String id : mine) {
            if (!args.isEmpty()) sql.append(", ");
            sql.append('?');
            args.add(id);
        }
        sql.append("))");
        return Optional.of(new Predicate(sql.toString(), List.copyOf(args)));
    }
}
