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
  { value: 'carris-light', label: 'Carris Light (Yellow + Blue)' },
  { value: 'carris-dark', label: 'Carris Dark (Yellow + Gold)' },
];

const STORAGE_KEY = 'portal-theme';
export const DEFAULT_THEME = 'dark-slate';

/** Tenant theme policy served by instance settings (present = config-owned). */
export interface ThemePolicy {
  defaultTheme?: string | null;
  enabledThemes?: string[] | null;
}

/**
 * Theme state. localStorage is the instant-paint cache; the DB (user_settings
 * scope 'general') is authoritative on next boot — precedence: DB > localStorage > default.
 * DB writes are fire-and-forget: localStorage is already updated, and the
 * DB value is corrected on the user's next explicit change.
 *
 * A tenant policy (defaultTheme/enabledThemes from instance settings) narrows the
 * selectable set and provides the tenant default; it is applied at shell boot before
 * DB-preference correction. Values outside the policy fall back to the tenant default.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly userSettings = inject(UserSettingsService);

  readonly theme = signal<string>(this.restore());
  readonly available = signal<ThemeOption[]>(PORTAL_THEMES);
  readonly defaultTheme = signal<string>(DEFAULT_THEME);

  constructor() {
    this.apply(this.theme());
  }

  /**
   * Applies the tenant theme policy and re-validates the current selection against the
   * narrowed set. Unknown policy values are ignored (kept permissive); an enabled set that
   * intersects to nothing falls back to the full catalog.
   */
  setPolicy(policy: ThemePolicy | null | undefined): void {
    let list = PORTAL_THEMES;
    const enabled = policy?.enabledThemes;
    if (Array.isArray(enabled) && enabled.length > 0) {
      const allowed = new Set(enabled);
      const filtered = PORTAL_THEMES.filter((t) => allowed.has(t.value));
      if (filtered.length > 0) {
        list = filtered;
      } else {
        console.warn('[theme] enabledThemes matches no known theme — keeping full catalog');
      }
    }
    this.available.set(list);
    const requested = policy?.defaultTheme;
    const fallback =
      requested && list.some((t) => t.value === requested)
        ? requested
        : list.some((t) => t.value === DEFAULT_THEME)
          ? DEFAULT_THEME
          : list[0]!.value;
    this.defaultTheme.set(fallback);
    if (!list.some((t) => t.value === this.theme())) {
      this.setTheme(fallback);
    }
  }

  /**
   * Boot-time correction from `/api/config` preferences (WP7 shell wiring).
   * Applies the DB preference without persisting it back (DB already has it).
   */
  initFromPreferences(prefs: UserPreferences): void {
    if (!prefs.theme || !this.available().some((t) => t.value === prefs.theme)) return;
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
    if (!this.available().some((t) => t.value === value)) return;
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
