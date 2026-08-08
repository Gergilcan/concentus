package com.concentus.llm;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Finds the few tools relevant to a task, out of a server's hundreds.
 *
 * <h2>Why</h2>
 * A tool definition is a JSON schema in the prompt. Holded's MCP server exposes 338 of them —
 * roughly fifty thousand tokens before the conversation starts, which no self-hosted context will
 * hold. The model server then truncates without saying so, and the model reports having only the
 * handful that survived. Choosing an allowlist by hand fixes that, but only when you know in
 * advance which area an agent will touch.
 *
 * <p>So instead of loading every definition up front, the agent is given one tool that searches
 * them. It asks for "customer contact records", gets five schemas back, and calls the real tool by
 * name. This is the pattern the industry converged on — Claude Code ships MCP Tool Search for
 * exactly this reason, and SEP-1821 proposes it as a protocol extension.
 *
 * <h2>How the ranking works</h2>
 * Embeddings when they are available: the tool corpus is embedded once per server, stored in
 * pgvector, and queried by cosine distance. That is what makes "who are my customers" find
 * {@code list_contacts}, a phrase sharing not one word with the tool's name.
 *
 * <p><b>It degrades rather than failing.</b> No pgvector, no embedding model, or a database that
 * is simply down, and it falls back to lexical matching over names and descriptions. Worse
 * ranking, still useful, and the run says which one it used — a search feature that takes the
 * whole flow down with it when an extension is missing would be a poor trade.
 */
@Component
public class ToolSearchIndex {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchIndex.class);

    private final JdbcTemplate jdbc;
    private final LocalModelClient client;
    private final String embeddingModel;
    private final boolean enabled;

    /** Null until the first successful index; also the flag for "vectors are usable". */
    private volatile Integer dimensions;
    private volatile boolean vectorReady;

    public ToolSearchIndex(JdbcTemplate jdbc, LocalModelClient client,
                           @Value("${local-model.embedding-model:bge-m3}") String embeddingModel,
                           @Value("${local-model.tool-search-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.enabled = enabled;
    }

    /** One tool as the search returns it, with the schema the model needs to call it. */
    public record Hit(String name, String description, String schemaJson, double score) {
    }

    /**
     * Whether semantic ranking is actually going to happen, and if not, which half is missing.
     *
     * <p>Reported because the two halves — the pgvector extension and an embedding model on the
     * server — are configured in completely different places, and when either is absent the system
     * quietly does something worse instead. "It fell back" is only useful with "because of this".
     *
     * @param modelPresent whether the server reports serving {@link #embeddingModel()}. Checked
     *                     against its own list rather than by trying an embed, so asking is cheap
     *                     and does not load a model into VRAM to answer a status question
     */
    public record Health(boolean enabled, String embeddingModel, boolean modelPresent,
                         boolean vectorReady, String detail) {
    }

    public String embeddingModel() {
        return embeddingModel;
    }

    public Health health(java.util.Set<String> servedModels) {
        boolean present = servedModels.stream()
                .anyMatch(m -> m.equalsIgnoreCase(embeddingModel)
                        // Ollama reports `bge-m3:latest` for what you pulled as `bge-m3`.
                        || m.toLowerCase(Locale.ROOT).startsWith(embeddingModel.toLowerCase(Locale.ROOT) + ":"));
        String detail;
        if (!enabled) {
            detail = "Tool search is off (local-model.tool-search-enabled=false).";
        } else if (!vectorReady && !present) {
            detail = "Ranking by word overlap: pgvector is missing and '" + embeddingModel
                    + "' is not on the model server.";
        } else if (!vectorReady) {
            detail = "Ranking by word overlap: the database has no pgvector extension. "
                    + "The embedded PostgreSQL does not ship it, so semantic ranking is "
                    + "unavailable in the desktop app.";
        } else if (!present) {
            detail = "Ranking by word overlap: the model server does not serve '" + embeddingModel
                    + "'. Pull it — `ollama pull " + embeddingModel + "` — it is the same server "
                    + "that runs your chat model, on the same URL.";
        } else {
            detail = "Semantic ranking, using '" + embeddingModel + "'.";
        }
        return new Health(enabled, embeddingModel, present, vectorReady, detail);
    }

    /** How a result set was ranked, so the run can say which and nobody has to guess. */
    public enum Ranking { VECTOR, LEXICAL }

    public record Results(List<Hit> hits, Ranking ranking, String note) {
    }

    @PostConstruct
    void ensureSchema() {
        if (!enabled) {
            log.info("MCP tool search is disabled (local-model.tool-search-enabled=false).");
            return;
        }
        try {
            // The extension is the thing most likely to be absent: the stock postgres image does
            // not carry it. Asked for rather than assumed, and a failure here is not fatal.
            jdbc.execute("create extension if not exists vector");
            vectorReady = true;
        } catch (RuntimeException e) {
            log.warn("pgvector is not available ({}), so MCP tool search will rank lexically. "
                    + "The extension is built per platform and shipped with the installer; a "
                    + "locally built jar carries none.", e.getMessage());
        }
    }

    /**
     * Creates the table once the embedding width is known.
     *
     * <p>The width comes from the model — 1024 for bge-m3, 768 for nomic-embed-text — and pgvector
     * needs it in the column type. A stored index built at another width is unusable, so it is
     * rebuilt rather than migrated: these are a cache of something we can always recompute.
     */
    private void ensureTable(int dims) {
        if (dimensions != null && dimensions == dims) return;
        synchronized (this) {
            if (dimensions != null && dimensions == dims) return;
            jdbc.execute("""
                    create table if not exists mcp_tool_index (
                      server_url   text not null,
                      tool_name    text not null,
                      description  text,
                      schema_json  text,
                      corpus_hash  text,
                      primary key (server_url, tool_name)
                    )""");
            Integer existing = currentDimensions();
            if (existing != null && existing != dims) {
                log.warn("The tool index was built with {}-dimensional vectors and the embedding "
                        + "model now returns {}. Rebuilding it.", existing, dims);
                jdbc.execute("alter table mcp_tool_index drop column embedding");
                existing = null;
            }
            if (existing == null) {
                jdbc.execute("alter table mcp_tool_index add column if not exists embedding vector("
                        + dims + ")");
            }
            dimensions = dims;
        }
    }

    private Integer currentDimensions() {
        try {
            return jdbc.queryForObject("""
                    select atttypmod from pg_attribute
                     where attrelid = 'mcp_tool_index'::regclass and attname = 'embedding'
                       and not attisdropped""", Integer.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Makes sure this server's tools are indexed, and returns whether vectors are usable.
     *
     * <p>Keyed on a hash of the tool list, so re-indexing happens when the server's tools change
     * and never merely because a run started. Embedding 338 strings takes seconds on a local GPU;
     * doing it per run would add that to every single turn.
     */
    public boolean ensureIndexed(String serverUrl, List<ChatTypes.ToolSpec> tools) {
        if (!enabled || !vectorReady || tools.isEmpty()) return false;
        String hash = corpusHash(tools);
        try {
            Integer indexed = jdbc.queryForObject(
                    "select count(*) from mcp_tool_index where server_url = ? and corpus_hash = ?",
                    Integer.class, serverUrl, hash);
            if (indexed != null && indexed == tools.size()) return true;
        } catch (RuntimeException e) {
            // The table may not exist yet on the very first call; fall through and build it.
            log.debug("Tool index not readable yet: {}", e.getMessage());
        }

        try {
            List<String> corpus = tools.stream().map(ToolSearchIndex::textFor).toList();
            List<float[]> vectors = client.embed(embeddingModel, corpus);
            ensureTable(vectors.getFirst().length);

            jdbc.update("delete from mcp_tool_index where server_url = ?", serverUrl);
            for (int i = 0; i < tools.size(); i++) {
                ChatTypes.ToolSpec tool = tools.get(i);
                jdbc.update("""
                        insert into mcp_tool_index
                          (server_url, tool_name, description, schema_json, corpus_hash, embedding)
                        values (?, ?, ?, ?, ?, ?::vector)""",
                        serverUrl, tool.name(), tool.description(),
                        tool.parameters() == null ? "{}" : tool.parameters().toString(),
                        hash, literal(vectors.get(i)));
            }
            log.info("Indexed {} tools from {} for semantic search.", tools.size(), serverUrl);
            return true;
        } catch (RuntimeException e) {
            log.warn("Could not build the tool index for {} ({}); falling back to lexical search.",
                    serverUrl, e.getMessage());
            return false;
        }
    }

    /** The few tools most relevant to a phrase. */
    public Results search(String serverUrl, List<ChatTypes.ToolSpec> tools, String query, int limit) {
        int wanted = Math.max(1, Math.min(limit, 25));
        if (ensureIndexed(serverUrl, tools)) {
            try {
                return new Results(vectorSearch(serverUrl, query, wanted), Ranking.VECTOR, null);
            } catch (RuntimeException e) {
                log.warn("Vector search failed ({}); ranking lexically instead.", e.getMessage());
            }
        }
        return new Results(lexicalSearch(tools, query, wanted), Ranking.LEXICAL,
                enabled && vectorReady
                        ? "Ranked by word overlap — the embedding index is unavailable."
                        : "Ranked by word overlap — semantic search needs pgvector and an "
                          + "embedding model.");
    }

    private List<Hit> vectorSearch(String serverUrl, String query, int limit) {
        float[] q = client.embed(embeddingModel, List.of(query)).getFirst();
        return jdbc.query("""
                select tool_name, description, schema_json, embedding <=> ?::vector as distance
                  from mcp_tool_index
                 where server_url = ?
                 order by distance
                 limit ?""",
                (rs, i) -> new Hit(rs.getString("tool_name"), rs.getString("description"),
                        rs.getString("schema_json"), 1.0 - rs.getDouble("distance")),
                literal(q), serverUrl, limit);
    }

    /**
     * Word-overlap ranking, for when there is no index.
     *
     * <p>Deliberately simple: it scores a term in the tool's name far above one in its
     * description, because a name is what the tool <em>is</em> and a description mentions its
     * neighbours. It will not connect "who are my customers" to {@code list_contacts} — that is
     * what the embeddings are for — but it reliably finds "contact".
     */
    private static List<Hit> lexicalSearch(List<ChatTypes.ToolSpec> tools, String query, int limit) {
        List<String> terms = new ArrayList<>();
        for (String term : query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (term.length() > 2) terms.add(term);
        }
        List<Hit> scored = new ArrayList<>();
        for (ChatTypes.ToolSpec tool : tools) {
            String name = tool.name().toLowerCase(Locale.ROOT);
            String description = (tool.description() == null ? "" : tool.description())
                    .toLowerCase(Locale.ROOT);
            double score = 0;
            for (String term : terms) {
                if (name.contains(term)) score += 3;
                else if (description.contains(term)) score += 1;
            }
            if (score > 0) {
                scored.add(new Hit(tool.name(), tool.description(),
                        tool.parameters() == null ? "{}" : tool.parameters().toString(), score));
            }
        }
        scored.sort(Comparator.comparingDouble(Hit::score).reversed());
        return scored.size() > limit ? scored.subList(0, limit) : scored;
    }

    /** Name and description together: a name alone is too short to embed meaningfully. */
    private static String textFor(ChatTypes.ToolSpec tool) {
        String readable = tool.name().replace('_', ' ');
        return readable + ". " + (tool.description() == null ? "" : tool.description());
    }

    /** Identifies a tool set, so re-indexing tracks the server rather than the calendar. */
    private static String corpusHash(List<ChatTypes.ToolSpec> tools) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ChatTypes.ToolSpec tool : tools) {
                digest.update(tool.name().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                if (tool.description() != null) {
                    digest.update(tool.description().getBytes(StandardCharsets.UTF_8));
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 32);
        } catch (Exception e) {
            return "none";
        }
    }

    /** pgvector's text form: `[1.0,2.0,…]`. */
    static String literal(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
