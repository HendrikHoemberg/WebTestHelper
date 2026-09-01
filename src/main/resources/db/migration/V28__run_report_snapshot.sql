-- Existing installs were migrated before this header table was present in V27. The run-report
-- classification logic needs it to exist (FindingStore.snapshotOf). IF NOT EXISTS keeps this a
-- no-op on fresh installs where V27 already created it.
CREATE TABLE IF NOT EXISTS run_report_snapshot (
    run_id BIGINT PRIMARY KEY
);
