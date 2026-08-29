-- Several organizations on one deployment.
--
-- What the schema already had: an organizations table, and a users.organization_id column that
-- named the one organization every account belonged to. What it did not have: any way for an
-- account to be in two, and any organization column at all on the records people actually build —
-- resources (flows, agents, MCP servers, facades, knowledge bases, skills, variables, databases)
-- and runs were deployment-wide, which the README said out loud.
--
-- Two changes, both additive.
--
-- MEMBERSHIPS. An account's organizations, with a role in each. users.organization_id stays and
-- changes meaning: it is now the CURRENT organization — the one the session is working in — and
-- users.role is the role held there, kept in step by the switch. Keeping both columns is what lets
-- every existing reader (the principal, the security filter, every store keyed by organization)
-- carry on unchanged; the memberships table is the truth they are copied from. Every existing
-- account becomes exactly one membership, in the organization it already named.
--
-- ORGANIZATION ON RESOURCES AND RUNS. Every existing row belongs to the one organization the
-- deployment had, so the backfill is not a guess: the oldest organizations row IS that
-- organization (AccountBootstrap creates it on every start, so an installation that ever ran has
-- exactly one). A row whose organization had no organizations row — possible only on a database
-- edited by hand — gets one named "Default", so no account is left pointing at nothing.
--
-- No foreign keys, for the reason V8 and V10 give: an installation that predates migrations is
-- baselined at V1, so V1's tables are recorded as applied without necessarily existing, and a
-- constraint here would fail on exactly those databases. The two tables this reads are
-- re-declared `if not exists` for the same reason V2 re-declares resources.

create table if not exists organizations (
  id text primary key,
  name text not null,
  created_at bigint not null
);

create table if not exists users (
  id text primary key,
  organization_id text not null,
  email text not null,
  password_hash text not null,
  role text not null,
  enabled boolean not null default true,
  created_at bigint not null
);

create table if not exists memberships (
  user_id          text not null,
  organization_id  text not null,
  -- VIEWER | OPERATOR | MEMBER | ADMIN, per organization: the same person may administer one
  -- and only read another.
  role             text not null,
  created_at       bigint not null,
  primary key (user_id, organization_id)
);

create index if not exists memberships_org_idx on memberships (organization_id);

-- An organization every account names but no row describes becomes a row, named "Default".
insert into organizations (id, name, created_at)
  select distinct u.organization_id, 'Default', (extract(epoch from now()) * 1000)::bigint
  from users u
  where not exists (select 1 from organizations o where o.id = u.organization_id);

-- Every existing account: one membership, in the organization it already belonged to, with the
-- role it already had.
insert into memberships (user_id, organization_id, role, created_at)
  select id, organization_id, role, created_at from users
  on conflict (user_id, organization_id) do nothing;

alter table resources add column if not exists organization_id text;
alter table runs add column if not exists organization_id text;

-- Everything that exists belongs to the organization the deployment had. On a fresh database
-- there are no rows and no organizations, and both statements do nothing; the stores stamp every
-- row they write from then on.
update resources
   set organization_id = (select id from organizations order by created_at, id limit 1)
 where organization_id is null;

update runs
   set organization_id = (select id from organizations order by created_at, id limit 1)
 where organization_id is null;

create index if not exists resources_org_idx on resources (organization_id, kind);
-- On the organization alone: an installation baselined at V1 may hold a runs table older than
-- some of its columns, and this index must not be the migration that fails there.
create index if not exists runs_org_idx on runs (organization_id);
