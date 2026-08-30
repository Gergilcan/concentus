-- The internal marketplace: what people publish for each other, and what each organization took.
--
-- An item is a thing a flow is built from — an MCP server, a library agent, a facade profile, a
-- skill, a plugin, an API endpoint, a flow template — published once and installed into any
-- organization with one click. It does NOT live in `resources`, and that is the one decision this
-- table exists for: `resources` is partitioned by organization and every reader filters on it,
-- which is exactly right for the things an organization builds and exactly wrong for the things it
-- shares. An item is visible ACROSS organizations — its scope says how far — so it needs a table
-- whose reads are written to cross that boundary on purpose, in one query, rather than a store
-- whose whole design is to forbid it.
--
-- Two scopes. `organization`: visible to the publishing organization's members, born `published`.
-- `global`: visible to every organization, born `pending` and waiting for a curator — an
-- administrator of the organization named by the setting `marketplace.curator-organization`, the
-- oldest one by default — to approve or reject it. The organization is kept on a global item as
-- "from", and the payload is the definition of the thing with its credentials stripped: an env
-- key, a header name, never a value.
--
-- Built-ins are the ones the app seeds from its bundled library (the MCP catalog, the library
-- agents, the starter flows). `built_in_hash` is what the seeder compares on every start: a changed
-- bundled definition is re-seeded, an unchanged one is left alone, and a deleted one stays deleted
-- because the seeder records what it installed, the way the starter flows already do.
--
-- No foreign keys, for the reason V8, V10 and V16 give: an installation that predates migrations is
-- baselined at V1 and its V1 tables may not exist, so a constraint here would fail on exactly those
-- databases. The author and the approver are recorded for the record, not enforced.
create table if not exists marketplace_items (
  id               text primary key,
  -- mcp | agent | facade | skill | plugin | api | flow
  kind             text not null,
  name             text not null,
  summary          text not null default '',
  description      text,
  -- A JSON array of strings; jsonb so a tag filter can use the containment operator later.
  tags             jsonb not null default '[]'::jsonb,
  -- Bumped when the payload changes on re-publish, so an organization that installed version 2
  -- can be told version 3 exists.
  version          integer not null default 1,
  -- organization | global
  scope            text not null,
  -- The publishing organization. Null only for built-ins, which no organization published.
  organization_id  text,
  -- published | pending | rejected
  status           text not null,
  -- The sentence a curator wrote when rejecting; read by the author.
  rejection        text,
  author_user_id   text not null,
  author_email     text,
  payload          jsonb not null,
  icon             text,
  built_in         boolean not null default false,
  built_in_hash    text,
  created_at       bigint not null,
  updated_at       bigint not null,
  published_at     bigint,
  approved_by      text
);

-- The two ways the list is read: "everything published globally" and "everything of my
-- organization", plus the curator's "everything global and pending".
create index if not exists marketplace_items_scope_status_idx on marketplace_items (scope, status);
create index if not exists marketplace_items_org_idx on marketplace_items (organization_id);

-- What each organization installed and which resource it became — so "Installed" and "Update
-- available" are shown from a record rather than guessed from a name, and an uninstall knows what
-- to remove. One row per item per organization: installing again is an update, not a second copy.
create table if not exists marketplace_installs (
  item_id          text not null,
  organization_id  text not null,
  -- The id of the resource created in that organization; null for kinds that create nothing
  -- server-side (an API endpoint is filled into a node, not stored).
  resource_id      text,
  -- The item's version at the time, compared with the item's current one.
  version          integer not null,
  installed_at     bigint not null,
  installed_by     text,
  primary key (item_id, organization_id)
);

create index if not exists marketplace_installs_org_idx on marketplace_installs (organization_id);
