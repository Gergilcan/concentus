-- Accounts this browser has signed in with, so switching between them costs a click.
--
-- Two roles on one machine is the normal case while a permission model is being set up: you check
-- what an operator sees, then go back to being an admin to change it. Without this the loop is
-- sign out, retype an address, retype a password, and again in reverse — which is enough friction
-- that the check does not get done.
--
-- Why it is not simply held in the browser: a session is a cookie, and a second account would mean
-- a second credential kept where a page can read it. What the browser holds instead is one opaque
-- device id in an HttpOnly cookie, and the accounts attached to it live here. The rule this buys
-- is the one that matters — an account can only be attached by signing into it, with its own
-- password or its own provider.
--
-- The trust this does grant, plainly: whoever has this browser can become any account attached to
-- it, which is what "stay signed in" has always meant. Detaching one is a click, and every
-- attachment expires on its own.
create table if not exists device_accounts (
  -- The random id in this browser's cookie. Not a fingerprint of the machine: a value we minted,
  -- which the person can drop by clearing their cookies.
  device_id        text not null,
  -- No foreign key to users(id), deliberately — the same reason user_identities has none. An
  -- installation that predates migrations is baselined at V1, so a constraint here would fail on
  -- exactly those databases. An orphaned row simply matches no account and is ignored.
  user_id          text not null,
  organization_id  text not null,
  -- Denormalised so the switcher can name the accounts without reading every user row, and so a
  -- row whose account is gone still reads as something rather than as a blank line.
  email            text,
  added_at         bigint not null,
  last_used_at     bigint not null,
  primary key (device_id, user_id)
);

create index if not exists idx_device_accounts_last_used on device_accounts(last_used_at);
