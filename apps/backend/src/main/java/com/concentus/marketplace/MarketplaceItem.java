package com.concentus.marketplace;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * One thing on the marketplace: the definition of an MCP server, an agent, a facade profile, a
 * skill, a plugin, an API endpoint or a flow, published by somebody for others to install.
 *
 * <p>The payload is the definition itself, as JSON, minus what must not travel: an id (the
 * installing organization mints its own) and every credential (a payload names which slots exist
 * — an env key, a header — never their values). Which keys a kind's payload carries is written
 * down in {@link MarketplaceInstaller}, beside the code that turns it back into a resource.
 *
 * @param kind           one of {@link #KINDS}
 * @param version        bumped when the payload changes on re-publish; what an install records
 * @param scope          {@link #SCOPE_ORGANIZATION} or {@link #SCOPE_GLOBAL}
 * @param organizationId the publishing organization — kept on a global item as "from"; null for
 *                       a built-in, which no organization published
 * @param status         {@link #PUBLISHED}, {@link #PENDING} or {@link #REJECTED}
 * @param rejection      the curator's sentence, when rejected
 * @param installs       how many organizations have it installed, deployment-wide
 * @param builtIn        seeded from the bundled library; re-seeded when the bundle changes, never
 *                       edited or deleted by hand
 */
public record MarketplaceItem(String id, String kind, String name, String summary, String description,
                              List<String> tags, int version, String scope, String organizationId,
                              String groupId, String status, String rejection, Author author,
                              JsonNode payload, String icon, int installs, boolean builtIn, long createdAt,
                              long updatedAt, Long publishedAt, String approvedBy) {

    /** The shape before groups: an item of an organization or of everyone, never of a group. */
    public MarketplaceItem(String id, String kind, String name, String summary, String description,
                           List<String> tags, int version, String scope, String organizationId,
                           String status, String rejection, Author author, JsonNode payload,
                           String icon, int installs, boolean builtIn, long createdAt,
                           long updatedAt, Long publishedAt, String approvedBy) {
        this(id, kind, name, summary, description, tags, version, scope, organizationId, null, status,
                rejection, author, payload, icon, installs, builtIn, createdAt, updatedAt, publishedAt, approvedBy);
    }

    public static final String KIND_MCP = "mcp";
    public static final String KIND_AGENT = "agent";
    public static final String KIND_FACADE = "facade";
    public static final String KIND_SKILL = "skill";
    public static final String KIND_PLUGIN = "plugin";
    public static final String KIND_API = "api";
    public static final String KIND_FLOW = "flow";
    public static final List<String> KINDS = List.of(KIND_MCP, KIND_AGENT, KIND_FACADE, KIND_SKILL,
            KIND_PLUGIN, KIND_API, KIND_FLOW);

    public static final String SCOPE_ORGANIZATION = "organization";
    public static final String SCOPE_GLOBAL = "global";
    /** Visible to one group's members and the organization's admins; born published, like the organization's. */
    public static final String SCOPE_GROUP = "group";

    public static final String PUBLISHED = "published";
    public static final String PENDING = "pending";
    public static final String REJECTED = "rejected";

    /** Who published it. Built-ins say {@code system:concentus} in both fields. */
    public record Author(String userId, String email) {
    }

    /** What one organization's install of an item became, as the list shows it. */
    public record Installed(String resourceId, int version, long installedAt) {
    }

    /**
     * An item as one caller sees it: the item, flattened, plus what only makes sense relative to
     * the caller — whether their organization has it installed, whether it is theirs, and what
     * they may do to it. The screen draws its buttons from these three rather than re-deriving
     * the rules.
     */
    public record View(@JsonUnwrapped MarketplaceItem item, Installed installed, boolean mine,
                       boolean canEdit, boolean canCurate) {
    }

    // Ignored, not serialised: a bean-style getter would otherwise appear in the JSON as a
    // property ("global", "published") beside the fields it is derived from.
    @JsonIgnore
    public boolean isGlobal() {
        return SCOPE_GLOBAL.equals(scope);
    }

    @JsonIgnore
    public boolean isPublished() {
        return PUBLISHED.equals(status);
    }

    @JsonIgnore
    public boolean isGroup() {
        return SCOPE_GROUP.equals(scope);
    }

    public List<String> tagsOrEmpty() {
        return tags == null ? List.of() : tags;
    }

    /** For {@link #payloadEquals}: a mapper with nothing configured, so the comparison is of the JSON text. */
    private static final ObjectMapper CANONICAL = new ObjectMapper();

    /**
     * Whether two payloads say the same thing. Compared after a round trip through JSON text,
     * because a node built in code and the same node read back from the database can differ in
     * type without differing in meaning — a {@code long} written as 12000 comes back as an int —
     * and a version bump on a difference nobody can see would tell every installer to update.
     */
    public static boolean payloadEquals(JsonNode a, JsonNode b) {
        try {
            JsonNode left = a == null ? CANONICAL.createObjectNode() : CANONICAL.readTree(CANONICAL.writeValueAsString(a));
            JsonNode right = b == null ? CANONICAL.createObjectNode() : CANONICAL.readTree(CANONICAL.writeValueAsString(b));
            return left.equals(right);
        } catch (Exception e) {
            return false;
        }
    }

    public MarketplaceItem withInstalls(int count) {
        return new MarketplaceItem(id, kind, name, summary, description, tags, version, scope,
                organizationId, groupId, status, rejection, author, payload, icon, count, builtIn, createdAt,
                updatedAt, publishedAt, approvedBy);
    }
}
