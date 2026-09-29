import { Injectable, signal } from '@angular/core';
import type { PortalUser } from '../models';
import type { ThemePolicy } from '../theme/theme.service';

export interface NavigationSettings {
  homeApp?: string;
  pinnedAppsEnabled?: boolean;
  workspacesEnabled?: boolean;
  defaultTheme?: string | null;
  enabledThemes?: string[] | null;
}

/**
 * Navigation settings service. The feature switches (pinned apps / workspaces)
 * are owned by the navigation module (NavigationStore.features) since D11;
 * this service carries the admin capability flag and the home-tab app
 * selection (instance-level, editable in General settings). The tenant theme
 * policy (defaultTheme/enabledThemes) is config-owned and only read here.
 */
@Injectable({ providedIn: 'root' })
export class SettingsService {
  readonly homeApp = signal<string>('navigation:portal');
  readonly isAdmin = signal(false);
  readonly themePolicy = signal<ThemePolicy | null>(null);

  init(user: PortalUser): void {
    this.isAdmin.set(user.roles.includes('portal-settings-edit'));
  }

  async load(): Promise<void> {
    try {
      const res = await fetch('/api/settings');
      if (!res.ok) return;
      const settings = (await res.json()) as NavigationSettings;
      if (settings.homeApp) this.homeApp.set(settings.homeApp);
      this.themePolicy.set({
        defaultTheme: settings.defaultTheme ?? null,
        enabledThemes: settings.enabledThemes ?? null,
      });
    } catch (err) {
      console.error('[settings] failed to load:', err);
    }
  }

  /** Persists the home-app selection (admin only; server re-validates). */
  async updateHomeApp(ref: string): Promise<boolean> {
    if (!this.isAdmin()) return false;
    try {
      const res = await fetch('/api/settings', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ homeApp: ref }),
      });
      if (!res.ok) return false;
      this.homeApp.set(ref);
      return true;
    } catch (err) {
      console.error('[settings] failed to update:', err);
      return false;
    }
  }
}
