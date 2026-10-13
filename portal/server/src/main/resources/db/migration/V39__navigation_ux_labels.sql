-- Navigation UX batch: tab pop-out / close-all captions, General settings page
-- copy (page header, home section, features section, save failure) and the
-- Portal Navigation app block copy (workspaces block, search, open-in-tab,
-- relative times). Seed catalog is insert-if-absent (Reconciler), so existing
-- installs need the explicit INSERT here; fresh installs take the edited
-- i18n-catalog.json directly. Label inserts are guarded per language joining
-- i18n_languages so a first-ever boot (catalog seed already applied) stays consistent.
INSERT INTO i18n_labels (language_code, key, value)
SELECT l.code, k.key, k.value
FROM (VALUES
  ('en-GB', 'tabs.popOut', 'Open in new window'),
  ('pt-PT', 'tabs.popOut', 'Abrir em nova janela'),
  ('fr-FR', 'tabs.popOut', 'Ouvrir dans une nouvelle fenêtre'),
  ('es-ES', 'tabs.popOut', 'Abrir en ventana nueva'),
  ('en-GB', 'tabs.closeAllTabs', 'Close all tabs'),
  ('pt-PT', 'tabs.closeAllTabs', 'Fechar todos os separadores'),
  ('fr-FR', 'tabs.closeAllTabs', 'Fermer tous les onglets'),
  ('es-ES', 'tabs.closeAllTabs', 'Cerrar todas las pestañas'),
  ('en-GB', 'navigation.general.title', 'General'),
  ('pt-PT', 'navigation.general.title', 'Geral'),
  ('fr-FR', 'navigation.general.title', 'Général'),
  ('es-ES', 'navigation.general.title', 'General'),
  ('en-GB', 'navigation.general.description', 'Home application and navigation feature switches.'),
  ('pt-PT', 'navigation.general.description', 'Aplicação inicial e interruptores de funcionalidades de navegação.'),
  ('fr-FR', 'navigation.general.description', 'Application d’accueil et interrupteurs des fonctionnalités de navigation.'),
  ('es-ES', 'navigation.general.description', 'Aplicación inicial e interruptores de funciones de navegación.'),
  ('en-GB', 'navigation.general.homeApp', 'Home application'),
  ('pt-PT', 'navigation.general.homeApp', 'Aplicação inicial'),
  ('fr-FR', 'navigation.general.homeApp', 'Application d’accueil'),
  ('es-ES', 'navigation.general.homeApp', 'Aplicación inicial'),
  ('en-GB', 'navigation.general.homeAppHint', 'Application rendered by the unclosable Home tab.'),
  ('pt-PT', 'navigation.general.homeAppHint', 'Aplicação apresentada no separador Início (não pode ser fechado).'),
  ('fr-FR', 'navigation.general.homeAppHint', 'Application affichée dans l’onglet Accueil (infermable).'),
  ('es-ES', 'navigation.general.homeAppHint', 'Aplicación mostrada en la pestaña Inicio (no se puede cerrar).'),
  ('en-GB', 'navigation.general.featuresSection', 'Navigation features'),
  ('pt-PT', 'navigation.general.featuresSection', 'Funcionalidades de navegação'),
  ('fr-FR', 'navigation.general.featuresSection', 'Fonctionnalités de navigation'),
  ('es-ES', 'navigation.general.featuresSection', 'Funciones de navegación'),
  ('en-GB', 'navigation.general.saveFailed', 'Could not save the settings'),
  ('pt-PT', 'navigation.general.saveFailed', 'Não foi possível guardar as definições'),
  ('fr-FR', 'navigation.general.saveFailed', 'Impossible d’enregistrer les paramètres'),
  ('es-ES', 'navigation.general.saveFailed', 'No se pudo guardar la configuración'),
  ('en-GB', 'navigation.app.workspaces', 'Workspaces'),
  ('pt-PT', 'navigation.app.workspaces', 'Áreas de trabalho'),
  ('fr-FR', 'navigation.app.workspaces', 'Espaces de travail'),
  ('es-ES', 'navigation.app.workspaces', 'Espacios de trabajo'),
  ('en-GB', 'navigation.app.workspacesEmpty', 'No workspaces yet.'),
  ('pt-PT', 'navigation.app.workspacesEmpty', 'Ainda sem áreas de trabalho.'),
  ('fr-FR', 'navigation.app.workspacesEmpty', 'Aucun espace de travail pour le moment.'),
  ('es-ES', 'navigation.app.workspacesEmpty', 'Aún no hay espacios de trabajo.'),
  ('en-GB', 'navigation.app.searchPlaceholder', 'Search apps and workspaces…'),
  ('pt-PT', 'navigation.app.searchPlaceholder', 'Pesquisar apps e áreas de trabalho…'),
  ('fr-FR', 'navigation.app.searchPlaceholder', 'Rechercher des apps et espaces de travail…'),
  ('es-ES', 'navigation.app.searchPlaceholder', 'Buscar apps y espacios de trabajo…'),
  ('en-GB', 'navigation.app.openInNewTab', 'Open in new tab'),
  ('pt-PT', 'navigation.app.openInNewTab', 'Abrir em novo separador'),
  ('fr-FR', 'navigation.app.openInNewTab', 'Ouvrir dans un nouvel onglet'),
  ('es-ES', 'navigation.app.openInNewTab', 'Abrir en pestaña nueva'),
  ('en-GB', 'navigation.app.time.justNow', 'just now'),
  ('pt-PT', 'navigation.app.time.justNow', 'neste momento'),
  ('fr-FR', 'navigation.app.time.justNow', 'à l’instant'),
  ('es-ES', 'navigation.app.time.justNow', 'ahora mismo'),
  ('en-GB', 'navigation.app.time.minutesAgo', '{count}m ago'),
  ('pt-PT', 'navigation.app.time.minutesAgo', 'há {count} min'),
  ('fr-FR', 'navigation.app.time.minutesAgo', 'il y a {count} min'),
  ('es-ES', 'navigation.app.time.minutesAgo', 'hace {count} min'),
  ('en-GB', 'navigation.app.time.hoursAgo', '{count}h ago'),
  ('pt-PT', 'navigation.app.time.hoursAgo', 'há {count} h'),
  ('fr-FR', 'navigation.app.time.hoursAgo', 'il y a {count} h'),
  ('es-ES', 'navigation.app.time.hoursAgo', 'hace {count} h'),
  ('en-GB', 'navigation.app.time.daysAgo', '{count}d ago'),
  ('pt-PT', 'navigation.app.time.daysAgo', 'há {count} dias'),
  ('fr-FR', 'navigation.app.time.daysAgo', 'il y a {count} j'),
  ('es-ES', 'navigation.app.time.daysAgo', 'hace {count} días')
) AS k(language_code, key, value)
JOIN i18n_languages l ON l.code = k.language_code
ON CONFLICT (language_code, key) DO NOTHING;

-- Bump the label-cache version so running clients pick up the new copy
-- (only when the settings row exists; first-ever boot seeds it with the new version).
UPDATE i18n_settings
   SET content_version = content_version + 1
 WHERE id = 1
   AND EXISTS (SELECT 1 FROM i18n_labels WHERE key = 'tabs.popOut');
