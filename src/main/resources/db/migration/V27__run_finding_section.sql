-- A run report is a historical classification. Run ids intentionally remain a plain value here,
-- like the run-id columns on finding, because retention may remove the referenced run row later.
-- The header distinguishes an empty completed report from a legacy run without a snapshot.
CREATE TABLE run_report_snapshot (
    run_id BIGINT PRIMARY KEY
);

CREATE TABLE run_finding_section (
    run_id BIGINT NOT NULL REFERENCES run_report_snapshot (run_id) ON DELETE CASCADE,
    finding_id BIGINT NOT NULL REFERENCES finding (id) ON DELETE CASCADE,
    section TEXT NOT NULL,
    PRIMARY KEY (run_id, finding_id)
);

CREATE INDEX ix_run_finding_section_run ON run_finding_section (run_id);
