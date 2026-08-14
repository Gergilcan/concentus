-- Version history gains authorship, and every run records the flow version it executed.
--
-- Both columns are additive and nullable on purpose: revisions and runs written before this
-- migration have no author and no version number, and the UI shows them as "—". Backfilling a
-- guess (the single local account, or "whatever version the flow is on now") would state as fact
-- something nobody recorded — an unsigned revision is honestly unsigned.

-- Repeated from V1 for the same reason `resources` is repeated in V2: installs that predate
-- migrations are baselined at V1, which marks it applied without running it. Those databases may
-- never have had this table, and `alter table` on a table that is not there fails the whole
-- migration — taking the app's schema with it. `if not exists` is a no-op everywhere else.
create table if not exists flow_versions (
  flow_id text not null,
  version int not null,
  name text,
  flow_json text,
  created_at bigint,
  primary key (flow_id, version)
);

alter table flow_versions add column if not exists author text;

-- The run's link to its flow's history by NUMBER. The exact graph a run executed is already
-- stored on the run (runs.flow_json); this is the human-readable anchor that lets an execution
-- and the Versions list name the same revision.
alter table runs add column if not exists flow_version int;
