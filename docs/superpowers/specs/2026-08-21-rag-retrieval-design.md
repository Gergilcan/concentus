# Retrieval that finds the right passage

**Status:** approved 2026-08-21. Slice A of five.

## The problem

Concentus already has most of a RAG pipeline: documents are extracted, chunked and embedded, and
the passages nearest a run's prompt are appended to the agent's system prompt. What it does not
have is the part that decides whether those are the *right* passages.

Retrieval today is a single cosine scan with word overlap as a fallback for chunks that have no
vector. Semantic similarity alone has a known blind spot, and it is not an exotic one: an exact
token — an error code, a document number, `ISO-27001`, a filename — sits in embedding space beside
every one of its neighbours. Ask for a policy by its reference and the nearest vectors are twelve
other policies. A lexical index finds it on the first try.

The reverse case is just as real, which is why the answer is both rather than either: a question
phrased as "what do I do if somebody leaves the company" matches a document titled *Offboarding*
only semantically.

## What this slice builds

Hybrid retrieval — lexical and semantic, fused — followed by a cross-encoder that reorders the
survivors, feeding a context assembler that deduplicates, orders and cites. Plus a tool, so an
agent whose first answer raised a question can go and ask another one.

Four slices are deliberately **not** here: per-chunk metadata and permissions, an evaluation
harness, ANN indexing for scale, and wider ingestion. They are tracked separately. Each is
independently useful and none of them improves an answer that retrieved the wrong passage.

## Indexes

A `lexeme tsvector` column on `knowledge_chunks`, written at ingest, with a GIN index over it.

It is indexed under **two configurations concatenated** —
`to_tsvector('spanish', content) || to_tsvector('english', content)`. Concatenating tsvectors is
supported and yields both stemmings in one column, so *accesos* matches *acceso* and *policies*
matches *policy* without anybody declaring a language. The cost is a larger index and some cross-
language noise; what it buys is that a bilingual base — the normal case here — works with no
configuration at all.

The column is written rather than generated. A generated column would need an immutable literal
configuration, which forecloses ever varying it per base; a written column leaves that door open
without opening it now.

`KnowledgeDef` is deliberately untouched. Adding a `language` component to that record is the exact
shape of the Jackson-record trap this codebase has been bitten by before, and this design does not
need a language knob to work.

The migration backfills `lexeme` for everything already ingested, in SQL. Nothing to reindex by
hand, and no base that silently stops matching after an upgrade.

## Retrieval

```
semantic (cosine, top 50)  ─┐
                             ├─ RRF (k=60) ─ cross-encoder ─ top k
lexical (ts_rank_cd, top 50) ┘
```

The lexical branch resolves in SQL against the GIN index. The semantic branch keeps the existing
in-memory scan and its per-base cache: at desktop scale a scan costs single-digit milliseconds, and
replacing it with ANN is slice D, which matters past roughly 50k chunks and not before.

Fusion is Reciprocal Rank Fusion, `score = Σ 1/(k + rank)` with `k = 60`. RRF fuses *ranks*, not
scores, which is the property that matters here: a cosine similarity and a `ts_rank_cd` are not
comparable quantities, and any attempt to weight them against each other would be inventing an
exchange rate. Rank position is comparable by construction.

A chunk found by only one branch still surfaces. That is the point — the two branches disagree
precisely on the cases the other one is blind to.

## Reranking

A cross-encoder reads the query and a passage *together* and scores the pair, which is why it
outranks any bi-encoder that had to embed them apart. It is too slow to run over a whole base and
exactly right over fifty candidates.

`BuiltInReranker` mirrors `BuiltInEmbedder`: ONNX Runtime in-process, HuggingFace tokenizer, model
downloaded on demand into the data folder, the same state machine and progress reporting. No
server, no tokens, no per-query cost.

The model is `Xenova/bge-reranker-base` quantized — 266 MB plus a 16 MB tokenizer, XLM-RoBERTa
based and therefore multilingual, which for Spanish documents is not optional.

**Absence degrades, never fails.** With no reranker downloaded, retrieval returns the RRF order and
the UI says what is being left on the table. This is the rule `KnowledgeService` already documents
for embeddings, and a knowledge base that stopped answering because an optional model was missing
would be the first place it broke.

## Two queries

| Path | Query | What it answers |
|---|---|---|
| `search_<base>` tool | Whatever the agent writes | Questions that surface mid-conversation |
| Preload | The run's prompt, as today | What the flow always needs |

Both, not either. The preload is what an agent gets without having to know to ask; the tool is for
when the first answer raises a second question. This is the same pairing `PreRunSubflows` already
uses for sub-flows, for the same reason.

One tool per wired base, named and described from `KnowledgeDef.description` — which that record's
own documentation says is written for exactly this purpose. It slots into the per-run MCP server
that already turns API nodes into typed tools.

No query rewriting in this slice. The hybrid already blunts the long-prompt problem, because BM25
tolerates a long query far better than a single embedding of it does. Rewriting without slice C
measuring the result is optimising blind.

## Context assembly

- **Deduplicate.** Chunks overlap by 200 characters at ingest, so adjacent passages repeat text.
  Adjacent passages from one document are merged into a single span.
- **Order by document and position, never by score.** A document is read forwards. Score order
  hands the model a shuffled document and asks it to reason about a process.
- **Budget.** A character ceiling per source, ~12,000 by default, adjustable in Settings. Characters
  rather than tokens because this codebase has no tokeniser for the agent's model and a fabricated
  token count would be worse than an honest character one.
- **Cite.** Each span is numbered `[n]` with a footer mapping it to `document — passage k`, and the
  prompt asks for those markers in the answer.

## Observability

A `concentus.retrieval` span: base, candidate counts per branch, whether a rerank happened,
latency, k. No content — the same rule already written for the other five span names. What is
useful for debugging and what is safe to export are different sets.

## Testing

- **Unit.** RRF fusion over known rank lists; assembler dedup, ordering and budget; chunk merging.
- **Integration.** One fixture document, two queries: one that only the lexical branch can find (an
  exact identifier) and one that only the semantic branch can find (a paraphrase). Both must
  retrieve. This is the whole thesis of the slice, so it is the test that matters most.
- **Degradation.** Retrieval returns results with no reranker downloaded, asserted rather than
  assumed.
- **End to end.** The knowledge panel reports reranker state; a run with a knowledge node shows
  citations.

## Files

Migration `V12__knowledge_retrieval.sql`; new `KnowledgeRetriever`, `ContextAssembler`,
`BuiltInReranker`; `KnowledgeService` reduced to ingestion; `RagContextInjector`,
`RunToolsController`, `Telemetry`, `KnowledgeController`, and the knowledge panel in the frontend.
