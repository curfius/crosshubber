import { Injectable, inject, signal } from '@angular/core';
import type { PortalUser } from '../models';
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
      const res = await fetch('/api/i18n/config');
      if (!res.ok) throw new Error(`config fetch failed (${res.status})`);
      this.config.set((await res.json()) as I18nPortalConfig);
      this.loaded.set(true);
      this.loadError.set(false);
    } catch (err) {
      console.error('[i18n-admin] failed to load config:', err);
      this.loadError.set(true);
    }
  }

  async updateSettings(patch: {
    defaultLanguage?: string;
    fallbackLanguage?: string;
    overrides?: I18nOverrides;
  }): Promise<boolean> {
    if (!this.canEdit() || this.saving()) return false;
    this.saving.set(true);
    try {
      const res = await fetch('/api/i18n/settings', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(patch),
      });
      if (!res.ok) {
        const body = (await res.json().catch(() => ({}))) as { error?: string };
        console.error('[i18n-admin] settings update rejected:', body.error ?? res.status);
        return false;
      }
      this.config.set((await res.json()) as I18nPortalConfig);
      await this.runtime.reload();
      return true;
    } catch (err) {
      console.error('[i18n-admin] settings update failed:', err);
      return false;
    } finally {
      this.saving.set(false);
    }
  }

  async updateLanguage(code: string, patch: Partial<Pick<I18nLanguage, 'enabled' | 'name' | 'nativeName' | 'sortOrder'>>): Promise<boolean> {
    if (!this.canEdit() || this.saving()) return false;
    this.saving.set(true);
    try {
      const res = await fetch(`/api/i18n/languages/${encodeURIComponent(code)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(patch),
      });
      if (!res.ok) {
        const body = (await res.json().catch(() => ({}))) as { error?: string };
        console.error('[i18n-admin] language update rejected:', body.error ?? res.status);
        return false;
      }
      await this.load();
      await this.runtime.reload();
      return true;
    } catch (err) {
      console.error('[i18n-admin] language update failed:', err);
      return false;
    } finally {
      this.saving.set(false);
    }
  }

  async saveLabels(code: string, entries: Array<{ key: string; value: string }>): Promise<boolean> {
    if (!this.canEdit() || this.saving() || entries.length === 0) return false;
    this.saving.set(true);
    try {
      const res = await fetch(`/api/i18n/labels/${encodeURIComponent(code)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ entries }),
      });
      if (!res.ok) {
        const body = (await res.json().catch(() => ({}))) as { error?: string };
        console.error('[i18n-admin] labels update rejected:', body.error ?? res.status);
        return false;
      }
      await this.load();
      await this.runtime.reload();
      return true;
    } catch (err) {
      console.error('[i18n-admin] labels update failed:', err);
      return false;
    } finally {
      this.saving.set(false);
    }
  }

  async loadLabels(code: string): Promise<Record<string, string> | null> {
    try {
      const res = await fetch(`/api/i18n/labels/${encodeURIComponent(code)}`);
      if (!res.ok) return null;
      const data = (await res.json()) as { labels: Record<string, string> };
      return data.labels;
    } catch (err) {
      console.error(`[i18n-admin] failed to load labels for "${code}":`, err);
      return null;
    }
  }
}
