import { Injectable } from '@angular/core';

/**
 * Client for the per-user `user_settings` API (scope-keyed JSONB documents).
 * Scope 'general' holds the portal's basic preferences (theme, language);
 * other scopes are reserved for future portal-owned screens per module.
 */
@Injectable({ providedIn: 'root' })
export class UserSettingsService {
  async get(scope: string): Promise<Record<string, unknown>> {
    try {
      const res = await fetch(`/api/user-settings/${encodeURIComponent(scope)}`);
      if (!res.ok) return {};
      const data = await res.json() as { settings: Record<string, unknown> };
      return data.settings ?? {};
    } catch (err) {
      console.error(`[user-settings] failed to load scope "${scope}":`, err);
      return {};
    }
  }

  async getAll(): Promise<Record<string, Record<string, unknown>>> {
    try {
      const res = await fetch('/api/user-settings');
      if (!res.ok) return {};
      const data = await res.json() as { settings: Record<string, Record<string, unknown>> };
      return data.settings ?? {};
    } catch (err) {
      console.error('[user-settings] failed to load all scopes:', err);
      return {};
    }
  }

  /** Atomic merge on the server; returns the merged document. */
  async update(scope: string, partial: Record<string, unknown>): Promise<Record<string, unknown>> {
    try {
      const res = await fetch(`/api/user-settings/${encodeURIComponent(scope)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(partial),
      });
      if (!res.ok) {
        console.error(`[user-settings] update scope "${scope}" failed:`, res.status, await res.text());
        return {};
      }
      const data = await res.json() as { settings: Record<string, unknown> };
      return data.settings ?? {};
    } catch (err) {
      console.error(`[user-settings] failed to update scope "${scope}":`, err);
      return {};
    }
  }
}
