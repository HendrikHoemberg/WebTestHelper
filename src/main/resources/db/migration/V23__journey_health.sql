-- §10.4, D106. Journey health tracking: consecutive failures, drift count, and last success.
-- A journey failing repeatedly with drift is flagged for re-recording.
ALTER TABLE journey
    ADD COLUMN last_success_at TIMESTAMPTZ,
    ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0,
    ADD COLUMN drift_count INT NOT NULL DEFAULT 0;
