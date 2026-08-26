-- V17 added digest_sent_at as a plain nullable column, so every run that already existed reads as
-- undigested. Nothing is in flight and the newest finish is long past the settle delay, so the
-- first cycle after the upgrade closes the entire run history as one window and mails it as
-- current news -- the exact opposite of the policy in spec 11.1.
--
-- Stamp what predates the feature. Only rows still NULL are touched, so re-running is a no-op.
UPDATE run
SET digest_sent_at = now()
WHERE digest_sent_at IS NULL
  AND status IN ('COMPLETED', 'FAILED');
