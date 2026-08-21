-- Where a document came from, what it was, and who put it here.
--
-- Until now a chunk knew its base, its document's name and its position, which answers "what does
-- this say" and nothing else. Three questions a person asks of a knowledge base could not be
-- answered at all: where did this come from, is it still current, and who added it.
--
-- source_url is the answer to the first two at once. A document ingested from a page carries the
-- address it came from, which is what makes re-fetching it possible — a wiki page copied into a
-- base starts going out of date the moment it is copied, and the copy has no way back to the
-- original unless it keeps one.
--
-- content_type is the sniffed type rather than the file extension, because the extension is what
-- somebody typed and the type is what the bytes actually were.
--
-- ingested_by is an email, denormalised on purpose: an account can be deleted and the question
-- "who added this document" still has an answer afterwards, which is the entire point of asking.
alter table knowledge_chunks add column if not exists source_url text;
alter table knowledge_chunks add column if not exists content_type text;
alter table knowledge_chunks add column if not exists ingested_by text;

-- Documents that came from a page, so a refresh can find them without scanning every chunk.
create index if not exists knowledge_chunks_source_url
  on knowledge_chunks (base_id, source_url)
  where source_url is not null;
