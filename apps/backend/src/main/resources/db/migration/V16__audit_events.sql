-- Who did what, when: the audit trail.
--
-- Runs already say who started them and versions who saved them, which answers "who changed this
-- flow" one record at a time. An organization asks the other question — "what did this person do
-- last Tuesday", "who touched the credentials this quarter" — and that is a question across every
-- kind of record at once, answered from one table that every action appends to. Append-only by
-- convention: nothing here updates a row, and the only delete is retention's.
--
-- One row per action, not per changed field. What the action was is `kind` ("flow.saved",
-- "member.role_changed"); what it was done to is the subject, named twice — by id, which is
-- stable, and by label, which is what a person reads after the flow it names has been deleted.
-- Anything else the action wants remembered goes in detail_json, with one rule enforced at the
-- recording sites rather than here: never a secret value. A credential's label, a setting's key,
-- whether a backup carried secrets — yes. The credential, the setting's value — never.
--
-- The actor is the signed-in address and the role it held AT THE TIME, copied rather than joined:
-- the role changes, the account is deleted, and the trail must still say what was true then.
-- Actions nobody was signed in for — a cron tick, a webhook, a mail trigger, the nightly purge —
-- are credited to "system:<trigger>", so the trail never has an unexplained blank where a name
-- should be.
--
-- A serial id rather than the text ids the other tables use: the trail is read newest-first and
-- paged by "everything before this row", and a monotonic integer is exactly that cursor — stable
-- across inserts in a way a timestamp with same-millisecond neighbours is not.
create table if not exists audit_events (
  id               bigserial primary key,
  at               bigint not null,
  organization_id  text not null,
  actor_email      text,
  actor_role       text,
  kind             text not null,
  subject_type     text,
  subject_id       text,
  subject_label    text,
  detail_json      text
);

-- The two ways it is read: the newest page of one organization's trail, and a date window of it
-- (the export, and retention's purge).
create index if not exists audit_events_org_id_idx on audit_events (organization_id, id desc);
create index if not exists audit_events_org_at_idx on audit_events (organization_id, at);
