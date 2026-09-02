import { Injectable, inject } from '@angular/core';
import { Bridge } from '../../core/bridge/bridge.service';
import { UrlSyncService, type NavRead } from '../../core/history/url-sync.service';
import { tabKeyOf } from '../../core/history/nav-state';
import { entryPointId, type Tab } from '../../core/models';
import { WorkbenchService } from './workspaces/workspaces.store';

interface TabLocation {
  groupId: string;
  tab: Tab;
}

/**
 * Navigation policy layer (D3). Owns the behaviour decision table for URL
 * <-> workbench <-> module synchronization; the Bridge is transport + security
 * only and the Workbench is state only.
 *
 * Behaviour rules (D4):
 *   - tab click / workspace switch / focused-module navigate -> URL push
 *   - structural mutations (close, split, drop, save, rename) -> URL replace
 *   - background-tab module navigate -> in-memory tabPaths only
 *   - popstate -> apply the state the browser already switched to
 *   - cold load -> normalize the URL (structured history.state) with replace
 *
 * Deep links (D5): an `app` param naming a known but closed entry point is
 * auto-opened only on cold load; unknown keys are warned about and ignored.
 *
 * Echo dedupe (D8): a module-reported path equal to the stored tabPaths entry
 * is dropped, preventing module -> URL -> restore -> module loops.
 */
@Injectable({ providedIn: 'root' })
export class NavigationCoordinator {
  private readonly urlSync = inject(UrlSyncService);
  private readonly wb = inject(WorkbenchService);
  private readonly bridge = inject(Bridge);

  /** moduleKey -> module path awaiting the module's mount (restore-before-ready, D11). */
  private readonly pendingPaths = new Map<string, string>();
  private readonly restoreHandlers = new Map<string, Set<(path: string) => void>>();

  constructor() {
    this.urlSync.onPopState((read) => void this.applyState(read, { cold: false }));
    this.bridge.moduleNavigate.subscribe(({ moduleKey, path }) => this.navigateFromModule(moduleKey, path));
  }

  /**
   * Applies a NavState to the workbench. `cold` marks the initial page load:
   * enables deep-link auto-open and URL normalization; suppression keeps the
   * intermediate workbench operations from writing history entries.
   */
  async applyState(read: NavRead, opts: { cold: boolean }): Promise<void> {
    this.wb.replaceAllTabPaths(read.paths);
    const nav = read.nav;
    await this.wb.withUrlSyncSuppressedAsync(async () => {
      await this.applyWorkspace(nav.ws, opts.cold);
      if (nav.app) this.applyApp(nav.app, nav.path, opts.cold);
    });
    if (opts.cold) this.wb.syncUrl('replace');
  }

  /**
   * Module-internal navigation. Called directly by embedded modules (they run
   * in-document) and indirectly by iframe/MFE modules through the Bridge.
   */
  navigateFromModule(moduleKey: string, path: string): void {
    const found = this.findActiveTabOfModule(moduleKey);
    if (!found) return;
    const key = tabKeyOf(entryPointId(found.tab.entryPoint), found.tab.instance);
    if (this.wb.getTabPath(key) === path) return; // echo dedupe (D8)
    this.wb.setTabPath(key, path);
    const group = this.wb.groups()[found.groupId];
    const isActive = found.groupId === this.wb.focusedGroupId() && found.tab.id === group?.activeId;
    if (isActive) this.wb.syncUrl('push');
    // Background tab: tabPaths only — surfaces in the URL when the tab activates.
  }

  /**
   * Registers a restore handler for an embedded module (runs in-document).
   * Any pending path that arrived before the module mounted is replayed.
   * Returns an unsubscribe function.
   */
  onRestore(moduleKey: string, handler: (path: string) => void): () => void {
    let set = this.restoreHandlers.get(moduleKey);
    if (!set) {
      set = new Set();
      this.restoreHandlers.set(moduleKey, set);
    }
    set.add(handler);
    const pending = this.pendingPaths.get(moduleKey);
    if (pending != null) {
      this.pendingPaths.delete(moduleKey);
      setTimeout(() => handler(pending), 0);
    }
    return () => set.delete(handler);
  }

  /**
   * Consumes the pending module path for a module, if any. Used by
   * module-outlet at render time and replayed to embedded modules that
   * register via onRestore, so restores arriving before a module mounts are
   * not lost (D11).
   */
  consumePendingPath(moduleKey: string): string | null {
    const path = this.pendingPaths.get(moduleKey);
    if (path != null) this.pendingPaths.delete(moduleKey);
    return path ?? null;
  }

  private async applyWorkspace(ws: string | null, cold: boolean): Promise<void> {
    if (ws === this.wb.activeWorkspace()) return;
    if (ws) {
      await this.wb.loadWorkspace(ws, false, false);
      if (this.wb.activeWorkspace() !== ws && cold) {
        await this.wb.restore();
      }
      // popstate with an unknown workspace name: keep the current view — the
      // browser has already set the URL it refers to.
    } else if (cold) {
      await this.wb.restore();
    } else {
      this.wb.openHomeWorkspace();
    }
  }

  private applyApp(app: string, path: string | null, cold: boolean): void {
    const found = this.wb.findByTabKey(app);
    if (found) {
      this.activateTab(found, path);
      return;
    }
    if (!cold) return; // deep-link auto-open only on cold load (D5)
    const ep = this.wb.findEntryPointByAppKey(app);
    if (!ep || ep.type === 'link') {
      console.warn(`[nav] unknown app "${app}" in URL — ignored`);
      return;
    }
    this.wb.openApp(ep);
    const opened = this.wb.findByTabKey(app) ?? this.wb.findByTabKey(entryPointId(ep));
    if (opened) this.activateTab(opened, path);
  }

  private activateTab(found: TabLocation, path: string | null): void {
    this.wb.activate(found.groupId, found.tab.id, false);
    if (path) {
      this.wb.setTabPath(tabKeyOf(entryPointId(found.tab.entryPoint), found.tab.instance), path);
      this.dispatchRestore(found.tab, path);
    }
  }

  private dispatchRestore(tab: Tab, path: string): void {
    const moduleKey = tab.entryPoint.moduleKey;
    let delivered = this.bridge.restoreModule(moduleKey, path);
    const handlers = this.restoreHandlers.get(moduleKey);
    if (handlers && handlers.size > 0) {
      for (const h of handlers) h(path);
      delivered = true;
    }
    if (delivered) {
      this.pendingPaths.delete(moduleKey);
    } else {
      this.pendingPaths.set(moduleKey, path);
    }
  }

  private findActiveTabOfModule(moduleKey: string): TabLocation | null {
    const groups = this.wb.groups();
    const focused = this.wb.focusedGroupId();
    // 1. focused group's active tab
    if (focused) {
      const g = groups[focused];
      const tab = g?.tabs.find((t) => t.id === g.activeId && t.entryPoint.moduleKey === moduleKey);
      if (tab) return { groupId: focused, tab };
    }
    // 2. focused group's other tabs (keep-alive: background tabs stay mounted)
    if (focused && groups[focused]) {
      const tab = groups[focused].tabs.find((t) => t.entryPoint.moduleKey === moduleKey);
      if (tab) return { groupId: focused, tab };
    }
    // 3. any group's active tab, then any tab at all
    for (const [gid, g] of Object.entries(groups)) {
      const tab = g.tabs.find((t) => t.id === g.activeId && t.entryPoint.moduleKey === moduleKey);
      if (tab) return { groupId: gid, tab };
    }
    for (const [gid, g] of Object.entries(groups)) {
      const tab = g.tabs.find((t) => t.entryPoint.moduleKey === moduleKey);
      if (tab) return { groupId: gid, tab };
    }
    return null;
  }
}
