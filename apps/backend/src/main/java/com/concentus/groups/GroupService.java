package com.concentus.groups;

import com.concentus.audit.AuditKinds;
import com.concentus.audit.AuditService;
import com.concentus.auth.AccountStore;
import com.concentus.auth.Accounts;
import com.concentus.auth.ConcentusUserDetails;
import com.concentus.auth.OrgContext;
import com.concentus.config.SettingDef;
import com.concentus.config.Settings;
import com.concentus.config.SettingsCatalog;
import com.concentus.config.SettingsStore;
import com.concentus.license.Feature;
import com.concentus.license.LicenseService;
import com.concentus.policy.OrgPolicy;
import com.concentus.policy.OrgPolicyService;
import com.concentus.secrets.CredentialStore;
import com.concentus.store.JsonStore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules of groups, in one place: who may see a group, who may change it, what a group may
 * override, and the one gate every write goes through.
 *
 * <p><b>The gate.</b> {@link Feature#GROUPS} is Enterprise. Every write here refuses with the
 * feature's own sentence where it is withheld; every read keeps working, so what a group already
 * scopes stays scoped and visible to exactly the same people after a downgrade — a downgrade
 * never widens who sees what.
 *
 * <p><b>Who.</b> An ADMIN of the organization sees every group and is the only one who creates,
 * renames or deletes one. A manager of a group adds and removes its members and edits its
 * settings and policy. Everybody else sees the groups they are in, and nothing of the others: a
 * group the caller may not see answers 404 with the same body as one that does not exist.
 *
 * <p><b>What a group scopes.</b> {@link #assign} is the one write that changes a resource's
 * group — the stores never touch the column on a save — and it is audited by resource. Moving a
 * resource into a group takes membership of that group (or ADMIN); moving it out takes seeing it,
 * which is the same thing.
 */
@Service
public class GroupService {

    /** The store kinds a resource may be scoped by. Policies, approvals, evals and watch state are not resources a person picks. */
    public static final Set<String> ASSIGNABLE_KINDS = Set.of("flow", "mcp", "agent", "facade-profile",
            "knowledge", "skill", "variable", "database");
    /** The kind {@link #assign} takes for a credential, which lives in its own table. */
    public static final String KIND_CREDENTIAL = "credential";

    static final int MAX_NAME = 80;

    /** What the Settings tab of a group reads and writes. */
    public record SettingsView(Map<String, String> values, List<Map<String, Object>> keys,
                               Map<String, String> inherited) {
    }

    /**
     * The group's policy as saved (nulls inherit), flattened, beside what a flow of the group
     * actually runs under once the organization's policy is laid underneath.
     */
    public record PolicyView(@JsonUnwrapped GroupPolicy policy, OrgPolicy effective, boolean enforced,
                             String refusal) {
    }

    public record Assignment(String kind, String resourceId, String groupId) {
    }

    public record Status(boolean allowed, String refusal, int groups, List<Group.Ref> mine) {
    }

    private final OrgContext orgContext;
    private final GroupContext context;
    private final GroupStore store;
    private final GroupPolicyStore policies;
    private final SettingsStore settingsStore;
    private final Settings settings;
    private final LicenseService license;
    private final AccountStore accounts;
    private final AuditService audit;
    private final OrgPolicyService orgPolicies;
    private final CredentialStore credentials;
    private final ObjectMapper mapper;
    /** The resource stores by kind, for {@link #assign}. */
    private final Map<String, JsonStore<?>> stores = new LinkedHashMap<>();

    public GroupService(OrgContext orgContext, GroupContext context, GroupStore store, GroupPolicyStore policies,
                        SettingsStore settingsStore, Settings settings, LicenseService license,
                        AccountStore accounts, AuditService audit, OrgPolicyService orgPolicies,
                        CredentialStore credentials, List<JsonStore<?>> resourceStores, ObjectMapper mapper) {
        this.orgContext = orgContext;
        this.context = context;
        this.store = store;
        this.policies = policies;
        this.settingsStore = settingsStore;
        this.settings = settings;
        this.license = license;
        this.accounts = accounts;
        this.audit = audit;
        this.orgPolicies = orgPolicies;
        this.credentials = credentials;
        this.mapper = mapper;
        for (JsonStore<?> s : resourceStores) {
            if (ASSIGNABLE_KINDS.contains(s.kind())) stores.put(s.kind(), s);
        }
    }

    // ------------------------------------------------------------------ the gate

    public boolean allowed() {
        return license.allows(Feature.GROUPS);
    }

    /** The feature's sentence where it is withheld; null where groups work. */
    public String refusal() {
        return license.refusal(Feature.GROUPS);
    }

    private void requireAllowed() {
        if (!allowed()) throw new OrgContext.AccessDeniedForOrganization(refusal());
    }

    // ------------------------------------------------------------------ groups

    /** The groups the caller may see: every one for an admin, otherwise their own. */
    public List<Group> visible() {
        String organizationId = orgContext.requireOrganizationId();
        GroupContext.Memberships mine = context.current();
        boolean admin = context.isAdmin();
        List<Group> out = new ArrayList<>();
        for (Group g : store.list(organizationId)) {
            if (admin || mine.groupIds().contains(g.id())) out.add(g.asSeenBy(mine.managed().contains(g.id())));
        }
        return out;
    }

    /** The caller's own groups, as the session carries them. */
    public List<Group.Ref> mine() {
        GroupContext.Memberships mine = context.current();
        if (mine.groupIds().isEmpty()) return List.of();
        List<Group.Ref> out = new ArrayList<>();
        for (Group g : store.list(orgContext.requireOrganizationId())) {
            if (mine.groupIds().contains(g.id())) out.add(new Group.Ref(g.id(), g.name(), mine.managed().contains(g.id())));
        }
        return out;
    }

    public Status status() {
        return new Status(allowed(), refusal(), visible().size(), mine());
    }

    public Group create(String name, String description) {
        requireAllowed();
        orgContext.requireAdmin();
        ConcentusUserDetails me = orgContext.requireUser();
        Group created = store.create(me.organizationId(), requireName(name), clean(description), me.email());
        audit.record(AuditKinds.GROUP_CREATED, "group", created.id(), created.name(), null);
        return created;
    }

    public Group update(String id, String name, String description) {
        requireAllowed();
        Group group = requireManager(requireVisible(id));
        Group updated = store.update(group.organizationId(), id, requireName(name), clean(description))
                .orElseThrow(GroupService::notFound);
        audit.record(AuditKinds.GROUP_UPDATED, "group", id, updated.name(),
                group.name().equals(updated.name()) ? null : Map.of("renamedFrom", group.name()));
        return updated.asSeenBy(group.manager());
    }

    /** Admin only. Un-scopes what the group held; never deletes a resource. */
    public GroupStore.Deleted delete(String id) {
        requireAllowed();
        orgContext.requireAdmin();
        Group group = requireVisible(id);
        settingsStore.clearGroup(id);
        if (policies.isAvailable()) policies.delete(id);
        GroupStore.Deleted deleted = store.delete(group.organizationId(), id);
        if (!deleted.deleted()) throw notFound();
        audit.record(AuditKinds.GROUP_DELETED, "group", id, group.name(),
                Map.of("members", group.members(), "unscoped", deleted.unscoped()));
        return deleted;
    }

    // ------------------------------------------------------------------ members

    public List<GroupMember> members(String id) {
        Group group = requireVisible(id);
        return store.members(group.organizationId(), id);
    }

    /** Puts an account of the organization into the group, or changes whether it manages it. */
    public GroupMember addMember(String id, String userId, boolean manager) {
        requireAllowed();
        Group group = requireManager(requireVisible(id));
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("A userId is required.");
        Accounts.UserAccount account = accounts.findMember(userId, group.organizationId())
                .orElseThrow(() -> new IllegalArgumentException("That account is not a member of this organization."));
        store.addMember(id, account.id(), manager);
        audit.record(AuditKinds.GROUP_MEMBER_ADDED, "group", id, group.name(),
                Map.of("userId", account.id(), "email", account.email(), "manager", manager));
        return store.members(group.organizationId(), id).stream()
                .filter(m -> m.userId().equals(account.id())).findFirst()
                .orElseThrow(() -> new IllegalStateException("The membership was not written."));
    }

    public void removeMember(String id, String userId) {
        requireAllowed();
        Group group = requireManager(requireVisible(id));
        String email = store.members(group.organizationId(), id).stream()
                .filter(m -> m.userId().equals(userId)).map(GroupMember::email).findFirst().orElse(null);
        if (!store.removeMember(id, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That account is not in this group.");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("userId", userId);
        if (email != null) detail.put("email", email);
        audit.record(AuditKinds.GROUP_MEMBER_REMOVED, "group", id, group.name(), detail);
    }

    // ------------------------------------------------------------------ settings

    /**
     * The group's overrides, the keys it may override (with the catalogue's words for each), and
     * what each key is worth without the override — so the tab can show "inherited" muted.
     */
    public SettingsView settings(String id) {
        Group group = requireVisible(id);
        Map<String, String> inherited = new LinkedHashMap<>();
        List<Map<String, Object>> keys = new ArrayList<>();
        for (SettingDef def : SettingsCatalog.groupScoped()) {
            keys.add(describe(def));
            inherited.put(def.key(), settings.forOrganization(group.organizationId(), def.key()).orElse(""));
        }
        return new SettingsView(settingsStore.groupSettings(id), keys, inherited);
    }

    /** Replaces the group's overrides. Only group-scoped keys; a blank clears; what is absent is cleared. */
    public SettingsView replaceSettings(String id, Map<String, String> values) {
        requireAllowed();
        Group group = requireManager(requireVisible(id));
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : (values == null ? Map.<String, String>of() : values).entrySet()) {
            SettingDef def = SettingsCatalog.byKey(e.getKey()).orElseThrow(() ->
                    new IllegalArgumentException("Unknown setting '" + e.getKey() + "'."));
            if (!def.groupScoped()) {
                throw new IllegalArgumentException("'" + def.key() + "' is read once for the whole deployment "
                        + "and cannot be set per group.");
            }
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (value.isEmpty()) continue;
            if (def.type() == SettingDef.Type.CHOICE && !def.options().contains(value)) {
                throw new IllegalArgumentException("'" + value + "' is not one of " + String.join(", ", def.options())
                        + " for '" + def.key() + "'.");
            }
            if (def.type() == SettingDef.Type.NUMBER) {
                try {
                    Long.parseLong(value);
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("'" + def.key() + "' takes a whole number.");
                }
            }
            clean.put(def.key(), value);
        }
        settingsStore.replaceGroupSettings(group.organizationId(), id, clean);
        // The keys, never the values: the trail says what was overridden, the tab says with what.
        audit.record(AuditKinds.GROUP_SETTINGS_CHANGED, "group", id, group.name(),
                Map.of("keys", List.copyOf(clean.keySet())));
        return settings(id);
    }

    private static Map<String, Object> describe(SettingDef def) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", def.key());
        out.put("label", def.label());
        out.put("group", def.group());
        out.put("description", def.help());
        out.put("type", def.type().name());
        out.put("options", def.options());
        out.put("restartRequired", def.restartRequired());
        out.put("groupScoped", true);
        return out;
    }

    // ------------------------------------------------------------------ policy

    public PolicyView policy(String id) {
        requireVisible(id);
        return policyView(id);
    }

    public PolicyView savePolicy(String id, GroupPolicy draft) {
        requireAllowed();
        Group group = requireManager(requireVisible(id));
        GroupPolicy normalized = (draft == null ? GroupPolicy.NONE : draft).normalized();
        if (normalized.inheritsEverything()) {
            policies.delete(id);
        } else {
            policies.save(group.organizationId(), id, normalized);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("requireFacade", normalized.requireFacade());
        detail.put("maxPermissionMode", normalized.maxPermissionMode());
        detail.put("monthlyBudgetUsd", normalized.monthlyBudgetUsd());
        detail.put("publishRequiresApproval", normalized.publishRequiresApproval());
        detail.put("defaultFacadeProfileId", normalized.defaultFacadeProfileId());
        detail.values().removeIf(v -> v == null);
        audit.record(AuditKinds.GROUP_POLICY_CHANGED, "group", id, group.name(), detail);
        return policyView(id);
    }

    private PolicyView policyView(String id) {
        return new PolicyView(policies.get(id).orElse(GroupPolicy.NONE), orgPolicies.effectiveForGroup(id),
                orgPolicies.enforced() && allowed(), refusal());
    }

    // ------------------------------------------------------------------ assigning resources

    /**
     * Scopes a resource to a group, or back to the organization with a null {@code groupId}.
     *
     * @throws ResponseStatusException 404 for a resource or a group the caller may not see
     */
    public Assignment assign(String kind, String resourceId, String groupId) {
        requireAllowed();
        String organizationId = orgContext.requireOrganizationId();
        String k = kind == null ? "" : kind.trim();
        if (resourceId == null || resourceId.isBlank()) throw new IllegalArgumentException("A resourceId is required.");
        String target = groupId == null || groupId.isBlank() ? null : groupId.trim();
        if (target != null) requireVisible(target);   // seeing it is being in it, or administering

        String previous;
        String label;
        if (KIND_CREDENTIAL.equals(k)) {
            CredentialStore.Credential credential = credentials.find(organizationId, resourceId)
                    .orElseThrow(GroupService::noSuchResource);
            previous = credential.groupId();
            label = credential.label();
            credentials.assignGroup(organizationId, resourceId, target);
        } else {
            JsonStore<?> resources = stores.get(k);
            if (resources == null) {
                throw new IllegalArgumentException("'" + kind + "' is not a kind of resource a group can hold. One of: "
                        + String.join(", ", ASSIGNABLE_KINDS.stream().sorted().toList()) + ", " + KIND_CREDENTIAL + ".");
            }
            Object item = resources.get(resourceId).orElseThrow(GroupService::noSuchResource);
            previous = resources.groupOf(resourceId).orElse(null);
            label = labelOf(item, resourceId);
            resources.assignGroup(organizationId, resourceId, target);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", k);
        if (previous != null) detail.put("from", previous);
        if (target != null) detail.put("to", target);
        audit.record(AuditKinds.RESOURCE_GROUP_CHANGED, k, resourceId, label, detail);
        return new Assignment(k, resourceId, target);
    }

    /** What the resource calls itself, for the audit row — a flow's name, an MCP server's, a variable's. */
    private String labelOf(Object item, String fallback) {
        try {
            JsonNode node = mapper.valueToTree(item);
            for (String field : List.of("name", "label", "title")) {
                JsonNode value = node.path(field);
                if (value.isTextual() && !value.asText().isBlank()) return value.asText();
            }
        } catch (RuntimeException e) {
            // A label is a nicety of the trail, never a reason to refuse the move.
        }
        return fallback;
    }

    // ------------------------------------------------------------------ rules

    /**
     * The group, for a caller about to put something into it — a marketplace item, an install:
     * the license must allow groups, and the caller must be in the group or administer the
     * organization. 404 for a group they may not see, 403 with the feature's sentence on Team.
     */
    public Group requireMemberOf(String groupId) {
        requireAllowed();
        return requireVisible(groupId);
    }

    /** The group, if the caller may see it; 404 otherwise, with the manager flag filled for them. */
    Group requireVisible(String id) {
        if (id == null || id.isBlank()) throw notFound();
        Group group = store.find(orgContext.requireOrganizationId(), id).orElseThrow(GroupService::notFound);
        if (!context.isAdmin() && !context.isMember(id)) throw notFound();
        return group.asSeenBy(context.manages(id));
    }

    private Group requireManager(Group group) {
        if (context.isAdmin() || group.manager()) return group;
        throw new OrgContext.AccessDeniedForOrganization(
                "Only an administrator or a manager of this group may change it.");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such group.");
    }

    private static ResponseStatusException noSuchResource() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such resource.");
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("A name is required.");
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME) {
            throw new IllegalArgumentException("The name is limited to " + MAX_NAME + " characters.");
        }
        return trimmed;
    }

    private static String clean(String description) {
        return description == null || description.isBlank() ? null : description.trim();
    }
}
