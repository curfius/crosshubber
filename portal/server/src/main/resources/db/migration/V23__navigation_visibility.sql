-- Per-row hidden/visible state for the settings / user-settings navigation tree
-- editors (eye toggle). Hiding a section hides all entry points within it.
ALTER TABLE entry_point_groups
  ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE entry_points
  ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT FALSE;
