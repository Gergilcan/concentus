package com.concentus.llm;

import com.concentus.store.PgVectorInstaller;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Finding the few relevant tools among a server's hundreds.
 *
 * <p>The behaviour that matters most is the degradation: this sits in front of every MCP call on
 * the self-hosted backend, and a search feature that takes the whole flow down when pgvector is
 * missing would be a bad trade. So the tests here mostly pin what happens when the good path is
 * unavailable — and, since the desktop installers started carrying the extension, what the good
 * path looks like on one of them.
 */
class ToolSearchIndexTest {

    private JdbcTemplate jdbc;
    private LocalModelClient client;
    private BuiltInEmbedder builtIn;

    /** The installer's record on a desktop built by the release workflow. */
    private static final PgVectorInstaller.Outcome SHIPPED = new PgVectorInstaller.Outcome(
            PgVectorInstaller.State.INSTALLED, "windows-amd64", "pgvector is installed.");
    /** The record on a jar somebody built with `mvn package` and no staged extension. */
    private static final PgVectorInstaller.Outcome LOCAL_BUILD = new PgVectorInstaller.Outcome(
            PgVectorInstaller.State.NOT_IN_JAR, "windows-amd64",
            "This jar was packaged without pgvector for windows-amd64.");
    /** The record on a deployment pointed at somebody else's PostgreSQL. */
    private static final PgVectorInstaller.Outcome EXTERNAL_DB = new PgVectorInstaller.Outcome(
            PgVectorInstaller.State.NOT_ATTEMPTED, null, "not attempted");

    private ToolSearchIndex index(boolean enabled, PgVectorInstaller.Outcome install) {
        return new ToolSearchIndex(jdbc, client, builtIn, () -> install, "bge-m3", enabled);
    }

    private static ChatTypes.ToolSpec tool(String name, String description) {
        return new ChatTypes.ToolSpec(name, description, new ObjectMapper().createObjectNode());
    }

    private static final List<ChatTypes.ToolSpec> TOOLS = List.of(
            tool("list_contacts", "Lists the contacts in the account."),
            tool("create_invoice", "Creates an invoice for a customer."),
            tool("list_employee_times", "Lists tracked employee hours."),
            tool("delete_webhook", "Removes a webhook subscription."));

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        client = mock(LocalModelClient.class);
        builtIn = mock(BuiltInEmbedder.class);
    }

    // ---------------------------------------------------------------- without the extension

    @Test
    void withoutPgvectorItRanksLexicallyAndSaysSo() {
        // A locally built jar carries no extension, and that must not break a run.
        doThrow(new RuntimeException("extension \"vector\" is not available"))
                .when(jdbc).execute(anyString());
        ToolSearchIndex idx = index(true, LOCAL_BUILD);
        idx.ensureSchema();

        ToolSearchIndex.Results results = idx.search("https://x/mcp", TOOLS, "contacts", 5);

        assertThat(results.ranking()).isEqualTo(ToolSearchIndex.Ranking.LEXICAL);
        assertThat(results.note()).contains("pgvector");
        assertThat(results.hits()).isNotEmpty();
        assertThat(results.hits().getFirst().name()).isEqualTo("list_contacts");
    }

    @Test
    void aJarWithoutTheExtensionIsBlamedRatherThanTheEmbeddedDatabase() {
        // The notice used to say the embedded PostgreSQL "does not ship" pgvector — untrue since
        // the installers started carrying it. The installer's own record says what is missing.
        doThrow(new RuntimeException("could not open extension control file"))
                .when(jdbc).execute(anyString());
        ToolSearchIndex idx = index(true, LOCAL_BUILD);
        idx.ensureSchema();

        ToolSearchIndex.Health health = idx.health(Set.of());

        assertThat(health.vectorReady()).isFalse();
        assertThat(health.detail())
                .contains("packaged without pgvector")
                .contains("could not open extension control file")
                .doesNotContain("does not ship");
    }

    @Test
    void anExternalDatabaseWithoutTheExtensionIsToldWhoInstallsIt() {
        // Nothing in this application installs anything on somebody else's server.
        doThrow(new RuntimeException("extension \"vector\" is not available"))
                .when(jdbc).execute(anyString());
        ToolSearchIndex idx = index(true, EXTERNAL_DB);
        idx.ensureSchema();

        assertThat(idx.health(Set.of()).detail())
                .contains("Install it on that server")
                .contains("pgvector");
    }

    // ---------------------------------------------------------------- with the extension

    @Test
    void onADesktopInstallTheBuiltInModelRanksByVectorWithoutAnyModelServer() {
        // The installer laid pgvector down, `create extension` succeeded, and the user downloaded
        // the built-in embedding model for the knowledge panel. Tool search must use both: the
        // old code embedded only through the model server and told this user to pull bge-m3.
        when(builtIn.isReady()).thenReturn(true);
        when(builtIn.embed(anyList(), anyBoolean())).thenReturn(List.of(new float[]{0.1f, 0.2f}));
        when(jdbc.queryForObject(contains("count(*)"), eq(Integer.class), any(), any()))
                .thenReturn(TOOLS.size());
        when(jdbc.query(contains("<=>"), any(RowMapper.class), any(), any(), any()))
                .thenReturn(List.of(new ToolSearchIndex.Hit("list_contacts", "Lists the contacts.",
                        "{}", 0.9)));
        ToolSearchIndex idx = index(true, SHIPPED);
        idx.ensureSchema();

        ToolSearchIndex.Results results = idx.search("https://x/mcp", TOOLS, "who are my customers", 5);

        assertThat(results.ranking()).isEqualTo(ToolSearchIndex.Ranking.VECTOR);
        assertThat(results.hits().getFirst().name()).isEqualTo("list_contacts");
        verifyNoInteractions(client);
    }

    @Test
    void healthOnADesktopInstallWithTheBuiltInModelIsSemantic() {
        when(builtIn.isReady()).thenReturn(true);
        ToolSearchIndex idx = index(true, SHIPPED);
        idx.ensureSchema();

        ToolSearchIndex.Health health = idx.health(Set.of());

        assertThat(health.vectorReady()).isTrue();
        assertThat(health.modelPresent()).isTrue();
        assertThat(health.embeddingModel()).contains(BuiltInEmbedder.MODEL_NAME);
        assertThat(health.detail()).startsWith("Semantic ranking");
    }

    @Test
    void withTheExtensionButNoEmbedderTheNoticeOffersBothWaysToGetOne() {
        when(builtIn.isReady()).thenReturn(false);
        ToolSearchIndex idx = index(true, SHIPPED);
        idx.ensureSchema();

        ToolSearchIndex.Health health = idx.health(Set.of("llama3"));

        assertThat(health.vectorReady()).isTrue();
        assertThat(health.modelPresent()).isFalse();
        assertThat(health.detail()).contains("built-in").contains("ollama pull bge-m3")
                .doesNotContain("pgvector");
    }

    @Test
    void theServersModelStillCountsWhenTheBuiltInOneIsAbsent() {
        // Ollama reports `bge-m3:latest` for what was pulled as `bge-m3`.
        when(builtIn.isReady()).thenReturn(false);
        ToolSearchIndex idx = index(true, SHIPPED);
        idx.ensureSchema();

        assertThat(idx.health(Set.of("bge-m3:latest")).modelPresent()).isTrue();
    }

    @Test
    void switchingEmbeddersChangesTheCorpusHashSoTheIndexIsRebuilt() {
        // A 384-wide query against 1024-wide rows fails at the database; the hash is what makes
        // the built-in model finishing its download mid-session re-embed rather than fail over.
        assertThat(ToolSearchIndex.corpusHash(TOOLS, "server:bge-m3"))
                .isNotEqualTo(ToolSearchIndex.corpusHash(TOOLS, "built-in:multilingual-e5-small"));
        assertThat(ToolSearchIndex.corpusHash(TOOLS, "server:bge-m3"))
                .isEqualTo(ToolSearchIndex.corpusHash(TOOLS, "server:bge-m3"));
    }

    // ---------------------------------------------------------------- lexical ranking itself

    @Test
    void aDisabledIndexNeverTouchesTheDatabase() {
        ToolSearchIndex idx = index(false, SHIPPED);
        idx.ensureSchema();

        assertThat(idx.ensureIndexed("https://x/mcp", TOOLS)).isFalse();
        verifyNoInteractions(client);
        verifyNoInteractions(jdbc);
    }

    @Test
    void aNameMatchOutranksADescriptionMatch() {
        // A name is what a tool *is*; a description often just mentions its neighbours.
        ToolSearchIndex idx = index(false, SHIPPED);

        var hits = idx.search("https://x/mcp", TOOLS, "invoice", 5).hits();

        assertThat(hits.getFirst().name()).isEqualTo("create_invoice");
    }

    @Test
    void severalWordsAccumulateScore() {
        ToolSearchIndex idx = index(false, SHIPPED);

        var hits = idx.search("https://x/mcp", TOOLS, "list employee hours", 5).hits();

        assertThat(hits.getFirst().name()).isEqualTo("list_employee_times");
    }

    @Test
    void veryShortWordsAreIgnoredSoTheyDoNotMatchEverything() {
        ToolSearchIndex idx = index(false, SHIPPED);

        // "an" and "of" would otherwise hit half the corpus through their descriptions.
        assertThat(idx.search("https://x/mcp", TOOLS, "an of", 5).hits()).isEmpty();
    }

    @Test
    void nothingRelevantReturnsNothingRatherThanTheWholeCatalogue() {
        // Failing open here would put every definition back in the prompt, which is the exact
        // problem this exists to solve.
        ToolSearchIndex idx = index(false, SHIPPED);

        assertThat(idx.search("https://x/mcp", TOOLS, "payroll deductions", 5).hits()).isEmpty();
    }

    @Test
    void theResultLimitIsRespectedAndBounded() {
        ToolSearchIndex idx = index(false, SHIPPED);

        assertThat(idx.search("https://x/mcp", TOOLS, "list", 1).hits()).hasSize(1);
        // A model asking for a thousand would undo the point of searching.
        assertThat(idx.search("https://x/mcp", TOOLS, "list", 9999).hits()).hasSizeLessThanOrEqualTo(25);
    }

    @Test
    void aHitCarriesTheSchemaSoTheModelCanCallIt() {
        // The whole trade is one round trip in exchange for not carrying every definition; that
        // only pays off if what comes back is enough to make the call.
        ToolSearchIndex idx = index(false, SHIPPED);

        assertThat(idx.search("https://x/mcp", TOOLS, "contacts", 1).hits().getFirst().schemaJson())
                .isNotBlank();
    }

    @Test
    void vectorLiteralsAreInPgvectorsTextForm() {
        assertThat(ToolSearchIndex.literal(new float[]{1.5f, -2.0f})).isEqualTo("[1.5,-2.0]");
    }
}
