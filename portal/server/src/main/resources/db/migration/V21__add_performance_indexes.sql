-- Performance indexes for the highest-traffic query patterns.
-- The composite conversations index also serves user_id-only lookups
-- (leftmost prefix), so no separate single-column index is needed.
CREATE INDEX IF NOT EXISTS idx_ai_hub_conversations_user_origin_updated
  ON ai_hub_conversations (user_id, origin, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_entry_points_category
  ON entry_points (category);

CREATE INDEX IF NOT EXISTS idx_entry_points_group_key
  ON entry_points (group_key);

CREATE INDEX IF NOT EXISTS idx_module_versions_module_installed
  ON module_versions (module_key, installed_at DESC);
