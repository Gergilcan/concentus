-- Lexical retrieval beside the semantic one.
--
-- Cosine similarity alone has a blind spot that is not exotic: an exact token — an error code, a
-- document reference, a filename — sits in embedding space beside every one of its neighbours.
-- Ask for a policy by its reference and the nearest vectors are twelve other policies. A lexical
-- index finds it first try. The reverse case is equally real, which is why retrieval now runs both
-- and fuses them rather than choosing.
--
-- Indexed under TWO configurations concatenated. `tsvector || tsvector` is supported and yields
-- both stemmings in one column, so 'accesos' matches 'acceso' and 'policies' matches 'policy'
-- without anybody having to declare the language of a base. It costs a larger index and some
-- cross-language noise; it buys a bilingual base — the normal case here — working with no
-- configuration at all.
--
-- Written rather than generated: a generated column needs an immutable literal configuration,
-- which forecloses ever varying this per base. A written column leaves that door closed but not
-- locked.
alter table knowledge_chunks add column if not exists lexeme tsvector;

-- Backfill, so a base ingested before this migration does not silently stop matching lexically
-- after an upgrade. Whoever ingested those documents is not going to re-upload them to find out.
update knowledge_chunks
   set lexeme = to_tsvector('spanish', content) || to_tsvector('english', content)
 where lexeme is null;

create index if not exists knowledge_chunks_lexeme on knowledge_chunks using gin (lexeme);
