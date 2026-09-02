CREATE TABLE IF NOT EXISTS user_settings (
  user_id    TEXT NOT NULL,
  scope      TEXT NOT NULL,
  settings   JSONB NOT NULL DEFAULT '{}',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, scope)
);

DROP TABLE IF EXISTS user_module_settings;

ALTER TABLE entry_point_groups DROP CONSTRAINT IF EXISTS entry_point_groups_category_check;
ALTER TABLE entry_point_groups ADD CONSTRAINT entry_point_groups_category_check
  CHECK (category IN ('applications','settings','features','user-settings'));
