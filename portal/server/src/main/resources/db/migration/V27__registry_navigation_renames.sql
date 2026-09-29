-- Table-ownership normalization (plan/NORMALIZATION_PLAN.md, decisions D0a..D0i):
-- registry-owned tables get the registry_ prefix, the shell-nav grouping table moves to the
-- navigation namespace, instance settings are revealed as navigation settings, and the
-- pre-decommission legacy favorites table is dropped (no entity; V26 already dropped it in
-- environments that ran the hygiene migration — this statement is a no-op there and covers
-- installs restored from older dumps).
-- Postgres does not rename indexes/sequences/constraints with the table, so every dependent
-- object is renamed explicitly (names verified against a live dev schema).

-- === tables ===
ALTER TABLE modules              RENAME TO registry_modules;
ALTER TABLE module_versions      RENAME TO registry_module_versions;
ALTER TABLE entry_points         RENAME TO registry_module_contents;
ALTER TABLE entry_point_groups   RENAME TO navigation_groups;
ALTER TABLE instance_settings    RENAME TO navigation_settings;

-- === sequences (V19 widened them to bigint; column defaults reference the OID,
--     so renames here keep the nextval() defaults working) ===
ALTER SEQUENCE entry_points_id_seq       RENAME TO registry_module_contents_id_seq;
ALTER SEQUENCE module_versions_id_seq    RENAME TO registry_module_versions_id_seq;
ALTER SEQUENCE entry_point_groups_id_seq RENAME TO navigation_groups_id_seq;

-- === columns (entry-point wording -> module content; entry_url is kept: it is the MFE
--     program entry URL and a manifest contract key) ===
ALTER TABLE registry_module_contents RENAME COLUMN entry_key        TO content_key;
ALTER TABLE registry_module_contents RENAME COLUMN parent_entry_key TO parent_content_key;

-- === constraints (registry) ===
ALTER TABLE registry_modules RENAME CONSTRAINT modules_pkey TO registry_modules_pkey;
ALTER TABLE registry_module_versions RENAME CONSTRAINT module_versions_pkey TO registry_module_versions_pkey;
ALTER TABLE registry_module_versions RENAME CONSTRAINT module_versions_module_key_fkey
    TO registry_module_versions_module_key_fkey;
ALTER TABLE registry_module_contents RENAME CONSTRAINT entry_points_pkey TO registry_module_contents_pkey;
ALTER TABLE registry_module_contents RENAME CONSTRAINT entry_points_module_key_fkey
    TO registry_module_contents_module_key_fkey;
ALTER TABLE registry_module_contents RENAME CONSTRAINT entry_points_category_check
    TO registry_module_contents_category_check;
ALTER TABLE registry_module_contents RENAME CONSTRAINT entry_points_type_check
    TO registry_module_contents_type_check;
ALTER TABLE registry_module_contents RENAME CONSTRAINT entry_points_module_key_entry_key_key
    TO registry_module_contents_module_key_content_key_key;

-- === constraints (navigation) ===
ALTER TABLE navigation_groups RENAME CONSTRAINT entry_point_groups_pkey TO navigation_groups_pkey;
ALTER TABLE navigation_groups RENAME CONSTRAINT entry_point_groups_category_check
    TO navigation_groups_category_check;
ALTER TABLE navigation_groups RENAME CONSTRAINT entry_point_groups_group_key_key
    TO navigation_groups_group_key_key;
ALTER TABLE navigation_groups RENAME CONSTRAINT entry_point_groups_parent_key_fkey
    TO navigation_groups_parent_key_fkey;

-- === constraint (instance settings; singleton id=1) ===
ALTER TABLE navigation_settings RENAME CONSTRAINT instance_settings_pkey TO navigation_settings_pkey;
ALTER TABLE navigation_settings RENAME CONSTRAINT instance_settings_id_check TO navigation_settings_id_check;

-- === indexes ===
ALTER INDEX idx_entry_points_category RENAME TO idx_registry_module_contents_category;
ALTER INDEX idx_entry_points_group_key RENAME TO idx_registry_module_contents_group_key;
ALTER INDEX idx_module_versions_module_installed RENAME TO idx_registry_module_versions_module_installed;
ALTER INDEX idx_entry_point_groups_category RENAME TO idx_navigation_groups_category;
ALTER INDEX idx_entry_point_groups_parent_key RENAME TO idx_navigation_groups_parent_key;
