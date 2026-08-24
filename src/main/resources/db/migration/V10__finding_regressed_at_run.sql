-- A regression is news in the run that brings the finding back, not in every run after it
-- (spec 6.3's Regressed section, read against spec 11.1's "mail on new or regressed ERROR
-- findings"). Branching the report on `resolved_at_run IS NOT NULL` made the flag permanent:
-- one unfixed regression then mailed on every run forever, which is exactly the failure mode
-- spec 6.4 exists to prevent, and an ACKNOWLEDGED finding that had ever regressed could never
-- return to the quiet Known section.
ALTER TABLE finding ADD COLUMN regressed_at_run BIGINT;

-- `resolved_at_run` keeps its own meaning: when the finding was last believed fixed. It is
-- history and stays set, so a finding's fix/regression cycle is still readable from the row.
COMMENT ON COLUMN finding.regressed_at_run IS
    'The run that revived this finding from RESOLVED; reported as Regressed in that run only.';

-- An ACTIVE finding that carries a resolved_at_run was revived at some point, and the run that
-- last observed it is the only estimate of when available from the row. Backfilling with it
-- leaves the most recent run''s report unchanged across the migration; every later run settles.
UPDATE finding SET regressed_at_run = last_seen_run
 WHERE observed_status = 'ACTIVE' AND resolved_at_run IS NOT NULL;
