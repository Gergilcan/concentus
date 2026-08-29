package com.concentus.llm;

import com.concentus.store.PgVectorInstaller;
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
import java.util.function.Supplier;

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
 * <p>The embedder is whichever one the knowledge bases use, in the same order of preference: the
 * built-in model when it has been downloaded, otherwise the model server's. It used to be the
 * server's alone, which on a desktop install — pgvector present, no Ollama, the built-in model
 * downloaded for the knowledge panel — meant ranking by word overlap while telling the user to
 * pull bge-m3. The two features share one embedding story now; see {@link BuiltInEmbedder}.
 *
 * <p><b>It degrades rather than failing.</b> No pgvector, no embedding model, or a database that
 * is simply down, and it falls back to lexical matching over names and descriptions. Worse
 * ranking, still useful, and the run says which one it used — a search feature that takes the
 * whole flow down with it when an extension is missing would be a poor trade.
 *
 * <p><b>Where pgvector comes from.</b> This is the only consumer of the extension in the
 * application — knowledge retrieval scans its vectors in memory ({@code KnowledgeRetriever}) and
 * never touches it. The release workflow compiles pgvector per platform into the jar, and
 * {@link PgVectorInstaller} lays it into the embedded PostgreSQL on every start, so a desktop
 * install HAS the extension and {@code create extension} below succeeds. What lacks it is a jar
 * built locally, an architecture nobody builds for, or an external PostgreSQL whose administrator
 * has not installed it — and {@link #health} says which, from the installer's own record, rather
 * than blaming the embedded database for something it stopped being guilty of.
 */
@Component
public class ToolSearchIndex {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchIndex.class);

    private final JdbcTemplate jdbc;
    private final LocalModelClient client;
    /** Null only when constructed by a test that is not about embedding. */
    private final BuiltInEmbedder builtIn;
    /** How the extension got into the embedded database, or why it did not. */
    private final Supplier<PgVectorInstaller.Outcome> extensionInstall;
    private final String embeddingModel;
    private final boolean enabled;

    /** Null until the first successful index; also the flag for "vectors are usable". */
    private volatile Integer dimensions;
    private volatile boolean vectorReady;
    /** PostgreSQL's own words when {@code create extension} was refused; null while it worked. */
    private volatile String extensionError;

    // Explicit, because a second constructor exists for tests and Spring will not choose between
    // two candidates on its own — it looks for a no-arg one and fails.
    @org.springframework.beans.factory.annotation.Autowired
    public ToolSearchIndex(JdbcTemplate jdbc, LocalModelClient client, BuiltInEmbedder builtIn,
                           @Value("${local-model.embedding-model:bge-m3}") String embeddingModel,
                           @Value("${local-model.tool-search-enabled:true}") boolean enabled) {
        this(jdbc, client, builtIn, PgVectorInstaller::outcome, embeddingModel, enabled);
    }

    /** Test seam: the installer's record is a value here, not a fact about the test machine. */
    ToolSearchIndex(JdbcTemplate jdbc, LocalModelClient client, BuiltInEmbedder builtIn,
                    Supplier<PgVectorInstaller.Outcome> extensionInstall, String embeddingModel,
                    boolean enabled) {
        this.jdbc = jdbc;
        this.client = client;
        this.builtIn = builtIn;
        this.extensionInstall = extensionInstall;
        this.embeddingModel = embeddingModel;
        this.enabled = enabled;
    }

    /** One tool as the search returns it, with the schema the model needs to call it. */
    public record Hit(String name, String description, String schemaJson, double score) {
    }

    /**
     * Whether semantic ranking is actually going to happen, and if not, which half is missing.
     *
     * <p>Reported because the two halves — the pgvector extension and an embedding model — are
     * configured in completely different places, and when either is absent the system quietly
     * does something worse instead. "It fell back" is only useful with "because of this".
     *
     * @param embeddingModel the embedder that would rank: the built-in model's name when it is
     *                       loaded, else the configured server model
     * @param modelPresent   whether that embedder can answer — the built-in model is loaded, or the
     *                       server reports serving the configured one. Checked against the server's
     *                       own list rather than by trying an embed, so asking is cheap and does not
     *                       load a model into VRAM to answer a status question
     */
    public record Health(boolean enabled, String embeddingModel, boolean modelPresent,
                         boolean vectorReady, String detail) {
    }

    public String embeddingModel() {
        return embeddingModel;
    }

    public Health health(java.util.Set<String> servedModels) {
        boolean served = servedModels.stream()
                .anyMatch(m -> m.equalsIgnoreCase(embeddingModel)
                        // Ollama reports `bge-m3:latest` for what you pulled as `bge-m3`.
                        || m.toLowerCase(Locale.ROOT).startsWith(embeddingModel.toLowerCase(Locale.ROOT) + ":"));
        boolean present = builtInReady() || served;
        String embedder = builtInReady() ? "built-in " + BuiltInEmbedder.MODEL_NAME : embeddingModel;
        String detail;
        if (!enabled) {
            detail = "Tool search is off (local-model.tool-search-enabled=false).";
        } else if (!vectorReady) {
            detail = "Ranking by word overlap: " + missingExtensionReason()
                    + (present ? "" : " " + missingEmbedderHint());
        } else if (!present) {
            detail = "Ranking by word overlap: " + missingEmbedderHint();
        } else {
            detail = "Semantic ranking, using " + embedder + ".";
        }
        return new Health(enabled, embedder, present, vectorReady, detail);
    }

    /**
     * Why {@code create extension vector} did not succeed, from the record of whoever was
     * responsible for making it possible.
     *
     * <p>On the embedded database that is {@link PgVectorInstaller}, which knows whether this jar
     * carries the extension at all. On an external database nothing here installs anything, and
     * the honest answer is the server's own error plus who can fix it.
     */
    private String missingExtensionReason() {
        PgVectorInstaller.Outcome install = extensionInstall.get();
        String said = extensionError == null ? "" : " PostgreSQL said: " + extensionError + ".";
        return switch (install.state()) {
            case NOT_ATTEMPTED -> "the PostgreSQL this installation uses has no pgvector "
                    + "extension." + said + " Install it on that server — the "
                    + "postgresql-17-pgvector package, or https://github.com/pgvector/pgvector — "
                    + "and restart; CREATE EXTENSION runs on start.";
            case INSTALLED -> "pgvector was copied into the embedded PostgreSQL but the server "
                    + "refused it." + said + " A library built for another PostgreSQL major "
                    + "version does this; the startup log has the details.";
            case NO_BUILD_FOR_PLATFORM, NOT_IN_JAR, FAILED -> install.detail() + said;
        };
    }

    private String missingEmbedderHint() {
        return "No embedding model can answer: download the built-in one under Knowledge "
                + "(no server needed), or serve '" + embeddingModel + "' from the model server — "
                + "`ollama pull " + embeddingModel + "` — on the same URL as your chat model.";
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
            // Asked for rather than assumed, and a failure here is not fatal. On the embedded
            // database the files were laid down by PgVectorInstaller a moment ago; on an external
            // one they are whatever the administrator installed.
            jdbc.execute("create extension if not exists vector");
            vectorReady = true;
            extensionError = null;
        } catch (RuntimeException e) {
            extensionError = e.getMessage();
            log.warn("pgvector is not available, so MCP tool search will rank lexically: {}",
                    missingExtensionReason());
        }
    }

    /**
     * Creates the table once the embedding width is known.
     *
     * <p>The width comes from the model — 384 for the built-in model, 1024 for bge-m3, 768 for
     * nomic-embed-text — and pgvector needs it in the column type. A stored index built at another
     * width is unusable, so it is rebuilt rather than migrated: these are a cache of something we
     * can always recompute. Every server's rows go, not only the one being indexed: the others
     * were embedded at the old width too, and a row whose vector column was dropped and re-added
     * would otherwise pass the "already indexed" check with nothing in it.
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
                jdbc.update("delete from mcp_tool_index");
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
     * <p>Keyed on a hash of the tool list and of the embedder, so re-indexing happens when the
     * server's tools change or a different model would now embed the query — never merely because
     * a run started. Embedding 338 strings takes seconds on a local GPU; doing it per run would
     * add that to every single turn.
     */
    public boolean ensureIndexed(String serverUrl, List<ChatTypes.ToolSpec> tools) {
        if (!enabled || !vectorReady || tools.isEmpty()) return false;
        String hash = corpusHash(tools, embedderId());
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
            List<float[]> vectors = embed(corpus, false);
            ensureTable(vectors.getFirst().length);

            jdbc.update("delete from mcp_tool_index where server_url = ?", serverUrl);
            // One batch, not one round trip per tool: the doc's own example server exposes 338.
            jdbc.batchUpdate("""
                    insert into mcp_tool_index
                      (server_url, tool_name, description, schema_json, corpus_hash, embedding)
                    values (?, ?, ?, ?, ?, ?::vector)""",
                    new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(java.sql.PreparedStatement ps, int i)
                                throws java.sql.SQLException {
                            ChatTypes.ToolSpec tool = tools.get(i);
                            ps.setString(1, serverUrl);
                            ps.setString(2, tool.name());
                            ps.setString(3, tool.description());
                            ps.setString(4, schemaJson(tool));
                            ps.setString(5, hash);
                            ps.setString(6, literal(vectors.get(i)));
                        }

                        @Override
                        public int getBatchSize() {
                            return tools.size();
                        }
                    });
            log.info("Indexed {} tools from {} for semantic search with {}.", tools.size(),
                    serverUrl, embedderId());
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
                        ? "Ranked by word overlap — no embedding model answered, so the index "
                          + "could not be used."
                        : "Ranked by word overlap — semantic search needs pgvector and an "
                          + "embedding model.");
    }

    private List<Hit> vectorSearch(String serverUrl, String query, int limit) {
        float[] q = embed(List.of(query), true).getFirst();
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

    // ------------------------------------------------------------------ embedding

    private boolean builtInReady() {
        return builtIn != null && builtIn.isReady();
    }

    /**
     * Which model embeds right now, as a string that changes when the answer does — it is part of
     * the corpus hash, so a switch (the built-in model finishing its download mid-session, say)
     * rebuilds the index at the new width instead of comparing a 384-wide query against 1024-wide
     * rows and failing over to word overlap for no visible reason.
     */
    private String embedderId() {
        return builtInReady() ? "built-in:" + BuiltInEmbedder.MODEL_NAME : "server:" + embeddingModel;
    }

    /**
     * Same preference as {@code KnowledgeRetriever}: the built-in model when loaded, then the
     * server. The E5 role prefixes are the built-in model's concern; the server's models take the
     * text as it is.
     */
    private List<float[]> embed(List<String> texts, boolean queries) {
        if (builtInReady()) {
            try {
                return builtIn.embed(texts, queries);
            } catch (RuntimeException e) {
                log.warn("Built-in embedding failed ({}); trying the model server.", e.getMessage());
            }
        }
        return client.embed(embeddingModel, texts);
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
                scored.add(new Hit(tool.name(), tool.description(), schemaJson(tool), score));
            }
        }
        scored.sort(Comparator.comparingDouble(Hit::score).reversed());
        return scored.size() > limit ? scored.subList(0, limit) : scored;
    }

    /** A tool's input schema as JSON. An absent schema is the empty object, never null. */
    private static String schemaJson(ChatTypes.ToolSpec tool) {
        return tool.parameters() == null ? "{}" : tool.parameters().toString();
    }

    /** Name and description together: a name alone is too short to embed meaningfully. */
    private static String textFor(ChatTypes.ToolSpec tool) {
        String readable = tool.name().replace('_', ' ');
        return readable + ". " + (tool.description() == null ? "" : tool.description());
    }

    /**
     * Identifies a tool set as embedded by one model, so re-indexing tracks the server and the
     * embedder rather than the calendar.
     */
    static String corpusHash(List<ChatTypes.ToolSpec> tools, String embedderId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(embedderId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
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
