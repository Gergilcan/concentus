package com.concentus.marketplace;

import com.concentus.audit.AuditKinds;
import com.concentus.audit.AuditService;
import com.concentus.auth.AccountStore;
import com.concentus.auth.Accounts;
import com.concentus.auth.ConcentusUserDetails;
import com.concentus.auth.OrgContext;
import com.concentus.config.Settings;
import com.concentus.config.SettingsCatalog;
import com.concentus.config.SettingsStore;
import com.concentus.marketplace.MarketplaceController.PublishFromRequest;
import com.concentus.marketplace.MarketplaceController.PublishRequest;
import com.concentus.marketplace.MarketplaceController.RejectRequest;
import com.concentus.model.FacadeProfile;
import com.concentus.model.FlowEdge;
import com.concentus.model.FlowGraph;
import com.concentus.model.FlowNode;
import com.concentus.model.LibraryAgent;
import com.concentus.model.McpDef;
import com.concentus.model.SkillDef;
import com.concentus.service.PluginRegistry;
import com.concentus.service.SkillService;
import com.concentus.store.AgentLibraryStore;
import com.concentus.store.FacadeProfileStore;
import com.concentus.store.FlowStore;
import com.concentus.store.FlowVersionStore;
import com.concentus.store.McpDefStore;
import com.concentus.store.SkillStore;
import com.concentus.store.TestDatabase;
import com.concentus.store.TestStores;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules of §4, one by one, against the real store and the real resource stores on the test
 * database. The organization and the role come from the security context the way they do in
 * production; only the audit trail and the plugin CLI are mocked — the first to assert the rows,
 * the second because there is no CLI here.
 */
class MarketplaceControllerTest {

    @TempDir
    Path dataDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final OrgContext orgContext = new OrgContext("default");
    private final MockEnvironment env = new MockEnvironment();
    private final AuditService audit = mock(AuditService.class);
    private final PluginRegistry plugins = mock(PluginRegistry.class);

    private JdbcTemplate jdbc;
    private AccountStore accounts;
    private MarketplaceStore store;
    private McpDefStore mcps;
    private AgentLibraryStore agents;
    private FacadeProfileStore facades;
    private SkillStore skills;
    private FlowStore flows;
    private FlowVersionStore versions;
    private MarketplaceController controller;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.jdbc();
        TestDatabase.reset(jdbc);
        for (String table : List.of("marketplace_installs", "marketplace_items", "memberships", "users", "organizations")) {
            jdbc.update("delete from " + table);
        }
        accounts = new AccountStore(jdbc);
        // org_a first: the oldest organization curates unless the setting says otherwise.
        accounts.createOrganization("org_a", "A");
        accounts.createOrganization("org_b", "B");

        store = new MarketplaceStore(jdbc, mapper);
        store.init();
        Settings settings = new Settings(new SettingsStore(null, null) {
            @Override
            public Optional<String> get(String organizationId, String key) {
                return Optional.empty();
            }
        }, env, orgContext);
        MarketplacePolicy policy = new MarketplacePolicy(settings, accounts, store);
        mcps = TestStores.mcpDefs(jdbc, dataDir, mapper, orgContext);
        agents = TestStores.agents(jdbc, dataDir, mapper, orgContext);
        facades = TestStores.facades(jdbc, mapper, orgContext);
        skills = TestStores.skills(jdbc, mapper, orgContext);
        flows = TestStores.flows(jdbc, dataDir, mapper, orgContext);
        versions = TestStores.flowVersions(jdbc, mapper);
        MarketplaceInstaller installer = new MarketplaceInstaller(mcps, agents, facades, skills, new SkillService(),
                plugins, flows, versions, orgContext, mapper);
        controller = new MarketplaceController(store, policy, installer, accounts, orgContext, audit, mapper);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ fixtures

    private static ConcentusUserDetails signIn(String userId, String organizationId, String role) {
        ConcentusUserDetails user = new ConcentusUserDetails(userId, organizationId, userId + "@x.test", "hash", role, true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        return user;
    }

    private ObjectNode mcpPayload(String name) {
        return mapper.createObjectNode().put("name", name).put("url", "https://" + name.toLowerCase() + ".test/mcp")
                .put("auth", "oauth");
    }

    private static PublishRequest publish(String kind, String name, String scope, JsonNode payload) {
        return new PublishRequest(kind, name, "one line about " + name, null, List.of("tag-" + name), null, scope, payload);
    }

    private MarketplaceItem.View publishAs(String userId, String org, String role, String scope, String name) {
        signIn(userId, org, role);
        return controller.publish(publish("mcp", name, scope, mcpPayload(name)));
    }

    private static void assertStatus(Throwable t, HttpStatus status) {
        assertThat(t).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }

    // ------------------------------------------------------------------ organization scope

    @Test
    void a_member_publishes_to_the_organization_and_colleagues_see_it_at_once() {
        MarketplaceItem.View published = publishAs("alice", "org_a", Accounts.ROLE_MEMBER, "organization", "Linear");

        assertThat(published.item().status()).isEqualTo("published");
        assertThat(published.item().publishedAt()).isNotNull();
        assertThat(published.item().organizationId()).isEqualTo("org_a");
        assertThat(published.item().author()).isEqualTo(new MarketplaceItem.Author("alice", "alice@x.test"));
        assertThat(published.mine()).isTrue();
        assertThat(published.canEdit()).isTrue();
        assertThat(published.canCurate()).isFalse();
        verify(audit).record(eq(AuditKinds.MARKETPLACE_PUBLISHED), eq("marketplace-item"),
                eq(published.item().id()), eq("Linear"), any());

        signIn("bob", "org_a", Accounts.ROLE_VIEWER);
        assertThat(controller.list(null, null, null, null, null, null).items())
                .extracting(v -> v.item().name()).containsExactly("Linear");
        assertThat(controller.get(published.item().id()).mine()).isFalse();
    }

    @Test
    void another_organization_gets_404_for_it_never_403() {
        String id = publishAs("alice", "org_a", Accounts.ROLE_MEMBER, "organization", "Linear").item().id();

        signIn("carol", "org_b", Accounts.ROLE_ADMIN);
        assertThat(controller.list(null, null, null, null, null, null).items()).isEmpty();
        assertStatus(catchThrowable(() -> controller.get(id)), HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> controller.edit(id, publish("mcp", "X", "organization", mcpPayload("X")))),
                HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> controller.delete(id)), HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> controller.install(id)), HttpStatus.NOT_FOUND);
        assertStatus(catchThrowable(() -> controller.approve(id)), HttpStatus.NOT_FOUND);
        // And exactly the same for an id nothing has.
        assertStatus(catchThrowable(() -> controller.get("mkt_000000000000")), HttpStatus.NOT_FOUND);
    }

    @Test
    void viewers_and_operators_cannot_publish() {
        for (String role : List.of(Accounts.ROLE_VIEWER, Accounts.ROLE_OPERATOR)) {
            signIn("v", "org_a", role);
            assertThatThrownBy(() -> controller.publish(publish("mcp", "X", "organization", mcpPayload("X"))))
                    .isInstanceOf(OrgContext.AccessDeniedForOrganization.class)
                    .hasMessageContaining("Member");
            assertThatThrownBy(() -> controller.publishFrom(new PublishFromRequest("mcp", "mcp_1", null, null,
                    null, null, null, null)))
                    .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
        }
        verify(audit, never()).record(any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ global scope and curation

    @Test
    void a_global_submission_waits_for_a_curator_and_is_hidden_from_everyone_else_until_approved() {
        MarketplaceItem.View submitted = publishAs("bob", "org_b", Accounts.ROLE_MEMBER, "global", "Notion");
        String id = submitted.item().id();
        assertThat(submitted.item().status()).isEqualTo("pending");
        assertThat(submitted.item().publishedAt()).isNull();

        // A member of a third organization: nothing.
        signIn("zed", "org_z", Accounts.ROLE_MEMBER);
        assertThat(controller.list(null, null, null, null, null, null).items()).isEmpty();
        assertStatus(catchThrowable(() -> controller.get(id)), HttpStatus.NOT_FOUND);

        // The curator — an admin of the oldest organization — sees it, marked pending, and the badge.
        signIn("alice", "org_a", Accounts.ROLE_ADMIN);
        MarketplaceController.Listing listing = controller.list(null, null, null, null, null, null);
        assertThat(listing.curator()).isTrue();
        assertThat(listing.pending()).isEqualTo(1);
        assertThat(listing.items()).singleElement().satisfies(v -> {
            assertThat(v.item().status()).isEqualTo("pending");
            assertThat(v.canCurate()).isTrue();
            assertThat(v.canEdit()).isTrue();
        });
        assertThat(controller.status().pending()).isEqualTo(1);

        MarketplaceItem.View approved = controller.approve(id);
        assertThat(approved.item().status()).isEqualTo("published");
        assertThat(approved.item().approvedBy()).isEqualTo("alice@x.test");
        assertThat(approved.item().publishedAt()).isNotNull();
        verify(audit).record(eq(AuditKinds.MARKETPLACE_APPROVED), eq("marketplace-item"), eq(id), eq("Notion"), any());

        signIn("zed", "org_z", Accounts.ROLE_VIEWER);
        assertThat(controller.get(id).item().status()).isEqualTo("published");
        assertThat(controller.status().curator()).isFalse();
        // Visible now, so the refusal names the rule rather than pretending the item is not there.
        assertThatThrownBy(() -> controller.approve(id)).isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
    }

    @Test
    void rejecting_needs_a_sentence_which_the_author_then_reads() {
        String id = publishAs("bob", "org_b", Accounts.ROLE_MEMBER, "global", "Notion").item().id();

        signIn("alice", "org_a", Accounts.ROLE_ADMIN);
        assertThatThrownBy(() -> controller.reject(id, new RejectRequest("  ")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        MarketplaceItem.View rejected = controller.reject(id, new RejectRequest("Duplicate of the built-in Notion."));
        assertThat(rejected.item().status()).isEqualTo("rejected");
        verify(audit).record(eq(AuditKinds.MARKETPLACE_REJECTED), eq("marketplace-item"), eq(id), eq("Notion"), any());

        signIn("bob", "org_b", Accounts.ROLE_MEMBER);
        assertThat(controller.get(id).item().rejection()).isEqualTo("Duplicate of the built-in Notion.");
        // Rejected is not pending: it no longer shows for the curators.
        signIn("alice", "org_a", Accounts.ROLE_ADMIN);
        assertThat(controller.list(null, null, null, null, null, null).pending()).isZero();
    }

    @Test
    void only_curators_approve_and_reject_and_only_global_items() {
        String global = publishAs("bob", "org_b", Accounts.ROLE_MEMBER, "global", "Notion").item().id();
        String local = publishAs("alice", "org_a", Accounts.ROLE_MEMBER, "organization", "Linear").item().id();

        // The author, a member of the curating organization, an admin elsewhere: none of them.
        signIn("bob", "org_b", Accounts.ROLE_MEMBER);
        assertThatThrownBy(() -> controller.approve(global)).isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
        signIn("alice", "org_a", Accounts.ROLE_MEMBER);
        assertThatThrownBy(() -> controller.get(global)).isInstanceOf(ResponseStatusException.class);
        signIn("carol", "org_b", Accounts.ROLE_ADMIN);
        assertThatThrownBy(() -> controller.approve(global)).isInstanceOf(OrgContext.AccessDeniedForOrganization.class)
                .hasMessageContaining("curators");
        // A curator, on an organization item: nothing to curate there.
        signIn("admin", "org_a", Accounts.ROLE_ADMIN);
        assertThatThrownBy(() -> controller.approve(local)).isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
        // Approving twice: the second is a conflict, not a second row.
        controller.approve(global);
        assertThatThrownBy(() -> controller.approve(global)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void the_curating_organization_is_the_oldest_unless_the_setting_names_another() {
        signIn("alice", "org_a", Accounts.ROLE_ADMIN);
        assertThat(controller.status().curator()).isTrue();
        signIn("carol", "org_b", Accounts.ROLE_ADMIN);
        assertThat(controller.status().curator()).isFalse();

        env.setProperty(SettingsCatalog.MARKETPLACE_CURATOR_ORGANIZATION, "org_b");

        assertThat(controller.status().curator()).isTrue();
        signIn("alice", "org_a", Accounts.ROLE_ADMIN);
        assertThat(controller.status().curator()).isFalse();
        // And the setting is one the settings screen knows, under its own group.
        assertThat(SettingsCatalog.byKey(SettingsCatalog.MARKETPLACE_CURATOR_ORGANIZATION)).get()
                .extracting(d -> d.group()).isEqualTo(SettingsCatalog.GROUP_MARKETPLACE);
    }

    @Test
    void an_admin_of_the_curating_organization_still_curates_while_working_elsewhere() {
        Accounts.UserAccount dana = accounts.createUser("org_a", "dana@x.test", "hash", Accounts.ROLE_ADMIN);
        accounts.addMembership(dana.id(), "org_b", Accounts.ROLE_MEMBER);
        accounts.switchOrganization(dana.id(), "org_b");
        publishAs("bob", "org_b", Accounts.ROLE_MEMBER, "global", "Notion");

        signIn(dana.id(), "org_b", Accounts.ROLE_MEMBER);

        assertThat(controller.status().curator()).isTrue();
        assertThat(controller.list(null, null, null, null, null, null).pending()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ editing and deleting

    @Test
    void the_author_edits_their_own_and_a_changed_payload_bumps_the_version() {
        MarketplaceItem.View v1 = publishAs("alice", "org_a", Accounts.ROLE_MEMBER, "organization", "Linear");
        String id = v1.item().id();

        MarketplaceItem.View renamed = controller.edit(id, new PublishRequest("mcp", "Linear (EU)", "s", "d",
                List.of("eu"), "L", "organization", v1.item().payload()));
        assertThat(renamed.item().version()).isEqualTo(1);
        assertThat(renamed.item()).extracting(MarketplaceItem::name, MarketplaceItem::summary, MarketplaceItem::icon)
                .containsExactly("Linear (EU)", "s", "L");

        MarketplaceItem.View changed = controller.edit(id, publish("mcp", "Linear", "organization",
                mcpPayload("Linear").put("url", "https://eu.linear.test/mcp")));
        assertThat(changed.item().version()).isEqualTo(2);
        assertThat(changed.item().status()).isEqualTo("published");
    }

    @Test
    void colleagues_cannot_edit_or_delete_but_the_organizations_admin_can() {
        String id = publishAs("alice", "org_a", Accounts.ROLE_MEMBER, "organization", "Linear").item().id();
        PublishRequest edit = publish("mcp", "Linear", "organization", mcpPayload("Linear"));

        signIn("bob", "org_a", Accounts.ROLE_MEMBER);
        assertThat(controller.get(id).canEdit()).isFalse();
        assertThatThrownBy(() -> controller.edit(id, edit)).isInstanceOf(OrgContext.AccessDeniedForOrganization.class);
        assertThatThrownBy(() -> controller.delete(id)).isInstanceOf(OrgContext.AccessDeniedForOrganization.class);

        signIn("admin", "org_a", Accounts.ROLE_ADMIN);
        assertThat(controller.get(id).canEdit()).isTrue();
        controller.edit(id, edit);
        controller.delete(id);
        assertStatus(catchThrowable(() -> controller.get(id)), HttpStatus.NOT_FOUND);
    }

    @Test
    void an_edit_by_a_non_curator_sends_a_global_item_back_to_the_curators_and_a_curators_edit_does_not() {
        String id = publishAs("bob", "org_b", Accounts.ROLE_MEMBER, "global", "Notion").item().id();
        signIn("alice", "org_a", Accounts.ROLE_ADMIN);
        controller.approve(id);

        signIn("bob", "org_b", Accounts.ROLE_MEMBER);
        MarketplaceItem.View resubmitted = controller.edit(id, publish("mcp", "Notion", "global",
                mcpPayload("Notion").put("url", "https://mcp.notion.com/v2")));
        assertThat(resubmitted.item().status()).isEqualTo("pending");
        assertThat(resubmitted.item().version()).isEqualTo(2);
        assertThat(resubmitted.item().approvedBy()).isNull();

        signIn("alice", "org_a", Accounts.ROLE_ADMIN);
        controller.approve(id);
        MarketplaceItem.View curated = controller.edit(id, publish("mcp", "Notion", "global", mcpPayload("Notion")));
        assertThat(curated.item().status()).isEqualTo("published");
        assertThat(curated.item().version()).isEqualTo(3);

        // The author withdraws it: their own, so they may delete it whatever its status.
        signIn("bob", "org_b", Accounts.ROLE_MEMBER);
        controller.delete(id);
        assertThat(store.find(id)).isEmpty();
    }

    @Test
    void built_ins_cannot_be_edited_or_deleted_but_can_be_installed() {
        long now = System.currentTimeMillis();
        MarketplaceItem builtIn = store.insert(new MarketplaceItem(null, "mcp", "GitHub", "Issues, PRs", null,
                List.of("Development"), 1, "global", null, "published", null,
                new MarketplaceItem.Author("system:concentus", "system:concentus"), mcpPayload("GitHub"), null,
                0, true, now, now, now, "system:concentus"), "h");
        signIn("alice", "org_a", Accounts.ROLE_ADMIN);   // an admin AND a curator, and still no

        assertThat(controller.get(builtIn.id())).extracting(MarketplaceItem.View::canEdit, MarketplaceItem.View::canCurate)
                .containsExactly(false, false);
        assertThatThrownBy(() -> controller.edit(builtIn.id(), publish("mcp", "GitHub", "global", mcpPayload("GitHub"))))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class).hasMessageContaining("Built-in");
        assertThatThrownBy(() -> controller.delete(builtIn.id()))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class).hasMessageContaining("Built-in");
        assertThatThrownBy(() -> controller.reject(builtIn.id(), new RejectRequest("no")))
                .isInstanceOf(OrgContext.AccessDeniedForOrganization.class);

        MarketplaceController.InstallResult installed = controller.install(builtIn.id());
        assertThat(mcps.get(installed.resourceId())).get().extracting(McpDef::name).isEqualTo("GitHub");
    }

    // ------------------------------------------------------------------ installing

    @Test
    void an_operator_installs_an_mcp_into_their_organization_with_the_credential_slot_empty() {
        ObjectNode payload = mcpPayload("GitLab").put("authHeader", "PRIVATE-TOKEN").put("auth", "token");
        payload.putObject("env").put("GITLAB_TOKEN", "");
        signIn("alice", "org_a", Accounts.ROLE_MEMBER);
        String id = controller.publish(publish("mcp", "GitLab", "global", payload)).item().id();
        signIn("admin", "org_a", Accounts.ROLE_ADMIN);
        controller.approve(id);

        signIn("ops", "org_b", Accounts.ROLE_OPERATOR);
        MarketplaceController.InstallResult result = controller.install(id);

        assertThat(result.kind()).isEqualTo("mcp");
        assertThat(result.version()).isEqualTo(1);
        McpDef created = mcps.get(result.resourceId()).orElseThrow();
        assertThat(created.name()).isEqualTo("GitLab");
        assertThat(created.url()).isEqualTo("https://gitlab.test/mcp");
        assertThat(created.authHeader()).isEqualTo("PRIVATE-TOKEN");
        assertThat(created.credentialId()).isNull();
        assertThat(created.env()).containsEntry("GITLAB_TOKEN", "");
        // In org_b only.
        signIn("alice", "org_a", Accounts.ROLE_MEMBER);
        assertThat(mcps.get(result.resourceId())).isEmpty();
        assertThat(controller.get(id).installed()).isNull();
        assertThat(controller.get(id).item().installs()).isEqualTo(1);

        signIn("ops", "org_b", Accounts.ROLE_OPERATOR);
        MarketplaceItem.View view = controller.get(id);
        assertThat(view.installed()).isEqualTo(new MarketplaceItem.Installed(result.resourceId(), 1,
                view.installed().installedAt()));
        verify(audit).record(eq(AuditKinds.MARKETPLACE_INSTALLED), eq("marketplace-item"), eq(id), eq("GitLab"), any());

        // Installing again is an update of the same resource, not a twin.
        assertThat(controller.install(id).resourceId()).isEqualTo(result.resourceId());
        assertThat(mcps.list()).hasSize(1);

        Map<String, Object> gone = controller.uninstall(id);
        assertThat(gone).containsEntry("uninstalled", true).containsEntry("resourceId", result.resourceId());
        assertThat(mcps.get(result.resourceId())).isEmpty();
        assertThat(controller.get(id).installed()).isNull();
        verify(audit).record(eq(AuditKinds.MARKETPLACE_UNINSTALLED), eq("marketplace-item"), eq(id), eq("GitLab"), any());
        assertThatThrownBy(() -> controller.uninstall(id)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not installed");
    }

    @Test
    void viewers_cannot_install_and_a_pending_item_installs_only_for_its_own_organization() {
        String id = publishAs("bob", "org_b", Accounts.ROLE_MEMBER, "global", "Notion").item().id();

        signIn("v", "org_b", Accounts.ROLE_VIEWER);
        assertThatThrownBy(() -> controller.install(id)).isInstanceOf(OrgContext.AccessDeniedForOrganization.class)
                .hasMessageContaining("Operator");
        assertThatThrownBy(() -> controller.uninstall(id)).isInstanceOf(OrgContext.AccessDeniedForOrganization.class);

        // The curator can see it but it is not published: not yet installable for them.
        signIn("alice", "org_a", Accounts.ROLE_ADMIN);
        assertThatThrownBy(() -> controller.install(id)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending");
        // The publishing organization may use its own submission while it waits.
        signIn("ops", "org_b", Accounts.ROLE_OPERATOR);
        assertThat(controller.install(id).resourceId()).startsWith("mcp_");
    }

    @Test
    void every_kind_installs_through_the_stores_the_panels_use() {
        signIn("alice", "org_a", Accounts.ROLE_MEMBER);
        String agent = controller.publish(publish("agent", "Reviewer", "organization",
                mapper.createObjectNode().put("name", "Reviewer").put("model", "claude-x").put("effort", "high")
                        .put("maxTokens", 4000).put("systemPrompt", "Review.").put("description", "Reviews"))).item().id();
        ObjectNode facadeNode = mapper.createObjectNode().put("name", "Read only").put("description", "")
                .put("readOnly", true).put("dryRun", false);
        facadeNode.putArray("tools").add("list");
        facadeNode.putArray("readAlso").add("run_report");
        String facade = controller.publish(publish("facade", "Read only", "organization", facadeNode)).item().id();
        ObjectNode skillNode = mapper.createObjectNode().put("name", "my-skill").put("description", "d");
        skillNode.putArray("files").addObject().put("path", "SKILL.md").put("contentBase64", Base64.getEncoder()
                .encodeToString("---\nname: my-skill\ndescription: d\n---\nDo the thing.\n"
                        .getBytes(StandardCharsets.UTF_8)));
        String skill = controller.publish(publish("skill", "my-skill", "organization", skillNode)).item().id();
        String api = controller.publish(publish("api", "Weather", "organization",
                mapper.createObjectNode().put("name", "Weather").put("baseUrl", "https://api.weather.test")
                        .put("description", "Forecasts"))).item().id();
        String plugin = controller.publish(publish("plugin", "context7", "organization",
                mapper.createObjectNode().put("pluginId", "context7").put("marketplace", "community"))).item().id();
        when(plugins.install("context7@community")).thenReturn("installed");

        signIn("ops", "org_a", Accounts.ROLE_OPERATOR);
        String agentId = controller.install(agent).resourceId();
        assertThat(agents.get(agentId)).get().extracting(LibraryAgent::name, LibraryAgent::model, LibraryAgent::maxTokens)
                .containsExactly("Reviewer", "claude-x", 4000L);
        String facadeId = controller.install(facade).resourceId();
        assertThat(facades.get(facadeId)).get().extracting(FacadeProfile::readOnly, FacadeProfile::dryRunEnabled,
                FacadeProfile::readAlsoOrEmpty).containsExactly(true, false, List.of("run_report"));
        String skillId = controller.install(skill).resourceId();
        assertThat(skills.get(skillId)).get().extracting(SkillDef::name).isEqualTo("my-skill");
        assertThat(controller.install(api).resourceId()).isNull();
        assertThat(controller.get(api).installed()).isNotNull();
        assertThat(controller.install(plugin).resourceId()).isEqualTo("context7@community");
        verify(plugins).install("context7@community");

        when(plugins.install("context7@community")).thenReturn("install failed: no such plugin");
        assertThatThrownBy(() -> controller.install(plugin)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no such plugin");

        controller.uninstall(agent);
        controller.uninstall(skill);
        controller.uninstall(plugin);
        assertThat(agents.get(agentId)).isEmpty();
        assertThat(skills.get(skillId)).isEmpty();
        verify(plugins).uninstall("context7@community");
    }

    // ------------------------------------------------------------------ publishing from a resource

    @Test
    void publishing_an_mcp_from_resources_strips_the_credential_and_the_token_values_and_says_so() {
        signIn("alice", "org_a", Accounts.ROLE_MEMBER);
        McpDef remote = mcps.save(McpDef.http(null, "GitHub", "https://api.githubcopilot.com/mcp/", "cred_1", null));
        McpDef local = mcps.save(new McpDef(null, "Ads", null, null, null, "npx", List.of("-y", "mcp-google-ads"),
                Map.of("GOOGLE_ADS_MCP_WRITE", "true", "GOOGLE_ADS_API_TOKEN", "credential:cred_2",
                        "GOOGLE_ADS_CUSTOMER_ID", "123")));

        Map<String, Object> published = controller.publishFrom(new PublishFromRequest("mcp", remote.id(), "global",
                null, "PRs and issues", null, null, null));
        assertThat(published.get("stripped")).isEqualTo(List.of("credentialId"));
        assertThat(published.get("status")).isEqualTo("pending");
        assertThat(published.get("summary")).isEqualTo("PRs and issues");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) published.get("payload");
        assertThat(payload).containsEntry("auth", "token").doesNotContainKey("credentialId").doesNotContainKey("id");

        Map<String, Object> stdio = controller.publishFrom(new PublishFromRequest("mcp", local.id(), null, null, null,
                null, null, null));
        assertThat(stdio.get("stripped")).isEqualTo(List.of("env.GOOGLE_ADS_API_TOKEN"));
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) ((Map<String, Object>) stdio.get("payload")).get("env");
        assertThat(env).containsEntry("GOOGLE_ADS_API_TOKEN", "").containsEntry("GOOGLE_ADS_MCP_WRITE", "true")
                .containsEntry("GOOGLE_ADS_CUSTOMER_ID", "123");
        assertThat(stdio.get("scope")).isEqualTo("organization");

        assertStatus(catchThrowable(() -> controller.publishFrom(new PublishFromRequest("mcp", "mcp_nope", null,
                null, null, null, null, null))), HttpStatus.NOT_FOUND);
        assertThatThrownBy(() -> controller.publishFrom(new PublishFromRequest("api", "x", null, null, null, null,
                null, null))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inspector");
    }

    @Test
    void publishing_a_flow_strips_every_secret_and_installing_it_arrives_paused() {
        signIn("alice", "org_a", Accounts.ROLE_MEMBER);
        FlowNode input = new FlowNode("in", "input", null, Map.of("mode", "webhook", "secret", "s3cret",
                "published", true, "publishToken", "tok", "cron", "0 7 * * *"));
        FlowNode mail = new FlowNode("m", "mail", null, Map.of("credentialId", "cred_m", "folder", "Inbox"));
        FlowNode lead = new FlowNode("a", "agent", "coordinator", Map.of("name", "Lead", "description", "Leads"));
        FlowGraph flow = flows.save(new FlowGraph(null, "Nightly", List.of(input, mail, lead),
                List.of(new FlowEdge("e1", "in", "a")), true, List.of("ops"), true, "https://hooks.slack/x", 5.0,
                "cred_slack", "#ops", "https://teams/x", Map.of("API_KEY", "k"), "Samples", false));

        Map<String, Object> published = controller.publishFrom(new PublishFromRequest("flow", flow.id(),
                "organization", null, null, null, null, null));

        assertThat(strings(published.get("stripped"))).containsExactlyInAnyOrder("secret", "publishToken",
                "m.credentialId", "approvalSlackCredentialId", "notifyWebhook", "approvalTeamsWebhook", "variables");
        assertThat(published.get("summary")).isEqualTo("Leads");
        assertThat(published.get("tags")).isEqualTo(List.of("ops"));

        // Published to the organization, so a colleague installs it there: a second flow, its own id.
        signIn("ops", "org_a", Accounts.ROLE_OPERATOR);
        String installed = controller.install((String) published.get("id")).resourceId();
        FlowGraph copy = flows.get(installed).orElseThrow();
        assertThat(copy.id()).isNotEqualTo(flow.id());
        assertThat(copy.enabledOrDefault()).isFalse();
        assertThat(copy.favorite()).isFalse();
        assertThat(copy.folder()).isNull();
        assertThat(copy.notifyWebhook()).isNull();
        assertThat(copy.approvalSlackCredentialId()).isNull();
        assertThat(copy.variables()).containsEntry("API_KEY", "");
        Map<String, Object> in = copy.nodesOrEmpty().get(0).dataOrEmpty();
        assertThat(in).containsEntry("secret", "").containsEntry("publishToken", "").containsEntry("published", false)
                .containsEntry("cron", "0 7 * * *");
        assertThat(copy.nodesOrEmpty().get(1).dataOrEmpty()).containsEntry("credentialId", "");
        assertThat(versions.currentVersion(installed)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ validation

    @Test
    void a_payload_the_resources_own_panel_would_refuse_is_refused_with_the_panels_sentence() {
        signIn("alice", "org_a", Accounts.ROLE_MEMBER);

        assertThatThrownBy(() -> controller.publish(publish("mcp", "X", "organization", mapper.createObjectNode().put("name", "X"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("name plus a url or a command are required.");
        assertThatThrownBy(() -> controller.publish(publish("agent", "X", "organization", mapper.createObjectNode().put("model", "m"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("name is required.");
        assertThatThrownBy(() -> controller.publish(publish("facade", "X", "organization", mapper.createObjectNode())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A profile needs a name.");
        assertThatThrownBy(() -> controller.publish(publish("skill", "X", "organization", mapper.createObjectNode().put("name", "s"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No SKILL.md");
        assertThatThrownBy(() -> controller.publish(publish("plugin", "X", "organization", mapper.createObjectNode().put("marketplace", "m"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pluginId");
        assertThatThrownBy(() -> controller.publish(publish("api", "X", "organization", mapper.createObjectNode().put("name", "W"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("baseUrl");
        assertThatThrownBy(() -> controller.publish(publish("flow", "X", "organization", mapper.createObjectNode().put("mode", "local"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> controller.publish(publish("gadget", "X", "organization", mapper.createObjectNode())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown kind");
        assertThatThrownBy(() -> controller.publish(publish("mcp", " ", "organization", mcpPayload("X"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name is required");
        assertThatThrownBy(() -> controller.publish(publish("mcp", "X", "everywhere", mcpPayload("X"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scope");
        assertThat(store.listVisible(new MarketplaceStore.Viewer("alice", "org_a", false))).isEmpty();
    }

    // ------------------------------------------------------------------ listing

    @Test
    void the_list_filters_sorts_and_carries_the_tags_and_the_json_has_the_agreed_shape() throws Exception {
        signIn("alice", "org_a", Accounts.ROLE_MEMBER);
        String linear = controller.publish(new PublishRequest("mcp", "Linear", "Issues, projects", "Long story",
                List.of("planning", "oauth"), "L", "organization", mcpPayload("Linear"))).item().id();
        controller.publish(new PublishRequest("agent", "Zed reviewer", "Reviews", null, List.of("review"), null,
                "organization", mapper.createObjectNode().put("name", "Zed reviewer")));
        signIn("ops", "org_a", Accounts.ROLE_OPERATOR);
        controller.install(linear);

        MarketplaceController.Listing all = controller.list(null, null, null, null, null, "name");
        assertThat(all.items()).extracting(v -> v.item().name()).containsExactly("Linear", "Zed reviewer");
        assertThat(all.tags()).containsExactly("oauth", "planning", "review");
        assertThat(controller.list(null, "agent", null, null, null, null).items()).hasSize(1);
        assertThat(controller.list(null, null, "global", null, null, null).items()).isEmpty();
        assertThat(controller.list(null, null, null, "review", null, null).items()).hasSize(1);
        assertThat(controller.list("proj", null, null, null, null, null).items())
                .extracting(v -> v.item().name()).containsExactly("Linear");
        assertThat(controller.list(null, null, null, null, "pending", null).items()).isEmpty();
        assertThat(controller.list(null, null, null, null, null, "installs").items())
                .extracting(v -> v.item().name()).containsExactly("Linear", "Zed reviewer");

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(all);
        System.out.println("LIST RESPONSE JSON\n" + json);
        JsonNode root = mapper.readTree(json);
        assertThat(root.fieldNames()).toIterable().containsExactly("items", "tags", "curator", "pending");
        JsonNode first = root.get("items").get(0);
        assertThat(first.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "kind", "name", "summary",
                "description", "tags", "version", "scope", "organizationId", "status", "rejection", "author",
                "payload", "icon", "installs", "builtIn", "createdAt", "updatedAt", "publishedAt", "approvedBy",
                "installed", "mine", "canEdit", "canCurate");
        assertThat(first.get("installed").fieldNames()).toIterable().containsExactly("resourceId", "version", "installedAt");
        assertThat(first.get("author").fieldNames()).toIterable().containsExactly("userId", "email");
        assertThat(first.get("payload").get("url").asText()).isEqualTo("https://linear.test/mcp");
        assertThat(first.get("installs").asInt()).isEqualTo(1);
        assertThat(first.get("mine").asBoolean()).isFalse();

        MarketplaceController.Status status = controller.status();
        assertThat(status.organizations()).isEqualTo(2);
        assertThat(mapper.readTree(mapper.writeValueAsString(status)).fieldNames()).toIterable()
                .containsExactly("curator", "pending", "organizations", "tags");
        System.out.println("STATUS JSON\n" + mapper.writeValueAsString(status));
        System.out.println("INSTALL JSON\n" + mapper.writeValueAsString(new MarketplaceController.InstallResult("mcp_1", "mcp", 1)));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }

    private static Throwable catchThrowable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        return org.assertj.core.api.Assertions.catchThrowable(call);
    }
}
