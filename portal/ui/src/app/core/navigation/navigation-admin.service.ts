import { Injectable, inject, signal } from '@angular/core';
import type { PortalUser } from '../models';
import type { NavigationLayout, ShellTreePayload, ShellTreeResponse, NavigationFeatures } from './navigation.models';
import type { NavigationStore } from './navigation.store';

export type ShellCategory = 'settings' | 'user-settings';

/**
 * Admin CRUD for navigation configuration (role `portal-navigation-edit`):
 * shell nav trees, Portal Navigation layout and the feature switches.
 */
@Injectable({ providedIn: 'root' })
export class NavigationAdminService {
  readonly canEdit = signal(false);
  readonly saving = signal(false);

  init(user: PortalUser): void {
    this.canEdit.set(user.roles.includes('portal-navigation-edit'));
  }

  private async send<T>(url: string, method: string, body?: unknown): Promise<{ ok: boolean; data?: T; error?: string }> {
    if (!this.canEdit() || this.saving()) return { ok: false, error: 'not-allowed' };
    this.saving.set(true);
    try {
      const res = await fetch(url, {
        method,
        headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
        body: body !== undefined ? JSON.stringify(body) : undefined,
      });
      if (!res.ok) {
        const errBody = (await res.json().catch(() => ({}))) as { error?: string };
        return { ok: false, error: errBody.error ?? `HTTP ${res.status}` };
      }
      return { ok: true, data: (await res.json()) as T };
    } catch (err) {
      return { ok: false, error: (err as Error).message };
    } finally {
      this.saving.set(false);
    }
  }

  async loadShellTree(category: ShellCategory): Promise<ShellTreeResponse | null> {
    try {
      const res = await fetch(`/api/navigation/shell-tree?category=${category}`);
      if (!res.ok) return null;
      return (await res.json()) as ShellTreeResponse;
    } catch {
      return null;
    }
  }

  async saveShellTree(category: ShellCategory, payload: ShellTreePayload): Promise<{ ok: boolean; error?: string }> {
    const result = await this.send<ShellTreeResponse>(
      `/api/navigation/shell-tree/${category}`, 'PUT', payload,
    );
    return { ok: result.ok, error: result.error };
  }

  async loadLayout(): Promise<NavigationLayout | null> {
    try {
      const res = await fetch('/api/navigation/layout');
      if (!res.ok) return null;
      return ((await res.json()) as { layout: NavigationLayout }).layout;
    } catch {
      return null;
    }
  }

  async saveLayout(layout: NavigationLayout): Promise<{ ok: boolean; error?: string }> {
    const result = await this.send<{ layout: NavigationLayout }>('/api/navigation/layout', 'PUT', layout);
    return { ok: result.ok, error: result.error };
  }

  async saveFeatures(partial: Partial<NavigationFeatures>, store?: NavigationStore): Promise<boolean> {
    const result = await this.send<NavigationFeatures>('/api/navigation/features', 'PUT', partial);
    if (result.ok && result.data && store) {
      store.features.set(result.data);
    }
    return result.ok;
  }
}
