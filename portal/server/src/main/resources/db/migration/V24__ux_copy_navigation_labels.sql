-- Phase 1 UX copy (plan/UX_PLAN.md): tabs.noTabsHint pointed only at the
-- sidebar; the Home tab is the primary launcher. The seed catalog is
-- insert-if-absent (Reconciler), so existing installs need an explicit UPDATE;
-- fresh installs take the edited i18n-catalog.json values directly.
UPDATE i18n_labels
   SET value = 'Open an app from Home or the sidebar to get started'
 WHERE language_code = 'en-GB'
   AND key = 'tabs.noTabsHint';

UPDATE i18n_labels
   SET value = 'Abra uma aplicação no Início ou na barra lateral para começar'
 WHERE language_code = 'pt-PT'
   AND key = 'tabs.noTabsHint';

UPDATE i18n_labels
   SET value = 'Ouvrez une app depuis l’accueil ou la barre latérale pour commencer'
 WHERE language_code = 'fr-FR'
   AND key = 'tabs.noTabsHint';

UPDATE i18n_labels
   SET value = 'Abra una app desde Inicio o la barra lateral para empezar'
 WHERE language_code = 'es-ES'
   AND key = 'tabs.noTabsHint';

-- Client label bundles are cached in localStorage keyed by content_version —
-- bump it so running clients pick up the new copy.
UPDATE i18n_settings
   SET content_version = content_version + 1
 WHERE id = 1;
