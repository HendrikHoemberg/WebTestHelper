-- V5's backstop was per site, which over-enforced spec 5.3: "one run at a time per site"
-- constrains what may be RUNNING (ux_run_single_active_per_site), not what may be QUEUED.
--
-- Spec 9 schedules pulse daily 03:00, full Sunday 03:00 and deep on the 1st at 03:00. On the
-- first Sunday-of-the-month all three fire for the same site; under the per-site index two of
-- them were silently deduped into whichever queued first, so the monthly deep run — the only
-- tier that submits forms and verifies mail — could vanish without trace.
--
-- Scoping the index keeps what the backstop was actually for (clicking "Jetzt prüfen" twice
-- must not build a backlog) while letting the three tiers queue side by side.
DROP INDEX ux_run_single_queued_per_site;

CREATE UNIQUE INDEX ux_run_single_queued_per_site_scope
    ON run (site_id, scope) WHERE status = 'QUEUED';
