-- Per-flow persistent memory: short notes agents leave for future runs of the same flow.
--
-- One row per note rather than one text blob per flow, so two overlapping runs appending at the
-- same time cannot overwrite each other's writes — the id sequence is the order notes arrived in,
-- and it is what "most recent" means when reading.
create table if not exists flow_memory (
  id bigserial primary key,
  flow_id text not null,
  -- The run that wrote it, for tracing a note back to the execution that learned it. Nullable and
  -- not a foreign key: the note deliberately outlives the run, which may be evicted or deleted.
  run_id text,
  note text not null,
  created_at bigint not null
);

create index if not exists flow_memory_flow_idx on flow_memory (flow_id, id desc);
