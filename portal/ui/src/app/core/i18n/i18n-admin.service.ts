import { Injectable, inject, signal } from '@angular/core';
import type { PortalUser } from '../models';
import { apiFetch, errorBody } from '../http/api-fetch';
import { I18nService, type I18nLanguage, type I18nOverrides, type I18nPortalConfig } from './i18n.service';

export interface I18nLabelEntryDraft {
  key: string;
  value: string;
  dirty: boolean;
}

/**
 * Admin CRUD for languages / labels / i18n settings (i18n-settings module).
 * Runtime translation state lives in I18nService; after every successful
 * write we reload it so the shell picks up changes immediately.
 */
@Injectable({ providedIn: 'root' })
export class I18nAdminService {
  private readonly runtime = inject(I18nService);

  readonly canEdit = signal(false);
  readonly config = signal<I18nPortalConfig | null>(null);
  readonly loaded = signal(false);
  readonly saving = signal(false);
  readonly loadError = signal(false);

  init(user: PortalUser): void {
    this.canEdit.set(user.roles.includes('portal-i18n-edit'));
  }

  async load(): Promise<void> {
    try {
      const res = await apiFetch('/api/i18n/config');
      this.config.set((await res.json()) as I18nPortalConfig);
      this.loaded.set(true);
      this.loadError.set(false);
    } catch (err) {
      console.error('[i18n-admin] failed to load config:', err);
      this.loadError.set(true);
    }
  }

  /**
   * Shared write path for the three admin mutations: auth/saving guard, PUT, error-body
   * logging. `onOk` distinguishes the settings endpoint (returns the fresh config) from
   * the language/label endpoints (config is re-fetched via load()).
   */
  private async mutate(
    url: string,
    patch: unknown,
    what: string,
    onOk: 'applyConfig' | 'reloadConfig',
  ): Promise<boolean> {
    if (!this.canEdit() || this.saving()) return false;
    this.saving.set(true);
    try {
      const res = await fetch(url, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(patch),
      });
      if (!res.ok) {
        console.error(`[i18n-admin] ${what} rejected:`, await errorBody(res));
        return false;
      }
      if (onOk === 'applyConfig') {
        this.config.set((await res.json()) as I18nPortalConfig);
      } else {
        await this.load();
      }
      await this.runtime.reload();
      return true;
    } catch (err) {
      console.error(`[i18n-admin] ${what} failed:`, err);
      return false;
    } finally {
      this.saving.set(false);
    }
  }

  async updateSettings(patch: {
    defaultLanguage?: string;
    fallbackLanguage?: string;
    overrides?: I18nOverrides;
  }): Promise<boolean> {
    return this.mutate('/api/i18n/settings', patch, 'settings update', 'applyConfig');
  }

  async updateLanguage(
    code: string,
    patch: Partial<Pick<I18nLanguage, 'enabled' | 'name' | 'nativeName' | 'sortOrder'>>,
  ): Promise<boolean> {
    return this.mutate(
      `/api/i18n/languages/${encodeURIComponent(code)}`,
      patch,
      'language update',
      'reloadConfig',
    );
  }

  async saveLabels(code: string, entries: Array<{ key: string; value: string }>): Promise<boolean> {
    if (entries.length === 0) return false;
    return this.mutate(
      `/api/i18n/labels/${encodeURIComponent(code)}`,
      { entries },
      'labels update',
      'reloadConfig',
    );
  }

  async loadLabels(code: string): Promise<Record<string, string> | null> {
    try {
      const res = await apiFetch(`/api/i18n/labels/${encodeURIComponent(code)}`);
      const data = (await res.json()) as { labels: Record<string, string> };
      return data.labels;
    } catch (err) {
      console.error(`[i18n-admin] failed to load labels for "${code}":`, err);
      return null;
    }
  }
}
