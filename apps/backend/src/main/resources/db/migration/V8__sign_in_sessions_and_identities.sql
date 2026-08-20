-- Staying signed in, and signing in with a company account.
--
-- persistent_logins is Spring Security's own remember-me schema, name for name: a session used to
-- live only in the servlet container, so every restart of the backend — an update, a reboot, a
-- crash — signed everyone out. On the desktop that was a shrug; on a deployment a team shares it
-- is a login screen after every deploy.
create table if not exists persistent_logins (
  username  varchar(255) not null,
  series    varchar(64) primary key,
  token     varchar(64) not null,
  last_used timestamp not null
);

-- Which external identity an account signs in with, when it is not a password.
--
-- Separate from users on purpose: an address is not an identity. The same person may arrive as
-- name@company.com through Entra today and through something else tomorrow, and the account —
-- with its role, its history, its authorship on every flow version — has to be the thing that
-- survives that. Deleting the row unlinks the provider without touching the account.
create table if not exists user_identities (
  provider         text not null,
  -- The provider's own immutable id for the person ("oid" in Entra). NOT the email: addresses are
  -- reassigned when people leave, and matching on one would hand a leaver's flows to their
  -- replacement.
  subject          text not null,
  -- No foreign key to users(id), deliberately. An installation that predates migrations is
  -- baselined at V1, so V1's tables are recorded as applied without being created — a constraint
  -- here would make this migration fail on exactly those databases, which is the failure mode
  -- the resources table already taught this project once. The relationship is enforced where it
  -- is created, in UserIdentityStore, and an orphaned row simply matches no account.
  user_id          text not null,
  organization_id  text not null,
  email            text,
  created_at       bigint not null,
  primary key (provider, subject)
);

create index if not exists idx_user_identities_user on user_identities(user_id);
