-- Tokens for machines.
--
-- A CI job, a cron entry or another system that runs a flow had one way in: a person's email and
-- password in its environment. That is a person's account acting from a place they are not, with
-- every power they have, and a password that has to change the day they leave. A service account
-- is the honest shape — a name, a role no higher than MEMBER (a token must never be able to mint
-- tokens), and a token shown exactly once.
--
-- The token itself is never stored. What is kept is its SHA-256, so a copy of this table is not a
-- copy of the credentials: a presented token is hashed and looked up, and a stolen row unlocks
-- nothing. Revocation keeps the row with a timestamp rather than deleting it, because "who could
-- act as what, and until when" is an answer an audit needs after the fact.
create table if not exists service_accounts (
  id               text primary key,
  -- Scoped, like every other record here: a token stands for an account IN an organization, and
  -- the organization is read from this row, never from the request.
  organization_id  text not null,
  name             text not null,
  -- VIEWER, OPERATOR or MEMBER. ADMIN is refused where a row is created and clamped where a token
  -- is read, so a hand-edited row cannot promote a machine either.
  role             text not null,
  -- Hex SHA-256 of the full token ("csa_" prefix included). Unique, so a hash resolves to one row.
  token_hash       text not null unique,
  -- No foreign key to users(id), deliberately — the same reason user_identities has none. An
  -- installation that predates migrations is baselined at V1, so a constraint here would fail on
  -- exactly those databases. The creator is recorded for the audit line, not enforced.
  created_by       text,
  created_at       bigint not null,
  -- Touched at most once a minute by the filter that reads the token, so a busy pipeline is one
  -- write a minute rather than one per request.
  last_used_at     bigint,
  revoked_at       bigint
);

create index if not exists idx_service_accounts_org on service_accounts(organization_id, created_at);
