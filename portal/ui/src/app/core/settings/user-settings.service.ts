import { Injectable } from '@angular/core';
import { apiFetch } from '../http/api-fetch';
import { ScopedSettingsClient } from './scoped-settings-client';

/**
 * Client for the per-user `user_settings` API (scope-keyed JSONB documents).
 * Scope 'general' holds the portal's basic preferences (theme, language);
 * other scopes are reserved for future portal-owned screens per module.
 */
@Injectable({ providedIn: 'root' })
export class UserSettingsService extends ScopedSettingsClient {
  protected readonly logTag = 'user-settings';
  protected readonly urlPrefix = '/api/user-settings';

  async getAll(): Promise<Record<string, Record<string, unknown>>> {
    try {
      const res = await apiFetch('/api/user-settings');
      const data = (await res.json()) as { settings: Record<string, Record<string, unknown>> };
      return data.settings ?? {};
    } catch (err) {
      console.error('[user-settings] failed to load all scopes:', err);
      return {};
    }
  }
}
