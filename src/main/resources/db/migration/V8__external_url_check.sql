-- The shared external URL cache (spec 8.1). Twenty sites linking to one partner URL cost one
-- request per TTL window instead of twenty per sweep — the largest single cost driver in a sweep,
-- and the largest source of UNVERIFIABLE findings, since we stop tripping third-party limits.
CREATE TABLE external_url_check (
    url            TEXT PRIMARY KEY,               -- NormalizedUrl.value(), spec 6.2's key
    status         TEXT        NOT NULL CHECK (status IN ('OK','DEAD','UNVERIFIABLE')),
    http_status    INTEGER     NOT NULL DEFAULT 0,
    content_type   TEXT,
    content_length BIGINT,
    body_prefix    TEXT,                           -- null when a HEAD was enough
    failure_text   TEXT,
    checked_at     TIMESTAMPTZ NOT NULL,
    -- Which sites depend on this URL: one that turns dead must produce findings on every
    -- affected site, not only the one that happened to re-check it (spec 8.1).
    dependent_site_ids JSONB   NOT NULL DEFAULT '[]'::jsonb
);

CREATE INDEX ix_external_url_check_checked_at ON external_url_check (checked_at);
