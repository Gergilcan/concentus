package com.concentus.marketplace;

import com.concentus.audit.AuditKinds;
import com.concentus.audit.AuditService;
import com.concentus.auth.AccountStore;
import com.concentus.auth.ConcentusUserDetails;
import com.concentus.auth.OrgContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.concentus.marketplace.MarketplaceItem.PENDING;
import static com.concentus.marketplace.MarketplaceItem.PUBLISHED;
import static com.concentus.marketplace.MarketplaceItem.REJECTED;
import static com.concentus.marketplace.MarketplaceItem.SCOPE_GLOBAL;
import static com.concentus.marketplace.MarketplaceItem.SCOPE_GROUP;
import static com.concentus.marketplace.MarketplaceItem.SCOPE_ORGANIZATION;

/**
 * The marketplace: listing what the caller may see, publishing, curating and installing.
 *
 * <p>Two things every route does before anything else. It reads who is asking from
 * {@link OrgContext} — never from the request — and it looks the item up through the store's
 * visibility query, so an item outside what the caller may see answers 404 with the same body as
 * an id that names nothing. The rules of what they may then DO to it live in
 * {@link MarketplacePolicy}; a refusal is a 403 with a sentence.
 *
 * <p>Filters and sort are applied here, after the visibility query: the visible set is a few
 * hundred items at most, and doing the narrowing in one place keeps the SQL to the one question
 * that must be answered in SQL.
 */
@RestController
@RequestMapping("/api/marketplace")
public class MarketplaceController {

    /** Longest name, summary and icon: a card, not a document. */
    static final int MAX_NAME = 80;
    static final int MAX_SUMMARY = 200;
    static final int MAX_ICON = 8;

    private final MarketplaceStore store;
    private final MarketplacePolicy policy;
    private final MarketplaceInstaller installer;
    private final AccountStore accounts;
    private final OrgContext orgContext;
    private final AuditService audit;
    private final ObjectMapper mapper;
    /** The rules of a group: who may publish to one, and the one write that scopes an installed resource to one. */
    private final com.concentus.groups.GroupService groups;

    public MarketplaceController(MarketplaceStore store, MarketplacePolicy policy, MarketplaceInstaller installer,
                                 AccountStore accounts, OrgContext orgContext, AuditService audit,
                                 ObjectMapper mapper, com.concentus.groups.GroupService groups) {
        this.store = store;
        this.policy = policy;
        this.installer = installer;
        this.accounts = accounts;
        this.orgContext = orgContext;
        this.audit = audit;
        this.mapper = mapper;
        this.groups = groups;
    }

    // ------------------------------------------------------------------ request and response shapes

    /**
     * The body of publish and edit.
     *
     * @param groupId the group, when {@code scope} is {@code group}; ignored otherwise
     */
    public record PublishRequest(String kind, String name, String summary, String description,
                                 List<String> tags, String icon, String scope, JsonNode payload, String groupId) {

        /** The shape before groups. */
        public PublishRequest(String kind, String name, String summary, String description,
                              List<String> tags, String icon, String scope, JsonNode payload) {
            this(kind, name, summary, description, tags, icon, scope, payload, null);
        }
    }

    /** "Publish this resource": the resource names the payload; the words are optional. */
    public record PublishFromRequest(String kind, String resourceId, String scope, String name,
                                     String summary, String description, List<String> tags, String icon,
                                     String groupId) {

        /** The shape before groups. */
        public PublishFromRequest(String kind, String resourceId, String scope, String name,
                                  String summary, String description, List<String> tags, String icon) {
            this(kind, resourceId, scope, name, summary, description, tags, icon, null);
        }
    }

    /** The body of install: where the created resource lands — the organization when absent, else a group. */
    public record InstallRequest(String groupId) {
    }

    public record RejectRequest(String reason) {
    }

    /**
     * @param tags    every tag on the visible items, for the chips
     * @param curator whether the caller curates
     * @param pending how many visible items still wait — the badge
     */
    public record Listing(List<MarketplaceItem.View> items, List<String> tags, boolean curator, int pending) {
    }

    /** What an install created. */
    public record InstallResult(String resourceId, String kind, int version) {
    }

    public record Status(boolean curator, int pending, long organizations, List<String> tags) {
    }

    // ------------------------------------------------------------------ reading

    @GetMapping("/items")
    public Listing list(@RequestParam(required = false) String q,
                        @RequestParam(required = false) String kind,
                        @RequestParam(required = false) String scope,
                        @RequestParam(required = false) String tag,
                        @RequestParam(required = false) String status,
                        @RequestParam(required = false) String sort) {
        ConcentusUserDetails me = orgContext.requireUser();
        MarketplaceStore.Viewer viewer = policy.viewerFor(me);
        List<MarketplaceItem> visible = store.listVisible(viewer);

        List<MarketplaceItem> items = new ArrayList<>();
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        for (MarketplaceItem item : visible) {
            if (notBlank(kind) && !kind.equalsIgnoreCase(item.kind())) continue;
            if (notBlank(scope) && !scope.equalsIgnoreCase(item.scope())) continue;
            if (notBlank(status) && !status.equalsIgnoreCase(item.status())) continue;
            if (notBlank(tag) && item.tagsOrEmpty().stream().noneMatch(t -> t.equalsIgnoreCase(tag))) continue;
            if (!needle.isEmpty() && !matches(item, needle)) continue;
            items.add(item);
        }
        items.sort(comparator(sort));

        Map<String, MarketplaceStore.Install> installs = store.installsOf(me.organizationId());
        List<MarketplaceItem.View> views = items.stream().map(i -> view(i, me, viewer, installs)).toList();
        return new Listing(views, tagsOf(visible), viewer.curator(), pendingIn(visible));
    }

    @GetMapping("/items/{id}")
    public MarketplaceItem.View get(@PathVariable String id) {
        ConcentusUserDetails me = orgContext.requireUser();
        MarketplaceStore.Viewer viewer = policy.viewerFor(me);
        MarketplaceItem item = requireVisible(id, viewer);
        return view(item, me, viewer, store.installsOf(me.organizationId()));
    }

    @GetMapping("/status")
    public Status status() {
        ConcentusUserDetails me = orgContext.requireUser();
        MarketplaceStore.Viewer viewer = policy.viewerFor(me);
        List<MarketplaceItem> visible = store.listVisible(viewer);
        return new Status(viewer.curator(), pendingIn(visible), accounts.countOrganizations(), tagsOf(visible));
    }

    // ------------------------------------------------------------------ publishing

    /**
     * Publishes. To the organization: visible to its members at once. To everyone: a submission,
     * {@code pending} until a curator approves it — the author sees it, the curators see it,
     * nobody else does yet.
     */
    @PostMapping("/items")
    public MarketplaceItem.View publish(@RequestBody PublishRequest body) {
        ConcentusUserDetails me = orgContext.requireUser();
        requirePublisher(me);
        MarketplaceItem item = itemFrom(body, me);
        MarketplaceItem saved = store.insert(item, null);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", saved.kind());
        detail.put("scope", saved.scope());
        detail.put("version", saved.version());
        if (saved.groupId() != null) detail.put("groupId", saved.groupId());
        audit.record(AuditKinds.MARKETPLACE_PUBLISHED, "marketplace-item", saved.id(), saved.name(), detail);
        return view(saved, me, policy.viewerFor(me), Map.of());
    }

    /** As {@link #publish}, from a resource the organization already has, with its credentials stripped. */
    @PostMapping("/publish-from")
    public Map<String, Object> publishFrom(@RequestBody PublishFromRequest body) {
        ConcentusUserDetails me = orgContext.requireUser();
        requirePublisher(me);
        if (body == null || body.kind() == null || body.resourceId() == null || body.resourceId().isBlank()) {
            throw new IllegalArgumentException("kind and resourceId are required.");
        }
        MarketplaceInstaller.Built built = installer.fromResource(kindOf(body.kind()), body.resourceId());
        PublishRequest request = new PublishRequest(body.kind(),
                notBlank(body.name()) ? body.name() : built.name(),
                notBlank(body.summary()) ? body.summary() : built.summary(),
                notBlank(body.description()) ? body.description() : built.description(),
                body.tags() == null || body.tags().isEmpty() ? built.tags() : body.tags(),
                body.icon(), body.scope(), built.payload(), body.groupId());
        MarketplaceItem.View published = publish(request);
        Map<String, Object> out = new LinkedHashMap<>(mapper.convertValue(published,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        out.put("stripped", built.stripped());
        return out;
    }

    /**
     * Edits. The version is bumped when the payload changed, which is what tells an organization
     * that installed the old one that an update exists. A non-curator's edit of a global item —
     * or a move of an organization item to global — puts it back in front of the curators: the
     * approval was of what the item was, not of whatever it becomes.
     */
    @PutMapping("/items/{id}")
    public MarketplaceItem.View edit(@PathVariable String id, @RequestBody PublishRequest body) {
        ConcentusUserDetails me = orgContext.requireUser();
        MarketplaceStore.Viewer viewer = policy.viewerFor(me);
        MarketplaceItem existing = requireVisible(id, viewer);
        requireEditable(existing, me, viewer);
        MarketplaceItem edited = itemFrom(body, me);
        if (!edited.kind().equals(existing.kind())) {
            throw new IllegalArgumentException("An item's kind cannot change; publish a new item.");
        }

        boolean payloadChanged = !MarketplaceItem.payloadEquals(existing.payload(), edited.payload());
        int version = payloadChanged ? existing.version() + 1 : existing.version();
        String status;
        Long publishedAt = existing.publishedAt();
        String approvedBy = existing.approvedBy();
        if (SCOPE_GLOBAL.equals(edited.scope()) && !viewer.curator()) {
            status = PENDING;
            publishedAt = null;
            approvedBy = null;
        } else if (SCOPE_GLOBAL.equals(edited.scope())) {
            status = REJECTED.equals(existing.status()) ? PENDING : existing.status();
        } else {
            status = PUBLISHED;
            if (publishedAt == null) publishedAt = System.currentTimeMillis();
        }
        long now = System.currentTimeMillis();
        // The group comes from the edited request, already checked by itemFrom: moving an item
        // into a group takes membership of it, and moving it out clears the column.
        MarketplaceItem toSave = new MarketplaceItem(existing.id(), existing.kind(), edited.name(),
                edited.summary(), edited.description(), edited.tags(), version, edited.scope(),
                existing.organizationId(), edited.groupId(), status,
                PENDING.equals(status) ? null : existing.rejection(),
                existing.author(), edited.payload(), edited.icon(), existing.installs(), false,
                existing.createdAt(), now, publishedAt, approvedBy);
        store.update(toSave, null);
        audit.record(AuditKinds.MARKETPLACE_PUBLISHED, "marketplace-item", toSave.id(), toSave.name(),
                Map.of("kind", toSave.kind(), "scope", toSave.scope(), "version", toSave.version(), "edited", true));
        return view(store.find(id).orElse(toSave), me, viewer, store.installsOf(me.organizationId()));
    }

    /** Removes an item. The resources organizations installed from it stay theirs. */
    @DeleteMapping("/items/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        ConcentusUserDetails me = orgContext.requireUser();
        MarketplaceStore.Viewer viewer = policy.viewerFor(me);
        MarketplaceItem existing = requireVisible(id, viewer);
        requireEditable(existing, me, viewer);
        store.delete(id);
        return Map.of("deleted", true, "id", id);
    }

    // ------------------------------------------------------------------ curation

    @PostMapping("/items/{id}/approve")
    public MarketplaceItem.View approve(@PathVariable String id) {
        ConcentusUserDetails me = orgContext.requireUser();
        MarketplaceStore.Viewer viewer = policy.viewerFor(me);
        MarketplaceItem item = requireVisible(id, viewer);
        requireCurator(item, viewer);
        if (item.isPublished()) {
            throw new IllegalStateException("This item is already published.");
        }
        long now = System.currentTimeMillis();
        MarketplaceItem approved = new MarketplaceItem(item.id(), item.kind(), item.name(), item.summary(),
                item.description(), item.tags(), item.version(), item.scope(), item.organizationId(),
                PUBLISHED, null, item.author(), item.payload(), item.icon(), item.installs(), item.builtIn(),
                item.createdAt(), now, now, me.email());
        store.update(approved, null);
        audit.record(AuditKinds.MARKETPLACE_APPROVED, "marketplace-item", item.id(), item.name(),
                Map.of("kind", item.kind(), "version", item.version()));
        return view(approved, me, viewer, store.installsOf(me.organizationId()));
    }

    @PostMapping("/items/{id}/reject")
    public MarketplaceItem.View reject(@PathVariable String id, @RequestBody RejectRequest body) {
        ConcentusUserDetails me = orgContext.requireUser();
        MarketplaceStore.Viewer viewer = policy.viewerFor(me);
        MarketplaceItem item = requireVisible(id, viewer);
        requireCurator(item, viewer);
        String reason = body == null || body.reason() == null ? "" : body.reason().trim();
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("A rejection needs a reason — the sentence the author will read.");
        }
        long now = System.currentTimeMillis();
        MarketplaceItem rejected = new MarketplaceItem(item.id(), item.kind(), item.name(), item.summary(),
                item.description(), item.tags(), item.version(), item.scope(), item.organizationId(),
                REJECTED, reason, item.author(), item.payload(), item.icon(), item.installs(), item.builtIn(),
                item.createdAt(), now, null, null);
        store.update(rejected, null);
        audit.record(AuditKinds.MARKETPLACE_REJECTED, "marketplace-item", item.id(), item.name(),
                Map.of("kind", item.kind(), "version", item.version(), "reason", reason));
        return view(rejected, me, viewer, store.installsOf(me.organizationId()));
    }

    // ------------------------------------------------------------------ installing

    /**
     * Creates the item's resource in the caller's current organization and records it. Installing
     * again is an update: the resource the earlier install created is rewritten when it is still
     * there, and re-created when it was deleted.
     */
    @PostMapping("/items/{id}/install")
    public InstallResult install(@PathVariable String id, @RequestBody(required = false) InstallRequest body) {
        ConcentusUserDetails me = orgContext.requireUser();
        requireInstaller(me);
        MarketplaceStore.Viewer viewer = policy.viewerFor(me);
        MarketplaceItem item = requireVisible(id, viewer);
        boolean ours = item.organizationId() != null && item.organizationId().equals(me.organizationId());
        if (!item.isPublished() && !ours) {
            throw new IllegalStateException("This item is " + item.status() + " and cannot be installed yet.");
        }
        // Into a group: checked before anything is created, so a refusal leaves nothing behind
        // that the caller would then see and the group would not.
        String groupId = body == null || body.groupId() == null || body.groupId().isBlank()
                ? null : body.groupId().trim();
        String storeKind = groupId == null ? null : storeKindOf(item.kind());
        if (groupId != null) groups.requireMemberOf(groupId);
        String existing = store.install(item.id(), me.organizationId())
                .map(MarketplaceStore.Install::resourceId)
                .filter(r -> installer.resourceExists(item.kind(), r))
                .orElse(null);
        String resourceId = installer.install(item, existing);
        if (groupId != null && resourceId != null) groups.assign(storeKind, resourceId, groupId);
        store.recordInstall(new MarketplaceStore.Install(item.id(), me.organizationId(), resourceId,
                item.version(), System.currentTimeMillis(), me.email()));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", item.kind());
        detail.put("version", item.version());
        if (resourceId != null) detail.put("resourceId", resourceId);
        if (groupId != null) detail.put("groupId", groupId);
        audit.record(AuditKinds.MARKETPLACE_INSTALLED, "marketplace-item", item.id(), item.name(), detail);
        return new InstallResult(resourceId, item.kind(), item.version());
    }

    /** Into the organization — the call every caller from before groups makes. */
    public InstallResult install(String id) {
        return install(id, null);
    }

    /** The resource store an item's kind installs into, for scoping what was installed. */
    private static String storeKindOf(String kind) {
        return switch (kind) {
            case MarketplaceItem.KIND_MCP -> "mcp";
            case MarketplaceItem.KIND_AGENT -> "agent";
            case MarketplaceItem.KIND_FACADE -> "facade-profile";
            case MarketplaceItem.KIND_SKILL -> "skill";
            case MarketplaceItem.KIND_FLOW -> "flow";
            default -> throw new IllegalArgumentException("A " + kind + " creates no resource of the "
                    + "organization's, so it cannot be installed into a group.");
        };
    }

    /** Removes the resource the install created, if it is still there, and the record of the install. */
    @PostMapping("/items/{id}/uninstall")
    public Map<String, Object> uninstall(@PathVariable String id) {
        ConcentusUserDetails me = orgContext.requireUser();
        requireInstaller(me);
        MarketplaceStore.Viewer viewer = policy.viewerFor(me);
        MarketplaceItem item = requireVisible(id, viewer);
        MarketplaceStore.Install install = store.install(item.id(), me.organizationId())
                .orElseThrow(() -> new IllegalStateException("This item is not installed in this organization."));
        installer.uninstall(item.kind(), install.resourceId());
        store.removeInstall(item.id(), me.organizationId());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", item.kind());
        if (install.resourceId() != null) detail.put("resourceId", install.resourceId());
        audit.record(AuditKinds.MARKETPLACE_UNINSTALLED, "marketplace-item", item.id(), item.name(), detail);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uninstalled", true);
        out.put("resourceId", install.resourceId());
        return out;
    }

    // ------------------------------------------------------------------ rules

    private MarketplaceItem requireVisible(String id, MarketplaceStore.Viewer viewer) {
        return store.findVisible(id, viewer).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such marketplace item."));
    }

    private void requirePublisher(ConcentusUserDetails me) {
        if (!policy.canPublish(me)) {
            throw new OrgContext.AccessDeniedForOrganization(
                    "Publishing to the marketplace requires the Member role or above.");
        }
    }

    private void requireInstaller(ConcentusUserDetails me) {
        if (!policy.canInstall(me)) {
            throw new OrgContext.AccessDeniedForOrganization(
                    "Installing from the marketplace requires the Operator role or above.");
        }
    }

    private void requireEditable(MarketplaceItem item, ConcentusUserDetails me, MarketplaceStore.Viewer viewer) {
        if (item.builtIn()) {
            throw new OrgContext.AccessDeniedForOrganization(
                    "Built-in items cannot be edited or deleted; they are re-seeded from the bundled library.");
        }
        if (!policy.canEdit(item, me, viewer.curator())) {
            throw new OrgContext.AccessDeniedForOrganization(
                    "Only the author, a curator (for global items) or an administrator of the publishing "
                            + "organization may change this item.");
        }
    }

    private void requireCurator(MarketplaceItem item, MarketplaceStore.Viewer viewer) {
        if (!policy.canCurate(item, viewer.curator())) {
            throw new OrgContext.AccessDeniedForOrganization(
                    "Approving and rejecting is for the curators — the administrators of the curating "
                            + "organization — and only on global items.");
        }
    }

    // ------------------------------------------------------------------ building

    /** The item a request describes, validated, for the caller as author. Not yet saved. */
    private MarketplaceItem itemFrom(PublishRequest body, ConcentusUserDetails me) {
        if (body == null) throw new IllegalArgumentException("A body is required.");
        String kind = kindOf(body.kind());
        String scope = scopeOf(body.scope());
        String name = requireText(body.name(), "A name is required.", MAX_NAME, "name");
        String summary = body.summary() == null ? "" : body.summary().trim();
        if (summary.length() > MAX_SUMMARY) {
            throw new IllegalArgumentException("The summary is limited to " + MAX_SUMMARY + " characters.");
        }
        String icon = body.icon() == null || body.icon().isBlank() ? null : body.icon().trim();
        if (icon != null && icon.length() > MAX_ICON) {
            throw new IllegalArgumentException("The icon is an emoji or a short glyph.");
        }
        JsonNode payload = body.payload();
        installer.validate(kind, payload);
        // To a group: the caller must be in it (or administer the organization), and the license
        // must allow groups — both the group service's questions. Born published, like an
        // organization item: its audience is the group, and nobody curates inside a group.
        String groupId = null;
        if (SCOPE_GROUP.equals(scope)) {
            if (body.groupId() == null || body.groupId().isBlank()) {
                throw new IllegalArgumentException("Publishing to a group names the group (groupId).");
            }
            groupId = groups.requireMemberOf(body.groupId().trim()).id();
        }
        long now = System.currentTimeMillis();
        boolean global = SCOPE_GLOBAL.equals(scope);
        return new MarketplaceItem(null, kind, name, summary,
                body.description() == null || body.description().isBlank() ? null : body.description(),
                tags(body.tags()), 1, scope, me.organizationId(), groupId, global ? PENDING : PUBLISHED, null,
                new MarketplaceItem.Author(me.userId(), me.email()), payload, icon, 0, false, now, now,
                global ? null : now, null);
    }

    private static String kindOf(String kind) {
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if (!MarketplaceItem.KINDS.contains(k)) {
            throw new IllegalArgumentException("Unknown kind '" + kind + "'. One of: "
                    + String.join(", ", MarketplaceItem.KINDS) + ".");
        }
        return k;
    }

    private static String scopeOf(String scope) {
        if (scope == null || scope.isBlank()) return SCOPE_ORGANIZATION;
        String s = scope.trim().toLowerCase(Locale.ROOT);
        if (!SCOPE_ORGANIZATION.equals(s) && !SCOPE_GLOBAL.equals(s) && !SCOPE_GROUP.equals(s)) {
            throw new IllegalArgumentException("The scope is 'organization', 'group' or 'global'.");
        }
        return s;
    }

    private static String requireText(String value, String missing, int max, String what) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(missing);
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException("The " + what + " is limited to " + max + " characters.");
        }
        return trimmed;
    }

    private static List<String> tags(List<String> tags) {
        Set<String> out = new LinkedHashSet<>();
        if (tags != null) {
            for (String t : tags) {
                if (t != null && !t.isBlank()) out.add(t.trim());
            }
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ views

    private MarketplaceItem.View view(MarketplaceItem item, ConcentusUserDetails me, MarketplaceStore.Viewer viewer,
                                      Map<String, MarketplaceStore.Install> installs) {
        MarketplaceStore.Install install = installs.get(item.id());
        MarketplaceItem.Installed installed = install == null ? null
                : new MarketplaceItem.Installed(install.resourceId(), install.version(), install.installedAt());
        return new MarketplaceItem.View(item, installed, policy.isAuthor(item, me),
                policy.canEdit(item, me, viewer.curator()), policy.canCurate(item, viewer.curator()));
    }

    private static List<String> tagsOf(List<MarketplaceItem> items) {
        Set<String> tags = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (MarketplaceItem item : items) tags.addAll(item.tagsOrEmpty());
        return List.copyOf(tags);
    }

    private static int pendingIn(List<MarketplaceItem> items) {
        return (int) items.stream().filter(i -> PENDING.equals(i.status())).count();
    }

    private static boolean matches(MarketplaceItem item, String needle) {
        if (item.name() != null && item.name().toLowerCase(Locale.ROOT).contains(needle)) return true;
        if (item.summary() != null && item.summary().toLowerCase(Locale.ROOT).contains(needle)) return true;
        return item.tagsOrEmpty().stream().anyMatch(t -> t.toLowerCase(Locale.ROOT).contains(needle));
    }

    private static Comparator<MarketplaceItem> comparator(String sort) {
        Comparator<MarketplaceItem> byName = Comparator.comparing(i -> i.name() == null ? "" : i.name(),
                String.CASE_INSENSITIVE_ORDER);
        String s = sort == null ? "" : sort.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "installs" -> Comparator.comparingInt(MarketplaceItem::installs).reversed().thenComparing(byName);
            case "name" -> byName;
            default -> Comparator.comparingLong(MarketplaceItem::createdAt).reversed().thenComparing(byName);
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
