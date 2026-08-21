-- Backstop for "no queued backlog per site": two rapid enqueues may both pass the
-- find-then-save check; the index converts the loser's insert into a duplicate key.
CREATE UNIQUE INDEX ux_run_single_queued_per_site ON run (site_id) WHERE status = 'QUEUED';
