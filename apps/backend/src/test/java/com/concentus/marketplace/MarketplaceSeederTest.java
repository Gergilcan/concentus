package com.concentus.marketplace;

import com.concentus.store.TestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bundled library on the marketplace: what a first start publishes, and what a second one
 * leaves alone, rewrites, or declines to bring back.
 */
class MarketplaceSeederTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private MarketplaceStore store;
    private MarketplaceSeeder seeder;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.jdbc();
        TestDatabase.reset(jdbc);   // the marker lives in resources
        jdbc.update("delete from marketplace_installs");
        jdbc.update("delete from marketplace_items");
        store = new MarketplaceStore(jdbc, mapper);
        store.init();
        seeder = new MarketplaceSeeder(jdbc, store, mapper);
    }

    private List<MarketplaceItem> all() {
        return store.listVisible(new MarketplaceStore.Viewer("nobody", "org_none", false));
    }

    @Test
    void a_first_start_publishes_the_catalog_the_agents_and_the_starter_flows_as_global_built_ins() {
        MarketplaceSeeder.Report report = seeder.seed();

        List<MarketplaceItem> items = all();
        assertThat(report).isEqualTo(new MarketplaceSeeder.Report(40, 0, 0, 0));
        assertThat(items).filteredOn(i -> i.kind().equals("mcp")).hasSize(28);
        assertThat(items).filteredOn(i -> i.kind().equals("agent")).hasSize(4);
        assertThat(items).filteredOn(i -> i.kind().equals("flow")).hasSize(8);
        assertThat(items).allSatisfy(i -> {
            assertThat(i.builtIn()).isTrue();
            assertThat(i.scope()).isEqualTo("global");
            assertThat(i.status()).isEqualTo("published");
            assertThat(i.author()).isEqualTo(new MarketplaceItem.Author("system:concentus", "system:concentus"));
            assertThat(i.approvedBy()).isEqualTo("system:concentus");
            assertThat(i.organizationId()).isNull();
            assertThat(i.publishedAt()).isNotNull();
            assertThat(i.version()).isEqualTo(1);
            assertThat(i.id()).startsWith("mkt_").hasSize(16);
        });
    }

    @Test
    void the_mcp_catalog_is_seeded_with_blurb_note_category_and_auth() {
        seeder.seed();

        MarketplaceItem gitlab = all().stream().filter(i -> i.name().equals("GitLab")).findFirst().orElseThrow();
        assertThat(gitlab.summary()).isEqualTo("Issues, MRs, pipelines");
        assertThat(gitlab.description()).contains("PRIVATE-TOKEN");
        assertThat(gitlab.tags()).containsExactly("Development", "token");
        assertThat(gitlab.payload().get("url").asText()).isEqualTo("https://gitlab.com/api/v4/mcp");
        assertThat(gitlab.payload().get("authHeader").asText()).isEqualTo("PRIVATE-TOKEN");
        assertThat(gitlab.payload().get("auth").asText()).isEqualTo("token");
        assertThat(gitlab.payload().has("credentialId")).isFalse();

        MarketplaceItem ads = all().stream().filter(i -> i.name().equals("Google Ads (write)")).findFirst().orElseThrow();
        assertThat(ads.payload().get("command").asText()).isEqualTo("npx");
        assertThat(ads.payload().get("args")).hasSize(2);
        // Env keys travel; the flag that is configuration travels with its value, the ids empty.
        assertThat(ads.payload().get("env").get("GOOGLE_ADS_MCP_WRITE").asText()).isEqualTo("true");
        assertThat(ads.payload().get("env").get("GOOGLE_ADS_CUSTOMER_ID").asText()).isEmpty();
        assertThat(ads.tags()).containsExactly("Google", "stdio");
    }

    @Test
    void the_library_agents_and_flows_are_seeded_from_the_same_files_the_stores_use() {
        seeder.seed();

        MarketplaceItem lead = all().stream().filter(i -> i.kind().equals("agent") && i.name().equals("Tech Lead"))
                .findFirst().orElseThrow();
        assertThat(lead.payload().get("model").asText()).isEqualTo("claude-opus-4-8");
        assertThat(lead.payload().get("effort").asText()).isEqualTo("medium");
        assertThat(lead.payload().get("maxTokens").asLong()).isEqualTo(12000);
        assertThat(lead.payload().get("systemPrompt").asText()).contains("tech lead");
        assertThat(lead.payload().has("id")).isFalse();

        MarketplaceItem crew = all().stream().filter(i -> i.kind().equals("flow") && i.name().equals("PR review crew"))
                .findFirst().orElseThrow();
        assertThat(crew.tags()).containsExactly("code", "review", "git");
        assertThat(crew.summary()).isEqualTo("Coordinates a code review across specialised reviewers.");
        // Stripped like any published flow: no id to collide with, paused, no secret.
        assertThat(crew.payload().get("id").isNull()).isTrue();
        assertThat(crew.payload().get("enabled").asBoolean()).isFalse();
        assertThat(crew.payload().get("nodes")).hasSize(4);
    }

    @Test
    void a_second_start_changes_nothing_when_the_bundle_did_not_change() {
        seeder.seed();
        List<MarketplaceItem> before = all();

        MarketplaceSeeder.Report again = seeder.seed();

        assertThat(again).isEqualTo(new MarketplaceSeeder.Report(0, 0, 40, 0));
        assertThat(all()).usingRecursiveComparison().isEqualTo(before);
    }

    @Test
    void a_built_in_whose_bundled_definition_changed_is_rewritten_with_its_version_bumped() {
        seeder.seed();
        MarketplaceItem github = all().stream().filter(i -> i.name().equals("GitHub")).findFirst().orElseThrow();
        // What an older build's row looks like once the bundle moved on: a different hash and
        // a payload that no longer matches. The row's own history — creation time, installs —
        // is what must survive.
        jdbc.update("update marketplace_items set built_in_hash = 'stale', payload = '{\"name\":\"GitHub\"}'::jsonb, "
                + "summary = 'old words' where id = ?", github.id());
        store.recordInstall(new MarketplaceStore.Install(github.id(), "org_a", "mcp_1", 1, 5L, null));

        MarketplaceSeeder.Report report = seeder.seed();

        assertThat(report).isEqualTo(new MarketplaceSeeder.Report(0, 1, 39, 0));
        MarketplaceItem reseeded = store.find(github.id()).orElseThrow();
        assertThat(reseeded.version()).isEqualTo(2);
        assertThat(reseeded.summary()).isEqualTo("Issues, PRs, repositories");
        assertThat(reseeded.payload().get("url").asText()).isEqualTo("https://api.githubcopilot.com/mcp/");
        assertThat(reseeded.createdAt()).isEqualTo(github.createdAt());
        assertThat(reseeded.installs()).isEqualTo(1);
        // And the install now reads as out of date: version 1 installed, version 2 published.
        assertThat(store.install(github.id(), "org_a").orElseThrow().version()).isLessThan(reseeded.version());
    }

    @Test
    void a_changed_summary_alone_is_rewritten_without_bumping_the_version() {
        seeder.seed();
        MarketplaceItem github = all().stream().filter(i -> i.name().equals("GitHub")).findFirst().orElseThrow();
        jdbc.update("update marketplace_items set built_in_hash = 'stale', summary = 'old words' where id = ?",
                github.id());

        seeder.seed();

        MarketplaceItem reseeded = store.find(github.id()).orElseThrow();
        assertThat(reseeded.version()).isEqualTo(1);
        assertThat(reseeded.summary()).isEqualTo("Issues, PRs, repositories");
    }

    @Test
    void a_number_read_back_from_jsonb_in_another_type_is_not_a_changed_payload() {
        seeder.seed();
        MarketplaceItem lead = all().stream().filter(i -> i.kind().equals("agent") && i.name().equals("Tech Lead"))
                .findFirst().orElseThrow();
        // maxTokens was written from a long and comes back from jsonb as an int: still the same
        // payload, so a changed summary alone must not tell every installer an update exists.
        jdbc.update("update marketplace_items set built_in_hash = 'stale', summary = 'old words' where id = ?",
                lead.id());

        seeder.seed();

        MarketplaceItem reseeded = store.find(lead.id()).orElseThrow();
        assertThat(reseeded.version()).isEqualTo(1);
        assertThat(reseeded.summary()).isNotEqualTo("old words");
    }

    @Test
    void a_deleted_built_in_stays_deleted() {
        seeder.seed();
        MarketplaceItem figma = all().stream().filter(i -> i.name().equals("Figma")).findFirst().orElseThrow();
        store.delete(figma.id());

        MarketplaceSeeder.Report report = seeder.seed();

        assertThat(report).isEqualTo(new MarketplaceSeeder.Report(0, 0, 39, 1));
        assertThat(store.find(figma.id())).isEmpty();
        assertThat(all()).hasSize(39);
    }

    @Test
    void ids_are_derived_from_what_the_item_is_so_two_databases_agree() {
        assertThat(MarketplaceSeeder.idFor("mcp:GitHub")).isEqualTo(MarketplaceSeeder.idFor("mcp:GitHub"))
                .startsWith("mkt_").hasSize(16);
        assertThat(MarketplaceSeeder.idFor("mcp:GitHub")).isNotEqualTo(MarketplaceSeeder.idFor("mcp:GitLab"));
    }
}
