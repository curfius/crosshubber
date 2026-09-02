CREATE TABLE IF NOT EXISTS navigation_pinned_apps (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    TEXT NOT NULL,
  parent_id  UUID REFERENCES navigation_pinned_apps(id) ON DELETE CASCADE,
  node_type  TEXT NOT NULL CHECK (node_type IN ('folder','item')),
  name       TEXT,
  ref        TEXT,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT navigation_pinned_apps_shape CHECK (
    (node_type = 'folder' AND name IS NOT NULL AND ref IS NULL) OR
    (node_type = 'item'   AND ref   IS NOT NULL AND name IS NULL)
  )
);
CREATE INDEX IF NOT EXISTS navigation_pinned_apps_user_idx
  ON navigation_pinned_apps (user_id);

-- One-time seed from legacy favorites (favorites table still exists here).
INSERT INTO navigation_pinned_apps (user_id, node_type, ref, sort_order)
SELECT f.user_id, 'item', f.item_key,
       ROW_NUMBER() OVER (PARTITION BY f.user_id ORDER BY f.created_at) * 10
FROM favorites f
WHERE f.item_type = 'app';

CREATE TABLE IF NOT EXISTS navigation_user_settings (
  user_id    TEXT PRIMARY KEY,
  settings   JSONB NOT NULL DEFAULT '{}',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS navigation_layout (
  id         INTEGER PRIMARY KEY CHECK (id = 1),
  settings   JSONB NOT NULL DEFAULT '{}',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Rename favoritesEnabled -> pinnedAppsEnabled (D9); value preserved.
UPDATE instance_settings
SET settings = (settings - 'favoritesEnabled')
    || jsonb_build_object('pinnedAppsEnabled', COALESCE(settings->'favoritesEnabled', 'true'::jsonb))
WHERE settings ? 'favoritesEnabled';
