-- Category fold (navigation trees unification): the 'settings' category no
-- longer exists — the admin settings tree lives in 'admin-settings' from this
-- release on (EmbeddedCatalog, the shell-tree gate and both upsert APIs move
-- with this migration).
--
-- The constraint dance is three steps because neither value-set is usable
-- as-is: V26's 4-value CHECK explicitly excluded 'admin-settings' ("admin
-- settings are modules, not groups" — retired by this fold), and rows still
-- carry 'settings'. So: widen to the union, fold the rows, then land on the
-- final 4-value set, which now matches the group-capable subset of
-- registry_module_contents. The Reconciler re-asserts builtin categories from
-- EmbeddedCatalog at every boot, so catalog and migration ship together.
ALTER TABLE navigation_groups DROP CONSTRAINT IF EXISTS navigation_groups_category_check;
ALTER TABLE navigation_groups
    ADD CONSTRAINT navigation_groups_category_check
    CHECK (category IN ('applications', 'settings', 'features', 'admin-settings', 'user-settings'));

UPDATE navigation_groups SET category = 'admin-settings' WHERE category = 'settings';
UPDATE registry_module_contents SET category = 'admin-settings' WHERE category = 'settings';

ALTER TABLE navigation_groups DROP CONSTRAINT navigation_groups_category_check;
ALTER TABLE navigation_groups
    ADD CONSTRAINT navigation_groups_category_check
    CHECK (category IN ('applications', 'admin-settings', 'features', 'user-settings'));
