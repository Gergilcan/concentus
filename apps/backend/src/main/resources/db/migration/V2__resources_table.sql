-- The table flows, agents, MCP definitions and database definitions live in.
--
-- It is also in V1, and that is not an oversight — it is the fix for a specific failure.
--
-- Installs that predate migrations are baselined at V1, which records V1 as already applied and
-- never runs it. That is correct for the tables those installs really do have, and wrong for this
-- one: `resources` arrived later, with the move of flows out of files, so a database from before
-- that has every other table and not this one. Baselining then skipped the only migration that
-- would have created it, and every flow, agent and MCP definition had nowhere to go — the stores
-- reported themselves unavailable and the app showed an empty library while the user's files sat
-- untouched on disk.
--
-- Putting it above the baseline as well is what reaches those databases. `if not exists` makes it a
-- no-op everywhere else: a database created by V1 already has it, and one created fresh gets it
-- from V1 a moment earlier.
--
-- The general rule this is an instance of: a baseline says "the schema at this version already
-- exists". Anything added after those installs were made cannot be described by it, and belongs in
-- a migration above it.
create table if not exists resources (
  kind text not null,
  id text not null,
  sort_key text,
  json text not null,
  updated_at bigint not null,
  primary key (kind, id)
);
