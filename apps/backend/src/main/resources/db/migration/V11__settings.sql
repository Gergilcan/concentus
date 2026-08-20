-- Configuration that belongs to the installation rather than to the machine it runs on.
--
-- Almost everything adjustable in this application was an environment variable, which means
-- changing any of it was: find where the process gets its environment, edit it, restart, and hope.
-- On a desktop install there is no such place a person can edit at all — the shell computes the
-- environment — so those settings were, in practice, not settings.
--
-- What stays in the environment is what has to: the database this table lives in, the key that
-- decrypts what is in it, the port to listen on, and the bootstrap administrator for a deployment
-- nobody sits in front of. Everything else can be a row, and a row can have a form in front of it.
create table if not exists settings (
  -- Scoped, like every other record here, so one tenant's limits are not another's.
  organization_id  text not null,
  -- The property key it overrides, verbatim ("app.runs.max-concurrent"). Deliberately the same
  -- name as the configuration it replaces: one vocabulary for the thing, whichever way it is set,
  -- and a value found here reads as an override of exactly that.
  key              text not null,
  -- Text, whatever the setting's type. What a number or a flag means is the reading code's
  -- business, and it already has to parse the environment's strings anyway.
  value            text,
  -- Sealed with the same key that protects stored credentials. A client secret in a settings table
  -- is a credential wherever it happens to be filed.
  secret           boolean not null default false,
  updated_at       bigint not null,
  updated_by       text,
  primary key (organization_id, key)
);
