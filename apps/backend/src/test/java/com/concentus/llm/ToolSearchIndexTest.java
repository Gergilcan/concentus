package com.concentus.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Finding the few relevant tools among a server's hundreds.
 *
 * <p>The behaviour that matters most is the degradation: this sits in front of every MCP call on
 * the self-hosted backend, and a search feature that takes the whole flow down when pgvector is
 * missing would be a bad trade. So the tests here mostly pin what happens when the good path is
 * unavailable.
 */
class ToolSearchIndexTest {

    private JdbcTemplate jdbc;
    private LocalModelClient client;

    private static ToolSearchIndex index(JdbcTemplate jdbc, LocalModelClient client, boolean enabled) {
        return new ToolSearchIndex(jdbc, client, "bge-m3", enabled);
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
    }

    @Test
    void withoutPgvectorItRanksLexicallyAndSaysSo() {
        // The stock postgres image has no vector extension, and that must not break a run.
        doThrow(new RuntimeException("extension \"vector\" is not available"))
                .when(jdbc).execute(anyString());
        ToolSearchIndex idx = index(jdbc, client, true);
        idx.ensureSchema();

        ToolSearchIndex.Results results = idx.search("https://x/mcp", TOOLS, "contacts", 5);

        assertThat(results.ranking()).isEqualTo(ToolSearchIndex.Ranking.LEXICAL);
        assertThat(results.note()).contains("pgvector");
        assertThat(results.hits()).isNotEmpty();
        assertThat(results.hits().getFirst().name()).isEqualTo("list_contacts");
    }

    @Test
    void aDisabledIndexNeverTouchesTheDatabase() {
        ToolSearchIndex idx = index(jdbc, client, false);
        idx.ensureSchema();

        assertThat(idx.ensureIndexed("https://x/mcp", TOOLS)).isFalse();
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void aNameMatchOutranksADescriptionMatch() {
        // A name is what a tool *is*; a description often just mentions its neighbours.
        ToolSearchIndex idx = index(jdbc, client, false);

        var hits = idx.search("https://x/mcp", TOOLS, "invoice", 5).hits();

        assertThat(hits.getFirst().name()).isEqualTo("create_invoice");
    }

    @Test
    void severalWordsAccumulateScore() {
        ToolSearchIndex idx = index(jdbc, client, false);

        var hits = idx.search("https://x/mcp", TOOLS, "list employee hours", 5).hits();

        assertThat(hits.getFirst().name()).isEqualTo("list_employee_times");
    }

    @Test
    void veryShortWordsAreIgnoredSoTheyDoNotMatchEverything() {
        ToolSearchIndex idx = index(jdbc, client, false);

        // "an" and "of" would otherwise hit half the corpus through their descriptions.
        assertThat(idx.search("https://x/mcp", TOOLS, "an of", 5).hits()).isEmpty();
    }

    @Test
    void nothingRelevantReturnsNothingRatherThanTheWholeCatalogue() {
        // Failing open here would put every definition back in the prompt, which is the exact
        // problem this exists to solve.
        ToolSearchIndex idx = index(jdbc, client, false);

        assertThat(idx.search("https://x/mcp", TOOLS, "payroll deductions", 5).hits()).isEmpty();
    }

    @Test
    void theResultLimitIsRespectedAndBounded() {
        ToolSearchIndex idx = index(jdbc, client, false);

        assertThat(idx.search("https://x/mcp", TOOLS, "list", 1).hits()).hasSize(1);
        // A model asking for a thousand would undo the point of searching.
        assertThat(idx.search("https://x/mcp", TOOLS, "list", 9999).hits()).hasSizeLessThanOrEqualTo(25);
    }

    @Test
    void aHitCarriesTheSchemaSoTheModelCanCallIt() {
        // The whole trade is one round trip in exchange for not carrying every definition; that
        // only pays off if what comes back is enough to make the call.
        ToolSearchIndex idx = index(jdbc, client, false);

        assertThat(idx.search("https://x/mcp", TOOLS, "contacts", 1).hits().getFirst().schemaJson())
                .isNotBlank();
    }

    @Test
    void vectorLiteralsAreInPgvectorsTextForm() {
        assertThat(ToolSearchIndex.literal(new float[]{1.5f, -2.0f})).isEqualTo("[1.5,-2.0]");
    }
}
