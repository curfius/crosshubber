import type { Type } from '@angular/core';

/**
 * Loadable embedded modules in this build.
 * Keys MUST match the authoritative backend catalog
 * (portal/src/shared/embedded-catalog.ts) — enforced by embedded-modules.spec.ts.
 */
export const embeddedModules: Record<string, () => Promise<Type<unknown>>> = {
  'sample-embedded': () => import('../../embedded-modules/sample-embedded/sample-embedded.component').then((m) => m.SampleEmbedded),
  'module-registry': () => import('../modules/module-registry/module-registry.component').then((m) => m.ModuleRegistry),
  'portal-dashboard': () => import('../modules/home-dashboard/dashboard.component').then((m) => m.PortalDashboard),
  'settings': () => import('../modules/settings/settings.component').then((m) => m.Settings),
  'user-settings': () => import('../modules/user-settings/user-settings.component').then((m) => m.UserSettings),
  'user-settings-general': () => import('../modules/user-settings/user-settings-general.component').then((m) => m.UserSettingsGeneral),
  'ai-hub': () => import('../modules/ai-hub/ai-hub.component').then((m) => m.AiHub),
  'ai-hub-settings': () => import('../modules/ai-hub/settings/settings.component').then((m) => m.AiHubSettings),
  'ai-hub-providers': () => import('../modules/ai-hub/providers/providers.component').then((m) => m.AiHubProviders),
  'ai-hub-quick-chat': () => import('../modules/ai-hub/quick-chat/quick-chat.component').then((m) => m.AiHubQuickChat),
  'portal-general-settings': () => import('../modules/settings/portal-general-settings.component').then((m) => m.PortalGeneralSettings),
  'i18n-general': () => import('../modules/i18n-settings/i18n-general-settings.component').then((m) => m.I18nGeneralSettings),
  'i18n-languages': () => import('../modules/i18n-settings/i18n-languages.component').then((m) => m.I18nLanguages),
  'i18n-labels': () => import('../modules/i18n-settings/i18n-labels-editor.component').then((m) => m.I18nLabelsEditor),
  'portal-navigation': () => import('../modules/navigation/portal-navigation.component').then((m) => m.PortalNavigation),
  'navigation-pinned-apps': () => import('../modules/navigation/pinned-apps-editor.component').then((m) => m.PinnedAppsEditor),
  'navigation-sidebar': () => import('../modules/navigation/sidebar-nav-editor.component').then((m) => m.SidebarNavEditor),
  'navigation-settings': () => import('../modules/navigation/settings-nav-editor.component').then((m) => m.SettingsNavEditor),
  'navigation-user-settings': () => import('../modules/navigation/user-settings-nav-editor.component').then((m) => m.UserSettingsNavEditor),
  'navigation-portal': () => import('../modules/navigation/portal-nav-settings.component').then((m) => m.PortalNavSettings),
};

/** Valid embedded load paths in this build — derived, never hand-maintained. */
export const EMBEDDED_LOAD_PATHS: string[] = Object.keys(embeddedModules);
