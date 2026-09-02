import { Injectable, computed, inject, signal } from '@angular/core';
import { Bridge } from '../bridge/bridge.service';
import { UserSettingsService } from '../settings/user-settings.service';
import type { UserPreferences } from '../models';

export interface I18nLanguage {
  code: string;
  name: string;
  nativeName: string;
  enabled: boolean;
  seeded: boolean;
  sortOrder: number;
}

export interface I18nOverrides {
  timezone?: string;
  firstDayOfWeek?: number;
}

export interface I18nPortalConfig {
  languages: I18nLanguage[];
  defaultLanguage: string;
  fallbackLanguage: string;
  overrides: I18nOverrides;
  contentVersion: number;
}

const STORAGE_KEY = 'portal-language';
const CACHE_PREFIX = 'portal.i18n.labels.';
const FALLBACK_LOCALE = 'en-GB';

/**
 * Runtime i18n for the portal shell and builtin embedded modules.
 *
 * Bundles come from the public `/api/i18n/*` endpoints (DB-backed, edited in
 * the i18n-settings module) and are cached in localStorage keyed by the
 * server-side `contentVersion` — a bump invalidates every client cache.
 *
 * Lookup order per key: active language → fallback language → default
 * language → raw key (a visible signal that a translation is missing).
 *
 * External modules are told the active language via `?lang=` on the iframe
 * URL / MFE mount context (initial) and `portal:language` bridge messages
 * (on change) — see Bridge.broadcastLanguage.
 */
@Injectable({ providedIn: 'root' })
export class I18nService {
  private readonly bridge = inject(Bridge);
  private readonly userSettings = inject(UserSettingsService);

  readonly config = signal<I18nPortalConfig | null>(null);
  readonly active = signal<string | null>(null);
  readonly initialized = signal(false);

  private readonly bundles = signal<Record<string, Record<string, string>>>({});
  private initPromise: Promise<void> | null = null;

  /** Languages available in the switcher (enabled only, sorted). */
  readonly enabledLanguages = computed<I18nLanguage[]>(() =>
    (this.config()?.languages ?? [])
      .filter((l) => l.enabled)
      .sort((a, b) => a.sortOrder - b.sortOrder || a.code.localeCompare(b.code)),
  );

  /** BCP-47 tag used for `Intl.*` and `<html lang>`. */
  readonly locale = computed<string>(() => this.active() ?? FALLBACK_LOCALE);

  /** Merged label map: default ← fallback ← active. */
  private readonly labels = computed<Record<string, string>>(() => {
    const cfg = this.config();
    const bundles = this.bundles();
    const merged: Record<string, string> = {};
    for (const code of [cfg?.defaultLanguage, cfg?.fallbackLanguage, this.active()]) {
      if (code) Object.assign(merged, bundles[code] ?? {});
    }
    return merged;
  });

  constructor() {
    // Modules loaded after a language change get the current language on ready.
    this.bridge.ready.add(() => this.bridge.broadcastLanguage(this.locale()));
  }

  /** Fetches config + bundles and resolves the initial language. Idempotent. */
  init(): Promise<void> {
    this.initPromise ??= this.doInit();
    return this.initPromise;
  }

  /**
   * Re-fetches config and bundles after an i18n-settings edit. Also used by
   * the admin screens to refresh the running shell.
   */
  async reload(): Promise<void> {
    this.initPromise = null;
    this.initialized.set(false);
    this.bundles.set({});
    await this.init();
  }

  /** Translate a key with `{placeholder}` interpolation. Reactive in templates. */
  t(key: string, params?: Record<string, string | number>): string {
    const template = this.labels()[key] ?? key;
    if (!params) return template;
    return template.replace(/\{(\w+)\}/g, (m, name: string) =>
      Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : m,
    );
  }

  /**
   * Boot-time correction from `/api/config` preferences (DB > localStorage >
   * browser > default). Applies the DB language without persisting it back.
   */
  applyServerPreference(prefs: UserPreferences): void {
    if (!prefs.language) return;
    if (!this.enabledLanguages().some((l) => l.code === prefs.language)) return;
    if (prefs.language === this.active()) return;
    this.setLanguage(prefs.language, { persist: false });
  }

  setLanguage(code: string, opts: { persist?: boolean } = {}): void {
    if (!this.enabledLanguages().some((l) => l.code === code)) return;
    this.active.set(code);
    try {
      localStorage.setItem(STORAGE_KEY, code);
    } catch {
      // storage unavailable — still applies for the session
    }
    this.apply(code);
    void this.ensureBundle(code);
    this.bridge.broadcastLanguage(code);
    if (opts.persist !== false) {
      void this.userSettings.update('general', { language: code });
    }
  }

  // ── Locale-aware formatting (Intl + admin overrides) ───────────────────

  formatDate(value: string | number | Date, options?: Intl.DateTimeFormatOptions): string {
    const opts: Intl.DateTimeFormatOptions = { ...options };
    const tz = this.config()?.overrides.timezone;
    if (tz) opts.timeZone = tz;
    const date = typeof value === 'string' ? new Date(value) : value;
    return new Intl.DateTimeFormat(this.locale(), opts).format(date);
  }

  formatNumber(value: number, options?: Intl.NumberFormatOptions): string {
    return new Intl.NumberFormat(this.locale(), options).format(value);
  }

  formatCurrency(value: number, currency: string): string {
    return new Intl.NumberFormat(this.locale(), { style: 'currency', currency }).format(value);
  }

  /** Admin override for week-start-aware UIs (0 = Sunday … 6 = Saturday). */
  firstDayOfWeek(): number | undefined {
    return this.config()?.overrides.firstDayOfWeek;
  }

  // ── Internals ──────────────────────────────────────────────────────────

  private async doInit(): Promise<void> {
    try {
      const res = await fetch('/api/i18n/config');
      if (!res.ok) throw new Error(`config fetch failed (${res.status})`);
      const cfg = (await res.json()) as I18nPortalConfig;
      this.config.set(cfg);
      const lang = this.detectLanguage(cfg);
      this.active.set(lang);
      this.apply(lang);
      const needed = new Set([cfg.defaultLanguage, cfg.fallbackLanguage, lang]);
      await Promise.all([...needed].map((code) => this.ensureBundle(code)));
      this.initialized.set(true);
    } catch (err) {
      console.error('[i18n] initialization failed:', err);
    }
  }

  /** Explicit preference → browser locale (exact, then base subtag) → default. */
  private detectLanguage(cfg: I18nPortalConfig): string {
    const enabled = cfg.languages.filter((l) => l.enabled);
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored && enabled.some((l) => l.code === stored)) return stored;
    } catch {
      // ignore
    }
    const prefs: readonly string[] =
      Array.isArray(navigator.languages) && navigator.languages.length
        ? navigator.languages
        : [navigator.language];
    for (const pref of prefs) {
      if (!pref) continue;
      const lower = pref.toLowerCase();
      const exact = enabled.find((l) => l.code.toLowerCase() === lower);
      if (exact) return exact.code;
      const base = lower.split('-')[0];
      const partial = enabled.find((l) => l.code.toLowerCase().split('-')[0] === base);
      if (partial) return partial.code;
    }
    return cfg.defaultLanguage;
  }

  private async ensureBundle(code: string): Promise<void> {
    if (this.bundles()[code]) return;
    const labels = await this.loadBundle(code);
    if (labels && Object.keys(labels).length) {
      this.bundles.update((b) => ({ ...b, [code]: labels }));
    }
  }

  private async loadBundle(code: string): Promise<Record<string, string> | null> {
    const version = this.config()?.contentVersion;
    try {
      const cached = localStorage.getItem(CACHE_PREFIX + code);
      if (cached) {
        const parsed = JSON.parse(cached) as { version?: number; labels?: Record<string, string> };
        if (parsed.version === version && parsed.labels) return parsed.labels;
      }
    } catch {
      // corrupted cache — refetch
    }
    try {
      const res = await fetch(`/api/i18n/labels/${encodeURIComponent(code)}`);
      if (!res.ok) return null;
      const data = (await res.json()) as { labels: Record<string, string> };
      try {
        localStorage.setItem(CACHE_PREFIX + code, JSON.stringify({ version, labels: data.labels }));
      } catch {
        // storage unavailable
      }
      return data.labels;
    } catch (err) {
      console.error(`[i18n] failed to load bundle "${code}":`, err);
      return null;
    }
  }

  private apply(code: string): void {
    document.documentElement.setAttribute('lang', code);
  }
}
