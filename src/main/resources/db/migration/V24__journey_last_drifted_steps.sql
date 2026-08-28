-- §10.4. Which steps drifted on the most recent replay, so the detail screen can name them.
-- drift_count (V23) is cumulative across every replay and cannot answer "which steps, last time" —
-- this column is overwritten by each completed replay rather than incremented.
ALTER TABLE journey ADD COLUMN last_drifted_step_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
