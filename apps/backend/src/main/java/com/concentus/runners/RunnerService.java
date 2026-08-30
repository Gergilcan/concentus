package com.concentus.runners;

import com.concentus.audit.AuditKinds;
import com.concentus.audit.AuditService;
import com.concentus.auth.AccountStore;
import com.concentus.auth.Accounts;
import com.concentus.auth.ConcentusUserDetails;
import com.concentus.auth.OrgContext;
import com.concentus.groups.GroupContext;
import com.concentus.groups.GroupStore;
import com.concentus.license.Feature;
import com.concentus.license.LicenseService;
import com.concentus.web.OAuthCallbacks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Who may see, register, manage and use a runner — and which one a launch gets.
 *
 * <p>The scope on the row is the whole rule. {@code organization}: anybody in the organization
 * may run flows on it. {@code group}: the group's members and the organization's administrators
 * — and a launch with nobody signed in (a schedule, a webhook) whose flow belongs to that group.
 * {@code user}: the owner, and nobody else, ever: it is that person's machine and that person's
 * login, and neither an administrator nor a schedule gets to spend it.
 */
@Service
public class RunnerService {

    /** Where a launch was sent. */
    public record Selection(String runnerId, String runnerName, String note) {
    }

    public record MayCreate(boolean organization, List<String> groups, boolean user) {
    }

    public record Listing(List<RunnerView> runners, String hubUrl, MayCreate mayCreate) {
    }

    public record Created(RunnerView runner, String token, String hubUrl) {
    }

    private final RunnerStore store;
    private final RunnerRegistry registry;
    private final OrgContext orgContext;
    private final GroupContext groups;
    private final GroupStore groupStore;
    private final AccountStore accounts;
    private final LicenseService license;
    private final AuditService audit;
    private final String publicUrl;

    public RunnerService(RunnerStore store, RunnerRegistry registry, OrgContext orgContext, GroupContext groups,
                         GroupStore groupStore, AccountStore accounts, LicenseService license, AuditService audit,
                         @Value("${app.public-url:}") String publicUrl) {
        this.store = store;
        this.registry = registry;
        this.orgContext = orgContext;
        this.groups = groups;
        this.groupStore = groupStore;
        this.accounts = accounts;
        this.license = license;
        this.audit = audit;
        this.publicUrl = publicUrl == null ? "" : publicUrl.trim();
    }

    // ------------------------------------------------------------------ the screen

    public Listing list() {
        ConcentusUserDetails user = orgContext.requireUser();
        List<RunnerView> views = new ArrayList<>();
        for (Runner r : store.list(user.organizationId())) {
            if (sees(r, user)) views.add(view(r, user));
        }
        return new Listing(views, hubUrl(), mayCreate(user));
    }

    /** The runners the caller may run a flow on, revoked ones left out, offline ones marked. */
    public List<RunnerView> usable() {
        ConcentusUserDetails user = orgContext.requireUser();
        List<RunnerView> views = new ArrayList<>();
        for (Runner r : store.list(user.organizationId())) {
            if (!r.revoked() && mayUse(r, Optional.of(user), user.organizationId(), null)) views.add(view(r, user));
        }
        return views;
    }

    public Created register(String name, String scope, String groupId) {
        ConcentusUserDetails user = orgContext.requireUser();
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) {
            throw new IllegalArgumentException("A runner needs a name of up to 80 characters.");
        }
        String s = Runner.normalizeScope(scope);
        if (s == null) throw new IllegalArgumentException("The scope must be organization, group or user.");
        String group = null;
        String owner = null;
        boolean admin = isAdmin(user);
        switch (s) {
            case Runner.SCOPE_ORGANIZATION -> {
                if (!admin) throw new OrgContext.AccessDeniedForOrganization(
                        "Only an administrator registers a runner for the whole organization.");
            }
            case Runner.SCOPE_GROUP -> {
                if (groupId == null || groupId.isBlank()) throw new IllegalArgumentException("A group-scoped runner needs a group.");
                if (groupStore.find(user.organizationId(), groupId).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such group.");
                }
                if (license.withheld(Feature.GROUPS)) {
                    throw new OrgContext.AccessDeniedForOrganization(license.refusal(Feature.GROUPS));
                }
                if (!admin && !groups.manages(groupId)) throw new OrgContext.AccessDeniedForOrganization(
                        "Only an administrator or a manager of the group registers a runner for it.");
                group = groupId;
            }
            case Runner.SCOPE_USER -> owner = user.userId();
            default -> throw new IllegalArgumentException("The scope must be organization, group or user.");
        }
        String token = RunnerTokens.mint();
        Runner runner = store.create(user.organizationId(), trimmed, s, group, owner, RunnerTokens.hash(token),
                user.email());
        audit.record(AuditKinds.RUNNER_REGISTERED, "runner", runner.id(), runner.name(),
                Map.of("scope", s, "groupId", group == null ? "" : group));
        return new Created(view(runner, user), token, hubUrl());
    }

    public RunnerView rename(String id, String name) {
        ConcentusUserDetails user = orgContext.requireUser();
        Runner runner = require(id, user);
        requireManage(runner, user);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) {
            throw new IllegalArgumentException("A runner needs a name of up to 80 characters.");
        }
        store.rename(user.organizationId(), id, trimmed);
        audit.record(AuditKinds.RUNNER_RENAMED, "runner", id, trimmed, Map.of("from", runner.name()));
        return view(store.find(user.organizationId(), id).orElse(runner), user);
    }

    public RunnerView revoke(String id) {
        ConcentusUserDetails user = orgContext.requireUser();
        Runner runner = require(id, user);
        requireManage(runner, user);
        if (store.revoke(user.organizationId(), id, System.currentTimeMillis())) {
            registry.revoke(id);
            audit.record(AuditKinds.RUNNER_REVOKED, "runner", id, runner.name(), null);
        }
        return view(store.find(user.organizationId(), id).orElse(runner), user);
    }

    public void delete(String id) {
        ConcentusUserDetails user = orgContext.requireUser();
        Runner runner = require(id, user);
        requireManage(runner, user);
        registry.revoke(id);
        store.delete(user.organizationId(), id);
        audit.record(AuditKinds.RUNNER_DELETED, "runner", id, runner.name(), null);
    }

    // ------------------------------------------------------------------ the rules

    /** An administrator sees every runner; anybody else the ones they could use. */
    public boolean sees(Runner r, ConcentusUserDetails user) {
        if (!r.organizationId().equals(user.organizationId())) return false;
        if (isAdmin(user)) return true;
        return switch (r.scope()) {
            case Runner.SCOPE_ORGANIZATION -> true;
            case Runner.SCOPE_GROUP -> groups.isMember(r.groupId());
            case Runner.SCOPE_USER -> user.userId().equals(r.userId());
            default -> false;
        };
    }

    /** Rename, revoke, delete: an administrator, the owner, or a manager of the runner's group. */
    public boolean mayManage(Runner r, ConcentusUserDetails user) {
        if (!r.organizationId().equals(user.organizationId())) return false;
        if (isAdmin(user)) return true;
        return switch (r.scope()) {
            case Runner.SCOPE_GROUP -> groups.manages(r.groupId());
            case Runner.SCOPE_USER -> user.userId().equals(r.userId());
            default -> false;
        };
    }

    /**
     * Whether a launch may run on a runner.
     *
     * @param user        who is launching, if anyone
     * @param flowGroupId the flow's group, which is what a launch with nobody signed in is judged by
     */
    public boolean mayUse(Runner r, Optional<ConcentusUserDetails> user, String organizationId, String flowGroupId) {
        if (r.revoked() || !r.organizationId().equals(organizationId)) return false;
        return switch (r.scope()) {
            case Runner.SCOPE_ORGANIZATION -> true;
            case Runner.SCOPE_GROUP -> user.isPresent()
                    ? isAdmin(user.get()) || groups.isMember(r.groupId())
                    : r.groupId() != null && r.groupId().equals(flowGroupId);
            case Runner.SCOPE_USER -> user.isPresent() && user.get().userId().equals(r.userId());
            default -> false;
        };
    }

    /**
     * Where a launch goes, per the flow's {@code runner} setting.
     *
     * @param requested          the flow's setting: null/blank (automatic), {@code any}, or a runner id
     * @param claudeModel        whether the coordinator is a Claude model — the only kind a runner executes
     * @param localCliAvailable  whether this machine can run the CLI itself
     * @return null for this machine, else the runner chosen
     * @throws IllegalStateException when the setting cannot be honoured, saying why
     */
    public Selection choose(String requested, String organizationId, String flowGroupId, boolean claudeModel,
                            boolean localCliAvailable) {
        String wanted = requested == null ? "" : requested.trim();
        Optional<ConcentusUserDetails> user = orgContext.currentUser();
        if (wanted.isEmpty()) {
            if (localCliAvailable || !claudeModel) return null;
            return leastBusy(organizationId, flowGroupId, user)
                    .map(r -> new Selection(r.id(), r.name(),
                            "No Claude login on this server; running on runner '" + r.name() + "'."))
                    .orElse(null);
        }
        if (!claudeModel) {
            throw new IllegalStateException("Runners execute Claude CLI flows; this flow's coordinator uses a "
                    + "self-hosted model, which runs on this server. Set the flow to run here.");
        }
        if ("any".equalsIgnoreCase(wanted)) {
            return leastBusy(organizationId, flowGroupId, user)
                    .map(r -> new Selection(r.id(), r.name(), null))
                    .orElseThrow(() -> new IllegalStateException("No runner is online for this flow. Start one, "
                            + "or set the flow to run here."));
        }
        Runner runner = store.find(organizationId, wanted).orElseThrow(() -> new IllegalStateException(
                "This flow is set to run on a runner that no longer exists. Pick another under the flow's settings."));
        if (runner.revoked()) {
            throw new IllegalStateException("Runner '" + runner.name() + "' was revoked. Pick another under the flow's settings.");
        }
        if (!mayUse(runner, user, organizationId, flowGroupId)) {
            throw new IllegalStateException("Runner '" + runner.name() + "' is not yours to use: it is registered for "
                    + describeScope(runner) + ".");
        }
        if (!registry.online(runner.id())) {
            throw new IllegalStateException("Runner '" + runner.name() + "' is offline. Start it, or set the flow to run somewhere else.");
        }
        return new Selection(runner.id(), runner.name(), null);
    }

    private Optional<Runner> leastBusy(String organizationId, String flowGroupId, Optional<ConcentusUserDetails> user) {
        return store.list(organizationId).stream()
                .filter(r -> mayUse(r, user, organizationId, flowGroupId))
                .filter(r -> registry.online(r.id()))
                .min(Comparator.comparingInt((Runner r) -> registry.live(r.id()).busy()).thenComparing(Runner::name));
    }

    // ------------------------------------------------------------------ helpers

    private RunnerView view(Runner r, ConcentusUserDetails user) {
        String groupName = r.groupId() == null ? null : groupStore.nameOf(r.groupId()).orElse(null);
        String owner = r.userId() == null ? null
                : accounts.findById(r.userId()).map(Accounts.UserAccount::email).orElse(null);
        boolean mine = user.userId().equals(r.userId()) || (r.createdBy() != null && r.createdBy().equalsIgnoreCase(user.email()));
        return RunnerView.of(r, registry.live(r.id()), groupName, owner, mine,
                mayUse(r, Optional.of(user), user.organizationId(), null));
    }

    private MayCreate mayCreate(ConcentusUserDetails user) {
        boolean admin = isAdmin(user);
        boolean member = Accounts.atLeast(user.role(), Accounts.ROLE_MEMBER);
        List<String> groupIds = new ArrayList<>();
        if (member && !license.withheld(Feature.GROUPS)) {
            if (admin) {
                groupStore.list(user.organizationId()).forEach(g -> groupIds.add(g.id()));
            } else {
                groupIds.addAll(groups.current().managed());
            }
        }
        return new MayCreate(admin, groupIds, member);
    }

    private Runner require(String id, ConcentusUserDetails user) {
        Runner runner = store.find(user.organizationId(), id).orElse(null);
        if (runner == null || !sees(runner, user)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such runner.");
        }
        return runner;
    }

    private void requireManage(Runner runner, ConcentusUserDetails user) {
        if (!mayManage(runner, user)) {
            throw new OrgContext.AccessDeniedForOrganization("Only an administrator, the owner or a manager of its group "
                    + "changes this runner.");
        }
    }

    private static boolean isAdmin(ConcentusUserDetails user) {
        return Accounts.ROLE_ADMIN.equalsIgnoreCase(user.role());
    }

    private String describeScope(Runner r) {
        return switch (r.scope()) {
            case Runner.SCOPE_GROUP -> "the group " + groupStore.nameOf(r.groupId()).map(n -> "'" + n + "'").orElse(r.groupId());
            case Runner.SCOPE_USER -> "one person only";
            default -> "the organization";
        };
    }

    /** Where runners dial: the configured public URL, else wherever this request reached the backend. */
    public String hubUrl() {
        if (!publicUrl.isBlank()) return trimSlashes(publicUrl);
        try {
            return trimSlashes(OAuthCallbacks.requestBase());
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String trimSlashes(String s) {
        String out = s == null ? "" : s.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }
}
