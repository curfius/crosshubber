import { Injectable, computed, inject, signal } from '@angular/core';
import type { PinnedNode, NavigationUserSettings, NavigationFeatures } from './navigation.models';
import { collectPinnedRefs } from './navigation.models';

/**
 * Navigation state for the shell: pinned apps tree, per-user sidebar config
 * and instance feature switches. Loaded by the Shell at boot; per-user
 * mutations autosave (D10).
 */
@Injectable({ providedIn: 'root' })
export class NavigationStore {
  readonly pinnedTree = signal<PinnedNode[]>([]);
  readonly userSettings = signal<NavigationUserSettings | null>(null);
  readonly features = signal<NavigationFeatures>({ pinnedAppsEnabled: true, workspacesEnabled: true });
  readonly loaded = signal(false);

  /** Flat set of pinned item refs (root-first document order). */
  readonly pinnedRefs = computed(() => collectPinnedRefs(this.pinnedTree()));

  readonly sidebar = computed<NavigationUserSettings['sidebar']>(() =>
    this.userSettings()?.sidebar ?? { showPinned: true, showWorkspaces: true, apps: [] },
  );

  isPinned(ref: string): boolean {
    return this.pinnedRefs().includes(ref);
  }

  async load(): Promise<void> {
    await Promise.all([this.loadPinned(), this.loadUserSettings(), this.loadFeatures()]);
    this.loaded.set(true);
  }

  async loadPinned(): Promise<void> {
    try {
      const res = await fetch('/api/navigation/pinned-apps');
      if (!res.ok) return;
      const { tree } = (await res.json()) as { tree: PinnedNode[] };
      this.pinnedTree.set(tree ?? []);
    } catch {
      // offline / transient — keep current state
    }
  }

  async loadUserSettings(): Promise<void> {
    try {
      const res = await fetch('/api/navigation/user-settings');
      if (!res.ok) return;
      const { settings } = (await res.json()) as { settings: NavigationUserSettings };
      this.userSettings.set(settings);
    } catch {
      // ignore
    }
  }

  async loadFeatures(): Promise<void> {
    try {
      const res = await fetch('/api/navigation/features');
      if (!res.ok) return;
      this.features.set((await res.json()) as NavigationFeatures);
    } catch {
      // ignore
    }
  }

  /** Star-toggle from the dashboard (autosave, idempotent server-side). */
  async pin(ref: string): Promise<void> {
    if (this.isPinned(ref)) return;
    this.pinnedTree.update((tree) => [
      ...tree,
      { id: `pin-${ref}`, nodeType: 'item', ref, children: [] },
    ]);
    try {
      await fetch('/api/navigation/pinned-apps', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ref }),
      });
      await this.loadPinned();
    } catch {
      // ignore
    }
  }

  async unpin(ref: string): Promise<void> {
    this.pinnedTree.update((tree) =>
      tree.filter((n) => !(n.nodeType === 'item' && n.ref === ref)),
    );
    try {
      await fetch(`/api/navigation/pinned-apps/items/${encodeURIComponent(ref)}`, { method: 'DELETE' });
      await this.loadPinned();
    } catch {
      // ignore
    }
  }

  async togglePin(ref: string): Promise<void> {
    if (!this.features().pinnedAppsEnabled) return;
    if (this.isPinned(ref)) await this.unpin(ref);
    else await this.pin(ref);
  }

  /** Full-tree save (pinned editor); server returns canonical ids. */
  async saveTree(tree: PinnedNode[]): Promise<boolean> {
    try {
      const res = await fetch('/api/navigation/pinned-apps', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(tree),
      });
      if (!res.ok) return false;
      const { tree: saved } = (await res.json()) as { tree: PinnedNode[] };
      this.pinnedTree.set(saved);
      return true;
    } catch {
      return false;
    }
  }

  /** Partial sidebar config update (shallow-merged server-side). */
  async saveSidebar(partial: Partial<NavigationUserSettings['sidebar']>): Promise<boolean> {
    const next: NavigationUserSettings['sidebar'] = { ...this.sidebar(), ...partial };
    this.userSettings.update((s) => ({
      sidebar: next,
      sidebarExpanded: s?.sidebarExpanded ?? [],
    }));
    try {
      const res = await fetch('/api/navigation/user-settings', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sidebar: partial }),
      });
      if (!res.ok) return false;
      const { settings } = (await res.json()) as { settings: NavigationUserSettings };
      this.userSettings.set(settings);
      return true;
    } catch {
      return false;
    }
  }

  async saveExpanded(ids: string[]): Promise<void> {
    this.userSettings.update((s) => ({
      sidebar: s?.sidebar ?? { showPinned: true, showWorkspaces: true, apps: [] },
      sidebarExpanded: ids,
    }));
    try {
      await fetch('/api/navigation/user-settings', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sidebarExpanded: ids }),
      });
    } catch {
      // ignore
    }
  }

  /** Admin feature-switch write (portal-nav-settings editor). */
  async saveFeatures(partial: Partial<NavigationFeatures>): Promise<boolean> {
    this.features.update((f) => ({ ...f, ...partial }));
    try {
      const res = await fetch('/api/navigation/features', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(partial),
      });
      if (!res.ok) {
        await this.loadFeatures();
        return false;
      }
      this.features.set((await res.json()) as NavigationFeatures);
      return true;
    } catch {
      return false;
    }
  }
}
