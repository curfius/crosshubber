import { Injectable, signal } from '@angular/core';

import { DEFAULT_LOCALE, UiI18n } from './i18n';

/**
 * MFE-side implementation of the shared kit's i18n contract.
 *
 * The module backend has no label store of its own, so this service renders
 * labels from the portal's public, same-origin endpoints
 * (`GET /api/i18n/config`, `GET /api/i18n/labels/<code>`) — the same bundles
 * the portal shell serves, cached in localStorage keyed by the server-side
 * `contentVersion`.
 *
 * Language plumbing follows the portal→module contract:
 *   initial  — element `mount({ language })` context (call setContextLanguage)
 *   changes  — `portal:language` bridge event (composed CustomEvent, reaches window)
 *
 * Lookup order per key: active → fallback → default → raw key.
 */
@Injectable({ providedIn: 'root' })
export class MfeI18n implements UiI18n {
  private static readonly CACHE_PREFIX = 'mfe.i18n.labels.';

  private readonly bundles = signal<Record<string, Record<string, string>>>({});
  private readonly active = signal<string | null>(null);
  private config: MfeI18nConfig | null = null;
  private initPromise: Promise<void> | null = null;

  constructor() {
    window.addEventListener('portal:language', (event) => {
      const detail = (event as CustomEvent<{ language?: string }>).detail;
      const code = detail?.language;
      if (code) {
        this.setLanguage(code);
      }
    });
  }

  /** Called from the element mount() hook with the context language. */
  setContextLanguage(code: string): void {
    this.initPromise ??= this.doInit(code);
  }

  /** BCP-47 tag used for `Intl.*` and formatting. */
  locale(): string {
    return this.active() ?? DEFAULT_LOCALE;
  }

  /** Translate a key with `{placeholder}` interpolation. Reactive in templates. */
  t(key: string, params?: Record<string, string | number>): string {
    const template = this.labels()[key] ?? key;
    if (!params) return template;
    return template.replace(/\{(\w+)\}/g, (match, name: string) =>
      Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : match,
    );
  }

  formatDate(value: string | number | Date, options?: Intl.DateTimeFormatOptions): string {
    const opts: Intl.DateTimeFormatOptions = { ...options };
    const tz = this.config?.overrides?.timezone;
    if (tz) opts.timeZone = tz;
    const date = typeof value === 'string' ? new Date(value) : value;
    return new Intl.DateTimeFormat(this.locale(), opts).format(date);
  }

  formatNumber(value: number, options?: Intl.NumberFormatOptions): string {
    return new Intl.NumberFormat(this.locale(), options).format(value);
  }

  private setLanguage(code: string): void {
    this.active.set(code);
    void this.ensureBundle(code);
  }

  /** Merged label map: default ← fallback ← active. */
  private labels(): Record<string, string> {
    const cfg = this.config;
    const bundles = this.bundles();
    const merged: Record<string, string> = {};
    for (const code of [cfg?.defaultLanguage, cfg?.fallbackLanguage, this.active()]) {
      if (code) Object.assign(merged, bundles[code] ?? {});
    }
    return merged;
  }

  private async doInit(initialCode: string): Promise<void> {
    try {
      const res = await fetch('/api/i18n/config');
      if (!res.ok) throw new Error(`i18n config fetch failed (${res.status})`);
      this.config = (await res.json()) as MfeI18nConfig;
      const needed = new Set<string>([this.config.defaultLanguage, this.config.fallbackLanguage]);
      if (initialCode) needed.add(initialCode);
      for (const code of needed) {
        await this.ensureBundle(code);
      }
    } catch (err) {
      console.error('[mfe-i18n] initialization failed:', err);
    }
    this.active.set(initialCode || this.config?.defaultLanguage || DEFAULT_LOCALE);
  }

  private async ensureBundle(code: string): Promise<void> {
    if (this.bundles()[code]) return;
    const labels = await this.loadBundle(code);
    if (labels && Object.keys(labels).length) {
      this.bundles.update((b) => ({ ...b, [code]: labels }));
    }
  }

  private async loadBundle(code: string): Promise<Record<string, string> | null> {
    const version = this.config?.contentVersion;
    try {
      const cached = localStorage.getItem(MfeI18n.CACHE_PREFIX + code);
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
        localStorage.setItem(
          MfeI18n.CACHE_PREFIX + code,
          JSON.stringify({ version, labels: data.labels }),
        );
      } catch {
        // storage unavailable
      }
      return data.labels;
    } catch (err) {
      console.error(`[mfe-i18n] failed to load bundle "${code}":`, err);
      return null;
    }
  }
}

interface MfeI18nConfig {
  defaultLanguage: string;
  fallbackLanguage: string;
  overrides?: { timezone?: string };
  contentVersion: number;
}
