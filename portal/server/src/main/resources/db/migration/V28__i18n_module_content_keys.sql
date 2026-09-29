-- Module-content wording (plan/NORMALIZATION_PLAN.md, D0e/D0h): keys are renamed together
-- with the V27 table renames (registry.error.loadEntryPoints -> loadModuleContents,
-- registry.error.entryKey* -> contentKey*) and the dead pre-decommission favorites labels
-- are dropped with the favorites table (no template references them; they survived in the
-- seed catalog only). The seed catalog is insert-if-absent (Reconciler), so existing installs
-- need the explicit INSERT/DELETE here; fresh installs take the edited i18n-catalog.json
-- directly. Label inserts are guarded per language: V28 runs before the Reconciler seeds
-- i18n_languages, and the FK to i18n_languages must not fail a first-ever boot (fresh
-- installs receive the renamed keys from the catalog seed instead).
DELETE FROM i18n_labels
 WHERE key IN ('settings.general.favorites',
               'settings.general.favoritesHint',
               'settings.general.favoritesOffHint',
               'registry.error.loadEntryPoints',
               'registry.error.entryKeyRequired',
               'registry.error.entryKeyExists');

INSERT INTO i18n_labels (language_code, key, value)
SELECT l.code, k.key, k.value
FROM (VALUES
  ('en-GB', 'registry.error.loadModuleContents', 'Could not load module content.'),
  ('pt-PT', 'registry.error.loadModuleContents', 'Não foi possível carregar o conteúdo do módulo.'),
  ('fr-FR', 'registry.error.loadModuleContents', 'Impossible de charger le contenu du module.'),
  ('es-ES', 'registry.error.loadModuleContents', 'No se pudo cargar el contenido del módulo.'),
  ('en-GB', 'registry.error.contentKeyRequired', 'Content key is required.'),
  ('pt-PT', 'registry.error.contentKeyRequired', 'A chave do conteúdo é obrigatória.'),
  ('fr-FR', 'registry.error.contentKeyRequired', 'La clé du contenu est requise.'),
  ('es-ES', 'registry.error.contentKeyRequired', 'La clave del contenido es obligatoria.'),
  ('en-GB', 'registry.error.contentKeyExists',   'Content key "{key}" already exists.'),
  ('pt-PT', 'registry.error.contentKeyExists',   'A chave do conteúdo "{key}" já existe.'),
  ('fr-FR', 'registry.error.contentKeyExists',   'La clé du contenu « {key} » existe déjà.'),
  ('es-ES', 'registry.error.contentKeyExists',   'La clave del contenido «{key}» ya existe.')
) AS k(language_code, key, value)
JOIN i18n_languages l ON l.code = k.language_code
ON CONFLICT (language_code, key) DO NOTHING;

-- Bump the label-cache version so running clients drop the removed/renamed keys
-- (only when the settings row exists; first-ever boot seeds it with the new version).
UPDATE i18n_settings
   SET content_version = content_version + 1
 WHERE id = 1
   AND EXISTS (SELECT 1 FROM i18n_labels WHERE key = 'registry.error.loadModuleContents');