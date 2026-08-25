-- The human axis of spec 6.3 gains what "mandatory expiry" needs to be enforceable, and what
-- D50 needs to make an expiry visible rather than silent.
ALTER TABLE finding ADD COLUMN triaged_by TEXT;
-- NULL for every status except MUTED. The CHECK is what makes "indefinite mutes are how
-- monitoring goes blind" (spec 6.3) a property of the schema instead of a hope about the UI.
ALTER TABLE finding ADD COLUMN muted_until TIMESTAMPTZ;
-- Set by the sweep, never cleared. triage_reason is deliberately NOT cleared alongside it (D50):
-- the old reason next to a live occurrence count is what answers "is this mute still needed".
ALTER TABLE finding ADD COLUMN mute_expired_at TIMESTAMPTZ;
ALTER TABLE finding ADD CONSTRAINT ck_finding_mute_needs_expiry
    CHECK (triage_status <> 'MUTED' OR muted_until IS NOT NULL);

-- The sweep's only query. Partial, because it is the only state the sweep ever looks at.
CREATE INDEX ix_finding_mute_expiry ON finding (muted_until) WHERE triage_status = 'MUTED';
