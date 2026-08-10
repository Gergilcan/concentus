-- Golden runs: one execution per flow marked as the reference others are compared against.
--
-- A column on runs rather than a field on the flow: the flag is a fact about an execution, it must
-- survive restarts with the run it marks, and putting it on the flow would snapshot it into every
-- version-history revision where it means nothing.
alter table runs add column if not exists golden boolean not null default false;
