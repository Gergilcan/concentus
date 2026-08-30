-- Groups inside an organization.
--
-- An organization can be split into groups — the platform team, the support crew, a client's
-- squad — and two things follow the group rather than the organization: who sees a resource, and
-- which policy and settings a flow runs under. Nothing here replaces the organization: a group is
-- a subset of one organization's members, a group-scoped row is still that organization's row,
-- and the organization's administrators see and administer every group.
--
-- Four tables, all small. `groups` is the roster; `group_memberships` says who is in which, and
-- whether they manage it (a manager adds and removes members and edits the group's settings and
-- policy — an ADMIN of the organization may do all of that for every group, and is the only one
-- who creates, renames or deletes one). `group_settings` holds the per-group overrides of the
-- settings that are read per run, keyed like the `settings` table but by group; `group_policies`
-- holds one policy per group in the same JSON shape as the organization's, every field nullable —
-- null inherits the organization's value.
--
-- And one nullable column on each table whose rows can belong to a group: `resources` (a flow, an
-- MCP server, an agent, a facade, a knowledge base, a skill, a variable, a database),
-- `credentials`, `marketplace_items` (with the new scope value 'group') and `runs`. Null means
-- "the whole organization", which is what every existing row means, so no backfill: nothing that
-- exists today is scoped to a group, and the column's default says so. On `runs` the value is
-- copied from the flow at launch and records which group's settings and policy the run resolved
-- against — history, not a live pointer, which is why deleting a group un-scopes resources and
-- credentials but leaves the runs that already happened as they were.
--
-- No foreign keys, for the reason V8, V10, V16, V18 and V19 give: an installation that predates
-- migrations is baselined at V1 and its V1 tables may not exist, so a constraint here would fail
-- on exactly those databases. `credentials` is re-declared `if not exists` for the same reason V18
-- re-declares `users` — it is a V1 table, and the alter below has to find it on a database where
-- V1 was recorded as applied without having run.

create table if not exists groups (
  id               text primary key,
  organization_id  text not null,
  name             text not null,
  description      text,
  created_at       bigint not null,
  created_by       text
);

create index if not exists groups_org_idx on groups (organization_id);
-- A name is how a person picks a group in a select, so two with the same name in one organization
-- would make the choice meaningless. Case-blind, like a credential's label.
create unique index if not exists groups_org_name_key on groups (organization_id, lower(name));

create table if not exists group_memberships (
  group_id    text not null,
  user_id     text not null,
  manager     boolean not null default false,
  created_at  bigint not null,
  primary key (group_id, user_id)
);

-- "Which groups is this person in" is asked on every request, once, by GroupContext.
create index if not exists group_memberships_user_idx on group_memberships (user_id);

create table if not exists group_settings (
  organization_id  text not null,
  group_id         text not null,
  key              text not null,
  value            text not null,
  updated_at       bigint not null,
  primary key (group_id, key)
);

create table if not exists group_policies (
  group_id         text primary key,
  organization_id  text not null,
  json             text not null
);

create table if not exists credentials (
  id text primary key,
  organization_id text not null,
  label text not null,
  kind text not null,
  secret text not null,
  hint text,
  created_at bigint not null,
  updated_at bigint not null,
  last_used_at bigint
);

alter table resources add column if not exists group_id text;
alter table credentials add column if not exists group_id text;
alter table runs add column if not exists group_id text;
alter table marketplace_items add column if not exists group_id text;

create index if not exists resources_group_idx on resources (group_id);
create index if not exists credentials_group_idx on credentials (group_id);
create index if not exists runs_group_idx on runs (group_id);
create index if not exists marketplace_items_group_idx on marketplace_items (group_id);
