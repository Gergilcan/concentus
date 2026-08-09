-- Document knowledge bases: the text of uploaded documents, chunked for retrieval.
--
-- The base itself (name, description) is a `resources` row like every other definition; this table
-- holds only the bulky part. Chunks are keyed by (base, document, position) so re-uploading a
-- document replaces its chunks without touching the rest of the base.
--
-- The embedding is a JSON float array in text, NOT a pgvector column, and that is a decision
-- rather than an accident. Similarity is computed in Java: at desktop scale (thousands of chunks,
-- not millions) a linear scan costs single-digit milliseconds, and it works identically whether or
-- not the pgvector extension loaded — which for external databases is not ours to guarantee.
-- A null embedding means the embedding model was not available at ingest; retrieval then falls
-- back to word overlap for those chunks, and they can be re-embedded by re-uploading.
create table if not exists knowledge_chunks (
  base_id text not null,
  doc_name text not null,
  seq int not null,
  content text not null,
  embedding text,
  created_at bigint not null,
  primary key (base_id, doc_name, seq)
);
