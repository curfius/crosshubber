-- Remaining hot-path indexes not covered by V21.
-- entry_point_groups: category ordering is used by shell tree/config reads;
-- parent_key serves cascade checks for nested groups.
CREATE INDEX IF NOT EXISTS idx_entry_point_groups_category
  ON entry_point_groups (category);

CREATE INDEX IF NOT EXISTS idx_entry_point_groups_parent_key
  ON entry_point_groups (parent_key);

-- navigation_pinned_apps: FK used by tree lookups and cascade deletes.
CREATE INDEX IF NOT EXISTS idx_navigation_pinned_apps_parent_id
  ON navigation_pinned_apps (parent_id);
