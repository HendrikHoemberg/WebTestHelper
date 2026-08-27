-- Coverage stops being a cartesian product (D74): an interaction check runs on a handful of
-- pages, so the pages it was driven on are recorded separately from the pages the crawl visited.
-- Resolution reads them separately too, or a run that drove COOKIE_BANNER on the homepage would
-- silently resolve a COOKIE_BANNER finding on /kontakt (spec 6.4).
ALTER TABLE run
    ADD COLUMN covered_interaction_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN covered_interaction_check_types JSONB NOT NULL DEFAULT '[]'::jsonb;
