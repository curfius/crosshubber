import { Injectable } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class ModuleSettingsService {
  async get(moduleKey: string): Promise<Record<string, unknown>> {
    try {
      const res = await fetch(`/api/module-settings/${encodeURIComponent(moduleKey)}`);
      if (!res.ok) return {};
      const data = await res.json() as { settings: Record<string, unknown> };
      return data.settings ?? {};
    } catch (err) {
      console.error(`[module-settings] failed to load ${moduleKey}:`, err);
      return {};
    }
  }

  async update(moduleKey: string, partial: Record<string, unknown>): Promise<Record<string, unknown>> {
    try {
      const res = await fetch(`/api/module-settings/${encodeURIComponent(moduleKey)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(partial),
      });
      if (!res.ok) {
        console.error(`[module-settings] update ${moduleKey} failed:`, res.status, await res.text());
        return {};
      }
      const data = await res.json() as { settings: Record<string, unknown> };
      return data.settings ?? {};
    } catch (err) {
      console.error(`[module-settings] failed to update ${moduleKey}:`, err);
      return {};
    }
  }
}
