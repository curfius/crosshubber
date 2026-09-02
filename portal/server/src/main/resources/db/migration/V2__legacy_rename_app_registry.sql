DO $legacy_rename$
BEGIN
  IF to_regclass('entry_points') IS NOT NULL THEN
    ALTER TABLE entry_points
      DROP CONSTRAINT IF EXISTS entry_points_module_key_fkey;
  END IF;

  IF to_regclass('modules') IS NOT NULL THEN
    UPDATE modules
      SET key = 'module-registry', name = 'Module registry'
      WHERE key = 'app-registry';
  END IF;

  IF to_regclass('entry_points') IS NOT NULL THEN
    UPDATE entry_points SET module_key = 'module-registry'
      WHERE module_key = 'app-registry';
    UPDATE entry_points SET load_path = 'module-registry'
      WHERE load_path = 'app-registry';
    UPDATE entry_points SET name = 'Module registry', category = 'settings'
      WHERE load_path = 'module-registry' AND name = 'App registry';
    UPDATE entry_points SET category = 'settings'
      WHERE load_path = 'module-registry' AND category = 'applications';

    ALTER TABLE entry_points
      ADD CONSTRAINT entry_points_module_key_fkey
      FOREIGN KEY (module_key) REFERENCES modules(key) ON DELETE CASCADE;
  END IF;

  IF to_regclass('known_load_paths') IS NOT NULL THEN
    UPDATE known_load_paths SET load_path = 'module-registry'
      WHERE load_path = 'app-registry';
  END IF;

  IF to_regclass('module_settings') IS NOT NULL THEN
    UPDATE module_settings SET module_key = 'module-registry'
      WHERE module_key = 'app-registry';
  END IF;

  IF to_regclass('favorites') IS NOT NULL THEN
    UPDATE favorites SET item_key = 'module-registry'
      WHERE item_type = 'app' AND item_key = 'app-registry';
  END IF;

  IF to_regclass('workspaces') IS NOT NULL THEN
    UPDATE workspaces
      SET layout = replace(replace(layout::text,
            '"loadPath":"app-registry"', '"loadPath":"module-registry"'),
            '"moduleKey":"app-registry"', '"moduleKey":"module-registry"')::jsonb
      WHERE layout::text LIKE '%"app-registry"%';
    UPDATE workspaces
      SET groups = replace(replace(groups::text,
            '"loadPath":"app-registry"', '"loadPath":"module-registry"'),
            '"moduleKey":"app-registry"', '"moduleKey":"module-registry"')::jsonb
      WHERE groups::text LIKE '%"app-registry"%';
  END IF;
END
$legacy_rename$;
