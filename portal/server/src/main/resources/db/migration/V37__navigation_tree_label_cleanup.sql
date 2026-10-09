-- Navigation tree unification (plan/NAV_WORKSPACES_PLAN.md Phase 3): the three shell
-- categories (applications / admin-settings / user-settings) replaced the old Portal
-- Navigation layout editor. Its copy is retired (no template references remain) and the
-- new editor tab label ships as navigation.portal.appNavTab (the template was renamed
-- before the catalog was). Seed catalog is insert-if-absent (Reconciler), so existing
-- installs need the explicit DELETE/INSERT here; fresh installs take the edited
-- i18n-catalog.json directly. Label inserts are guarded per language joining
-- i18n_languages so a first-ever boot (catalog seed already applied) stays consistent.
DELETE FROM i18n_labels
 WHERE key IN ('navigation.portal.newSection',
               'navigation.portal.treeTitle',
               'navigation.portal.empty',
               'navigation.portal.layoutTab');

INSERT INTO i18n_labels (language_code, key, value)
SELECT l.code, k.key, k.value
FROM (VALUES
  ('en-GB', 'navigation.portal.appNavTab', 'App Navigation'),
  ('pt-PT', 'navigation.portal.appNavTab', 'Navegação de aplicações'),
  ('fr-FR', 'navigation.portal.appNavTab', 'Navigation des applications'),
  ('es-ES', 'navigation.portal.appNavTab', 'Navegación de aplicaciones')
) AS k(language_code, key, value)
JOIN i18n_languages l ON l.code = k.language_code
ON CONFLICT (language_code, key) DO NOTHING;

-- Bump the label-cache version so running clients drop the retired keys
-- (only when the settings row exists; first-ever boot seeds it with the new version).
UPDATE i18n_settings
   SET content_version = content_version + 1
 WHERE id = 1
   AND EXISTS (SELECT 1 FROM i18n_labels WHERE key = 'navigation.portal.appNavTab');
