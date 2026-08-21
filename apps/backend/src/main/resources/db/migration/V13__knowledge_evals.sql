-- A golden set per knowledge base: questions somebody will really ask, and which document holds
-- the answer.
--
-- Retrieval changes are otherwise unfalsifiable. Every ranking tweak sounds better than the last
-- one, and the only evidence anybody ever has is a handful of queries typed after the change, which
-- is how a system gets slowly worse while everyone reports it feeling sharper. Twenty questions with
-- known answers turn that into a number that can go down.
--
-- Expected documents are stored as a JSON array of names rather than a join table: a case names one
-- or two documents, they are edited as a unit, and nothing ever queries "which cases expect this
-- document". A join table would be three more statements for a question nobody asks.
--
-- No cascade to knowledge_chunks: deleting a document should NOT quietly delete the question it was
-- the answer to. A case whose document is gone is exactly the signal worth keeping — either the
-- document was removed by mistake, or the case needs a new answer, and both want a person to look.
create table if not exists knowledge_evals (
  id text primary key,
  base_id text not null,
  question text not null,
  expected_docs text not null,
  created_at bigint not null
);

create index if not exists knowledge_evals_base on knowledge_evals (base_id);
