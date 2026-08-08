-- The schema as it stood when it was still created by hand, one `create table if not exists` per
-- store, scattered across eight classes. Collected here so there is one place to read it and a
-- record of which version a database is on.
--
-- Everything is `if not exists` on purpose. Installs that predate Flyway already have these tables
-- and are baselined at this version, so V1 is normally skipped for them — but a baseline that ever
-- disagreed with reality would then fail on a table it could not create, and the guard makes that
-- harmless instead. It costs nothing on a fresh database.
--
-- One table is deliberately absent: mcp_tool_index. Its embedding column is `vector(N)`, where N is
-- the width of whatever embedding model is configured, so it cannot be written down ahead of time.
-- ToolSearchIndex still creates that one at runtime, and adjusts it when the model changes.

-- --------------------------------------------------------------------------- runs
create table if not exists runs (
  id text primary key,
  flow_id text,
  flow_name text,
  mode text,
  backend text,
  status text,
  trigger_type text,
  session_id text,
  local_session_id text,
  local_started boolean default false,
  error text,
  total_input_tokens bigint default 0,
  total_output_tokens bigint default 0,
  flow_json text,
  events_json text,
  node_execs_json text,
  created_at bigint,
  updated_at bigint,
  -- Added to the table after the first release, when it was still done with `alter table ... add
  -- column if not exists` on every start. Part of the table here: an install that predates
  -- migrations already ran those alters and is baselined, and a fresh database gets them outright.
  initial_prompt text,
  notify_webhook text
);

-- --------------------------------------------------------------------------- flow history
create table if not exists flow_versions (
  flow_id text not null,
  version int not null,
  name text,
  flow_json text,
  created_at bigint,
  primary key (flow_id, version)
);

-- --------------------------------------------------------------------------- accounts
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

-- Email is the login handle, so it must be unique case-insensitively: "Ana@x.com" and "ana@x.com"
-- are the same person, and allowing both would make sign-in ambiguous.
create unique index if not exists users_email_lower_key on users (lower(email));
create index if not exists users_organization_idx on users (organization_id);

-- --------------------------------------------------------------------------- credentials
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

create index if not exists credentials_org_idx on credentials (organization_id, kind);
-- Labels are how a person picks one in a dropdown, so two with the same name in the same
-- organization would make the choice meaningless.
create unique index if not exists credentials_org_label_key
  on credentials (organization_id, lower(label));

-- --------------------------------------------------------------------------- mail triggers
create table if not exists processed_mail (
  flow_id text not null,
  folder text not null,
  identity text not null,
  subject text,
  sender text,
  run_id text,
  created_at bigint not null,
  primary key (flow_id, folder, identity)
);

create index if not exists processed_mail_recent_idx
  on processed_mail (flow_id, folder, created_at desc);

-- --------------------------------------------------------------------------- flows, MCP, agents
-- One table for every kind of id-keyed record. They differ only in which Java type their JSON
-- deserialises to, so separate tables would be the same columns repeated and a new kind would need
-- a migration rather than a constructor argument.
create table if not exists resources (
  kind text not null,
  id text not null,
  sort_key text,
  json text not null,
  updated_at bigint not null,
  primary key (kind, id)
);
