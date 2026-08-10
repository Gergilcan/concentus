-- Per-run estimated cost, persisted so a monthly budget can be summed with one query instead of
-- re-pricing every historical run's node list on every start.
alter table runs add column if not exists cost_usd double precision default 0;
