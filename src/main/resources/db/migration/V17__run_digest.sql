ALTER TABLE run ADD COLUMN digest_sent_at TIMESTAMPTZ;
CREATE INDEX ix_run_undigested ON run (scope, finished_at)
    WHERE digest_sent_at IS NULL AND status IN ('COMPLETED', 'FAILED');
