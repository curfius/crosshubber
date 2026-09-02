import { Injectable, inject, signal } from '@angular/core';
import { UserSettingsService } from '../settings/user-settings.service';
import type { UserPreferences } from '../models';

export interface ThemeOption {
  value: string;
  label: string;
}

export const PORTAL_THEMES: ThemeOption[] = [
  { value: 'dark-slate', label: 'Dark Slate' },
  { value: 'dark-slate-bold', label: 'Dark Slate Bold' },
  { value: 'dark-slate-light', label: 'Dark Slate Light' },
  { value: 'light', label: 'Light' },
  { value: 'midnight-blue', label: 'Midnight Blue' },
  { value: 'forest', label: 'Forest' },
  { value: 'sunset', label: 'Sunset' },
  { value: 'ocean', label: 'Ocean' },
  { value: 'nord', label: 'Nord' },
  { value: 'dracula', label: 'Dracula' },
  { value: 'monochrome', label: 'Monochrome' },
  { value: 'high-contrast', label: 'High Contrast' },
  { value: 'malo-1', label: 'Malo 1 (Purple + Gold)' },
  { value: 'malo-2', label: 'Malo 2 (Teal + Navy)' },
  { value: 'malo-3', label: 'Malo 3 (Purple + Teal)' },
  { value: 'malo-light', label: 'Malo Light (Navy + Teal)' },
  { value: 'malo-dark', label: 'Malo Dark (Navy + Teal)' },
];

const STORAGE_KEY = 'portal-theme';
export const DEFAULT_THEME = 'dark-slate';

/**
 * Theme state. localStorage is the instant-paint cache; the DB (user_settings
 * scope 'general') is authoritative on next boot — precedence: DB > localStorage > default.
 * DB writes are fire-and-forget: localStorage is already updated, and the
 * DB value is corrected on the user's next explicit change.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly userSettings = inject(UserSettingsService);

  readonly theme = signal<string>(this.restore());

  constructor() {
    this.apply(this.theme());
  }

  /**
   * Boot-time correction from `/api/config` preferences (WP7 shell wiring).
   * Applies the DB preference without persisting it back (DB already has it).
   */
  initFromPreferences(prefs: UserPreferences): void {
    if (!prefs.theme || !PORTAL_THEMES.some((t) => t.value === prefs.theme)) return;
    if (prefs.theme === this.theme()) return;
    this.theme.set(prefs.theme);
    this.apply(prefs.theme);
    try {
      localStorage.setItem(STORAGE_KEY, prefs.theme);
    } catch {
      // storage unavailable — theme still applies for session
    }
  }

  setTheme(value: string): void {
    if (!PORTAL_THEMES.some((t) => t.value === value)) return;
    this.theme.set(value);
    this.apply(value);
    try {
      localStorage.setItem(STORAGE_KEY, value);
    } catch {
      // storage unavailable (private mode etc.) — theme still applies for session
    }
    void this.userSettings.update('general', { theme: value });
  }

  private restore(): string {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved && PORTAL_THEMES.some((t) => t.value === saved)) return saved;
    } catch {
      // ignore
    }
    return DEFAULT_THEME;
  }

  private apply(value: string): void {
    document.documentElement.setAttribute('data-theme', value);
  }
}
