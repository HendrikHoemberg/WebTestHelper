-- §8.1. dependent_site_ids was never read by production code: each dependent site's own run
-- already reports the cached result within the failure TTL, so the column and its merge on
-- re-store were dead weight. Drop it; the cache-driven propagation is unchanged.
ALTER TABLE external_url_check DROP COLUMN dependent_site_ids;
