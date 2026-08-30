-- The known-real anchor for the two-anchor soft-404 rule (spec 7.1): the site's root page.
ALTER TABLE run ADD COLUMN soft404_reference_status INTEGER;
ALTER TABLE run ADD COLUMN soft404_reference_simhash BIGINT;
ALTER TABLE run ADD COLUMN soft404_reference_text_length INTEGER;
