CREATE TABLE IF NOT EXISTS i18n_languages (
  code        TEXT PRIMARY KEY,
  name        TEXT NOT NULL,
  native_name TEXT NOT NULL,
  enabled     BOOLEAN NOT NULL DEFAULT TRUE,
  seeded      BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order  INTEGER NOT NULL DEFAULT 100,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS i18n_labels (
  language_code TEXT NOT NULL REFERENCES i18n_languages(code) ON DELETE CASCADE,
  key           TEXT NOT NULL,
  value         TEXT NOT NULL,
  updated_by    TEXT,
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (language_code, key)
);
CREATE INDEX IF NOT EXISTS i18n_labels_key_idx ON i18n_labels (key);

CREATE TABLE IF NOT EXISTS i18n_settings (
  id                INTEGER PRIMARY KEY CHECK (id = 1),
  default_language  TEXT NOT NULL REFERENCES i18n_languages(code),
  fallback_language TEXT NOT NULL REFERENCES i18n_languages(code),
  overrides         JSONB NOT NULL DEFAULT '{}',
  content_version   INTEGER NOT NULL DEFAULT 1,
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
