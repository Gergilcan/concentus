-- Runners: machines somebody operates that execute a run's claude processes for this backend.
--
-- A runner connects outbound to this backend with a token minted here, and the backend hands it
-- the turns to execute: the working directory, the clones, the process. The Claude login stays on
-- the runner — that is the whole reason runners exist, and why nothing about a login is stored
-- here. What IS stored is the registration: whose organization, what it is called, who may run
-- flows on it (the scope), and the hash of the token it presents.
--
-- The scope is one of three, and it is the row's only rule: 'organization' — any account of the
-- organization may run flows on it; 'group' — the members of `group_id` (and the organization's
-- administrators); 'user' — `user_id` alone, because it is that person's machine and that
-- person's login. A schedule or a webhook, which launches with nobody signed in, may use the
-- organization's runners and the runners of its flow's group, never somebody's own.
--
-- The token is never stored; its SHA-256 is, unique, so a presented token resolves to one row or
-- none — the same shape as service_accounts, for the same reason. Revocation keeps the row with a
-- timestamp: which machines could execute for this organization, and until when, is an answer an
-- audit needs afterwards.
--
-- Two columns on runs record which runner a run executed on. History, not a pointer: the name is
-- copied so the run still says where it ran after the runner is deleted or renamed. `runs` is
-- re-declared `if not exists` for the V1-baselined installs, exactly as V20 does for
-- `credentials`, and there are no foreign keys, for the reason V8 through V20 give.

create table if not exists runners (
  id               text primary key,
  organization_id  text not null,
  name             text not null,
  scope            text not null,
  group_id         text,
  user_id          text,
  token_hash       text not null unique,
  created_by       text,
  created_at       bigint not null,
  last_seen_at     bigint,
  revoked_at       bigint
);

create index if not exists runners_org_idx on runners (organization_id);
-- A name is how a flow's settings pick a runner, so two with one name in one organization would
-- make the choice meaningless. Case-blind, like a group's.
create unique index if not exists runners_org_name_key on runners (organization_id, lower(name));

create table if not exists runs (
  id text primary key,
  initial_prompt text
);

alter table runs add column if not exists runner_id text;
alter table runs add column if not exists runner_name text;
