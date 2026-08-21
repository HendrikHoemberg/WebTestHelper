-- Global key/value settings: SMTP, base URL, concurrency, redirect-all-mail (spec 11.4).
CREATE TABLE app_setting (
    setting_key TEXT PRIMARY KEY,
    setting_value TEXT,
    encrypted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
