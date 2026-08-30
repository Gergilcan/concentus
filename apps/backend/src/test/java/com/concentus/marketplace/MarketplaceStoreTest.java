package com.concentus.marketplace;

import com.concentus.marketplace.MarketplaceStore.Install;
import com.concentus.marketplace.MarketplaceStore.Viewer;
import com.concentus.store.TestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The marketplace tables, against a real PostgreSQL through the real migration.
 *
 * <p>What matters here is the visibility query: who sees what is decided in SQL, and a caller
 * outside an item's audience gets the same empty answer for it as for an id that names nothing.
 */
class MarketplaceStoreTest {

    private static final Viewer A_MEMBER = new Viewer("usr_a", "org_a", false);
    private static final Viewer B_MEMBER = new Viewer("usr_b", "org_b", false);
    private static final Viewer CURATOR = new Viewer("usr_c", "org_c", true);

    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private MarketplaceStore store;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.jdbc();
        jdbc.update("delete from marketplace_installs");
        jdbc.update("delete from marketplace_items");
        store = new MarketplaceStore(jdbc, mapper);
        store.init();
        assertThat(store.isAvailable()).isTrue();
    }

    private MarketplaceItem item(String name, String scope, String status, String organizationId, String authorId) {
        ObjectNode payload = mapper.createObjectNode().put("name", name).put("url", "https://example.test/mcp");
        long now = System.currentTimeMillis();
        return new MarketplaceItem(null, "mcp", name, "one line", null, List.of("tag-" + name), 1, scope,
                organizationId, status, null, new MarketplaceItem.Author(authorId, authorId + "@x.test"),
                payload, null, 0, false, now, now, MarketplaceItem.PUBLISHED.equals(status) ? now : null, null);
    }

    // ---- visibility ----

    @Test
    void a_published_global_item_is_visible_to_everyone() {
        MarketplaceItem global = store.insert(item("Linear", "global", "published", "org_a", "usr_a"), null);

        for (Viewer v : List.of(A_MEMBER, B_MEMBER, CURATOR)) {
            assertThat(store.listVisible(v)).extracting(MarketplaceItem::id).contains(global.id());
            assertThat(store.findVisible(global.id(), v)).isPresent();
        }
    }

    @Test
    void an_organization_item_is_visible_to_that_organization_only_whatever_its_status() {
        MarketplaceItem ours = store.insert(item("Ours", "organization", "published", "org_a", "usr_a"), null);

        assertThat(store.listVisible(A_MEMBER)).extracting(MarketplaceItem::id).containsExactly(ours.id());
        assertThat(store.listVisible(new Viewer("usr_a2", "org_a", false)))
                .extracting(MarketplaceItem::id).containsExactly(ours.id());
        assertThat(store.listVisible(B_MEMBER)).isEmpty();
        assertThat(store.listVisible(CURATOR)).isEmpty();
        // By id, from the wrong organization: the same answer as for an id that does not exist.
        assertThat(store.findVisible(ours.id(), B_MEMBER)).isEmpty();
        assertThat(store.findVisible("mkt_nothing", B_MEMBER)).isEmpty();
        assertThat(store.find(ours.id())).isPresent();
    }

    @Test
    void a_pending_global_submission_is_seen_by_its_author_its_organization_and_the_curators_but_nobody_else() {
        MarketplaceItem pending = store.insert(item("Draft", "global", "pending", "org_a", "usr_a"), null);
        MarketplaceItem rejected = store.insert(item("No", "global", "rejected", "org_a", "usr_a"), null);

        // The author, wherever they are working now.
        assertThat(store.listVisible(new Viewer("usr_a", "org_z", false)))
                .extracting(MarketplaceItem::id).containsExactlyInAnyOrder(pending.id(), rejected.id());
        // A colleague in the publishing organization.
        assertThat(store.listVisible(new Viewer("usr_a2", "org_a", false)))
                .extracting(MarketplaceItem::id).containsExactlyInAnyOrder(pending.id(), rejected.id());
        // A curator: what waits, not what was turned down.
        assertThat(store.listVisible(CURATOR)).extracting(MarketplaceItem::id).containsExactly(pending.id());
        // Everyone else: nothing, and nothing by id either.
        assertThat(store.listVisible(B_MEMBER)).isEmpty();
        assertThat(store.findVisible(pending.id(), B_MEMBER)).isEmpty();
        assertThat(store.findVisible(pending.id(), CURATOR)).isPresent();
        assertThat(store.findVisible(rejected.id(), CURATOR)).isEmpty();
    }

    @Test
    void the_pending_count_is_of_global_submissions_only() {
        store.insert(item("G1", "global", "pending", "org_a", "usr_a"), null);
        store.insert(item("G2", "global", "pending", "org_b", "usr_b"), null);
        store.insert(item("O", "organization", "published", "org_a", "usr_a"), null);
        store.insert(item("R", "global", "rejected", "org_a", "usr_a"), null);

        assertThat(store.countPendingGlobal()).isEqualTo(2);
    }

    // ---- installs ----

    @Test
    void installs_are_recorded_per_organization_counted_on_the_item_and_removed_on_uninstall() {
        MarketplaceItem global = store.insert(item("Linear", "global", "published", "org_a", "usr_a"), null);
        store.recordInstall(new Install(global.id(), "org_a", "mcp_1", 1, 10L, "a@x.test"));
        store.recordInstall(new Install(global.id(), "org_b", "mcp_2", 1, 11L, "b@x.test"));
        // The same organization again is an update of the one row, not a second copy.
        store.recordInstall(new Install(global.id(), "org_b", "mcp_2", 2, 12L, "b@x.test"));

        assertThat(store.find(global.id()).orElseThrow().installs()).isEqualTo(2);
        assertThat(store.install(global.id(), "org_b")).get()
                .extracting(Install::resourceId, Install::version, Install::installedAt)
                .containsExactly("mcp_2", 2, 12L);
        assertThat(store.installsOf("org_a")).containsOnlyKeys(global.id());
        assertThat(store.installsOf("org_z")).isEmpty();

        assertThat(store.removeInstall(global.id(), "org_b")).isTrue();
        assertThat(store.removeInstall(global.id(), "org_b")).isFalse();
        assertThat(store.find(global.id()).orElseThrow().installs()).isEqualTo(1);
        assertThat(store.install(global.id(), "org_b")).isEmpty();
    }

    @Test
    void deleting_an_item_takes_its_install_records_with_it() {
        MarketplaceItem global = store.insert(item("Linear", "global", "published", "org_a", "usr_a"), null);
        store.recordInstall(new Install(global.id(), "org_b", "mcp_2", 1, 11L, null));

        assertThat(store.delete(global.id())).isTrue();

        assertThat(store.find(global.id())).isEmpty();
        assertThat(store.installsOf("org_b")).isEmpty();
        assertThat(store.delete(global.id())).isFalse();
    }

    // ---- round trip ----

    @Test
    void every_column_survives_the_round_trip_including_the_json_ones() {
        MarketplaceItem in = item("Round", "global", "published", "org_a", "usr_a");
        MarketplaceItem saved = store.insert(in, "hash-1");

        MarketplaceItem out = store.find(saved.id()).orElseThrow();
        assertThat(out.id()).startsWith("mkt_").hasSize(16);
        assertThat(out.tags()).containsExactly("tag-Round");
        assertThat(out.payload().get("url").asText()).isEqualTo("https://example.test/mcp");
        assertThat(out.author()).isEqualTo(new MarketplaceItem.Author("usr_a", "usr_a@x.test"));
        assertThat(out.publishedAt()).isNotNull();
        assertThat(out.builtIn()).isFalse();
        assertThat(store.builtInHash(saved.id())).isEmpty();   // not a built-in, so the hash is not one

        MarketplaceItem edited = new MarketplaceItem(out.id(), out.kind(), "Renamed", "two", "desc",
                List.of("x", "y"), 2, "organization", out.organizationId(), "rejected", "because",
                out.author(), mapper.createObjectNode().put("name", "n").put("command", "npx"), "*", 0, true,
                out.createdAt(), 99L, null, "curator@x.test");
        store.update(edited, "hash-2");

        MarketplaceItem again = store.find(saved.id()).orElseThrow();
        assertThat(again).usingRecursiveComparison().ignoringFields("installs").isEqualTo(edited);
        assertThat(store.builtInHash(saved.id())).contains("hash-2");
        assertThat(store.listBuiltIn()).extracting(MarketplaceItem::id).containsExactly(saved.id());
    }

    @Test
    void the_oldest_organization_is_the_default_curator() {
        jdbc.update("delete from organizations");
        assertThat(store.oldestOrganizationId()).isEmpty();
        jdbc.update("insert into organizations (id, name, created_at) values (?,?,?)", "org_new", "New", 200L);
        jdbc.update("insert into organizations (id, name, created_at) values (?,?,?)", "org_first", "First", 100L);

        assertThat(store.oldestOrganizationId()).contains("org_first");
        jdbc.update("delete from organizations");
    }

    @Test
    void an_unavailable_table_reads_empty_and_refuses_writes() {
        MarketplaceStore dead = new MarketplaceStore(new JdbcTemplate(), mapper);   // no data source
        dead.init();

        assertThat(dead.isAvailable()).isFalse();
        assertThat(dead.listVisible(A_MEMBER)).isEmpty();
        assertThat(dead.installsOf("org_a")).isEqualTo(Map.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dead.insert(item("x", "global", "published", "o", "u"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unavailable");
    }
}
