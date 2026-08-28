-- D107. Journey findings resolve only within the journeys a run actually finished replaying —
-- the third scope, after the crawl's pages (spec 6.4) and the interaction pass's per-type page
-- sets (D74). A run that replayed 3 of 5 journeys must leave the other two alone.
ALTER TABLE run ADD COLUMN covered_journey_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
