import { moveItemInArray, type CdkDragDrop } from '@angular/cdk/drag-drop';
import { Injectable, computed, effect, inject, signal } from '@angular/core';
import type { PortalEntryPoint, Tab, TabGroup, SplitDir, LeafNode, SplitNode, LayoutNode, WorkspaceMeta, SavedGroup, WorkspaceSnapshot, EntryPointGroup } from '../../../core/models';
import { entryPointId, parseEntryPointId } from '../../../core/models';
import { UrlSyncService } from '../../../core/history/url-sync.service';
import { tabKeyOf, type NavState } from '../../../core/history/nav-state';

const ACTIVE_KEY = 'portal.activeWorkspace';
const HOME_SNAPSHOT_KEY = 'portal.homeSnapshot';

/** Default home-app ref (instance setting `homeApp` overrides it). */
const DEFAULT_HOME_REF = 'portal-navigation:portal';
const FALLBACK_HOME_REF = 'portal-dashboard:main';

function withInstances(tabs: Tab[]): Tab[] {
  const counts = new Map<string, number>();
  return tabs.map((t) => {
    const id = entryPointId(t.entryPoint);
    const n = (counts.get(id) ?? 0) + 1;
    counts.set(id, n);
    return { ...t, instance: n };
  });
}

function removeLeaf(node: LayoutNode, groupId: string): LayoutNode | null {
  if (node.kind === 'leaf') return node.groupId === groupId ? null : node;
  const a = removeLeaf(node.a, groupId);
  const b = removeLeaf(node.b, groupId);
  if (a && b) return { ...node, a, b };
  return a ?? b;
}

function firstLeaf(node: LayoutNode): string {
  return node.kind === 'leaf' ? node.groupId : firstLeaf(node.a);
}

@Injectable({ providedIn: 'root' })
export class WorkbenchService {
  private readonly urlSync = inject(UrlSyncService);

  readonly groups = signal<Record<string, TabGroup>>({});
  readonly layout = signal<LayoutNode | null>(null);
  readonly focusedGroupId = signal<string | null>(null);

  readonly workspaces = signal<WorkspaceMeta[]>([]);
  readonly activeWorkspace = signal<string | null>(null);
  readonly dirty = signal(false);
  readonly editMode = signal(false);
  readonly hideSingleTabToolbar = signal(false);
  readonly locked = signal(false);
  readonly description = signal('');
  readonly workspaceColor = signal('');
  readonly workspaceStatus = signal('');

  /** tabKey -> last known module-internal path (session scope; policy in NavigationCoordinator). */
  private tabPaths = new Map<string, string>();
  private suppressSync = false;

  readonly hasTabs = computed(() => Object.values(this.groups()).some((g) => g.tabs.length > 0));
  readonly primaryGroupId = computed(() => {
    const l = this.layout();
    return l ? firstLeaf(l) : null;
  });

  private readonly entryPoints = signal<PortalEntryPoint[]>([]);
  private readonly entryPointGroups = signal<EntryPointGroup[]>([]);
  private nextTabId = 1;
  private nextGroupId = 1;
  private nextSplitId = 1;
  private cachedSnapshot: WorkspaceSnapshot | null = null;

  constructor() {
    window.addEventListener('beforeunload', this.onBeforeUnload);
    effect(() => {
      const active = this.activeWorkspace();
      if (!active) {
        this.dirty.set(true);
        this.editMode.set(true);
        return;
      }
      const snap = this.cachedSnapshot;
      if (!snap || snap.name !== active) {
        this.dirty.set(true);
        return;
      }
      const current = this.serializeCurrent();
      const saved = { layout: snap.layout, groups: snap.groups, focusedGroupId: snap.focusedGroupId };
      this.dirty.set(JSON.stringify(saved) !== JSON.stringify(current));
    });
  }

  setEntryPoints(eps: PortalEntryPoint[]): void {
    this.entryPoints.set(eps);
  }

  getEntryPoints(): PortalEntryPoint[] {
    return this.entryPoints();
  }

  setEntryPointGroups(groups: EntryPointGroup[]): void {
    this.entryPointGroups.set(groups);
  }

  getEntryPointGroups(): EntryPointGroup[] {
    return this.entryPointGroups();
  }

  findEntryPoint(moduleKey: string, entryKey: string): PortalEntryPoint | undefined {
    return this.entryPoints().find((ep) => ep.moduleKey === moduleKey && ep.entryKey === entryKey);
  }

  // ── Home tab (fixture of the home view) ───────────────────────────────

  /** When workspaces are disabled, boot never auto-restores a workspace —
   *  the home view (with its unclosable Home tab) is the only view. */
  private workspacesGate = signal(true);

  setWorkspacesEnabled(enabled: boolean): void {
    this.workspacesGate.set(enabled);
  }

  /** Configured home app ref; resolved entry point + tab id of the fixture. */
  private homeAppRef = signal<string>(DEFAULT_HOME_REF);
  private homeTabId: number | null = null;

  setHomeApp(ref: string | null | undefined): void {
    this.homeAppRef.set(ref && ref.trim() ? ref : DEFAULT_HOME_REF);
  }

  getHomeAppRef(): string {
    return this.homeAppRef();
  }

  isHomeTab(tab: Tab): boolean {
    return tab.id === this.homeTabId;
  }

  private resolveHomeEntryPoint(): PortalEntryPoint | null {
    const resolve = (ref: string): PortalEntryPoint | null => {
      const idx = ref.indexOf(':');
      if (idx <= 0) return null;
      const ep = this.findEntryPoint(ref.slice(0, idx), ref.slice(idx + 1));
      return ep && ep.type !== 'link' ? ep : null;
    };
    return resolve(this.homeAppRef()) ?? resolve(FALLBACK_HOME_REF);
  }

  /** Ensures the unclosable Home tab exists as the first tab of the primary group. */
  ensureHomeTab(): void {
    const ep = this.resolveHomeEntryPoint();
    if (!ep) {
      this.homeTabId = null;
      return;
    }
    if (this.homeTabId != null) {
      const stillThere = Object.values(this.groups()).some((g) => g.tabs.some((t) => t.id === this.homeTabId));
      if (stillThere) {
        // Keep entry point fresh (admin may have changed the home app).
        this.groups.update((gs) => {
          for (const [gid, g] of Object.entries(gs)) {
            const idx = g.tabs.findIndex((t) => t.id === this.homeTabId);
            if (idx >= 0 && entryPointId(g.tabs[idx].entryPoint) !== entryPointId(ep)) {
              return { ...gs, [gid]: { ...g, tabs: withInstances(g.tabs.map((t) => (t.id === this.homeTabId ? { ...t, entryPoint: ep } : t))) } };
            }
          }
          return gs;
        });
        return;
      }
    }
    let gid = this.primaryGroupId();
    if (!gid || !this.groups()[gid]) {
      gid = this.addGroup().id;
    }
    const tab = { id: this.nextTabId++, entryPoint: ep, instance: 0 };
    this.homeTabId = tab.id;
    this.groups.update((gs) => ({
      ...gs,
      [gid]: {
        ...gs[gid],
        tabs: withInstances([tab, ...gs[gid].tabs]),
        activeId: gs[gid].activeId ?? tab.id,
      },
    }));
  }

  /** Sidebar home click: leave any workspace, then show + activate the Home tab. */
  goHome(): void {
    if (this.activeWorkspace()) {
      this.openHomeWorkspace();
    }
    this.ensureHomeTab();
    if (this.homeTabId != null) {
      const gid = Object.entries(this.groups()).find(([, g]) => g.tabs.some((t) => t.id === this.homeTabId))?.[0];
      if (gid) this.activate(gid, this.homeTabId, true);
    } else {
      this.syncUrl('push');
    }
  }

  openApp(ep: PortalEntryPoint, activate = true): void {
    if (ep.type === 'link') {
      window.open(ep.url, '_blank', 'noopener');
      return;
    }
    const id = entryPointId(ep);
    if (!ep.multi) {
      for (const g of Object.values(this.groups())) {
        const existing = g.tabs.find((t) => entryPointId(t.entryPoint) === id);
        if (existing) {
          this.activate(g.id, existing.id, true);
          return;
        }
      }
    }
    let gid = this.primaryGroupId();
    if (!gid || !this.groups()[gid]) {
      gid = this.addGroup().id;
    }
    const tab = { id: this.nextTabId++, entryPoint: ep, instance: 0 };
    this.groups.update((gs) => {
      const cur = gs[gid];
      return { ...gs, [gid]: { ...cur, tabs: withInstances([...cur.tabs, tab]) } };
    });
    if (activate) {
      this.activate(gid, tab.id, true);
    } else {
      this.focusedGroupId.set(gid);
    }
    this.saveSessionSnapshot();
  }

  activate(groupId: string, tabId: number, pushUrl = false): void {
    this.groups.update((gs) => ({ ...gs, [groupId]: { ...gs[groupId], activeId: tabId } }));
    this.focusedGroupId.set(groupId);
    if (pushUrl) this.syncUrl('push');
  }

  getActiveTabKey(): string | null {
    const gid = this.focusedGroupId();
    if (!gid) return null;
    const group = this.groups()[gid];
    if (!group || group.activeId == null) return null;
    const tab = group.tabs.find((t) => t.id === group.activeId);
    return tab ? tabKeyOf(entryPointId(tab.entryPoint), tab.instance) : null;
  }

  // ── Navigation URL sync (all History API access delegated to UrlSyncService) ──

  getTabPath(key: string): string | undefined {
    return this.tabPaths.get(key);
  }

  setTabPath(key: string, path: string): void {
    this.tabPaths.set(key, path);
  }

  replaceAllTabPaths(paths: Record<string, string>): void {
    this.tabPaths = new Map(Object.entries(paths));
  }

  currentNavState(): NavState {
    const app = this.getActiveTabKey();
    return { ws: this.activeWorkspace(), app, path: app ? (this.tabPaths.get(app) ?? null) : null };
  }

  /** Writes the current navigation state to the URL (push = user navigation, replace = normalization/mutation). */
  syncUrl(mode: 'push' | 'replace'): void {
    if (this.suppressSync) return;
    const nav = this.currentNavState();
    const paths = Object.fromEntries(this.tabPaths);
    if (mode === 'push') this.urlSync.push(nav, paths);
    else this.urlSync.replace(nav, paths);
  }

  /** Suppresses URL writes during coordinator-driven state application (popstate, cold load). */
  async withUrlSyncSuppressedAsync<T>(fn: () => Promise<T>): Promise<T> {
    const prev = this.suppressSync;
    this.suppressSync = true;
    try {
      return await fn();
    } finally {
      this.suppressSync = prev;
    }
  }

  /** Finds the open tab with the given tabKey (`epId` or `epId:instance`). */
  findByTabKey(key: string): { groupId: string; tab: Tab } | null {
    for (const [gid, g] of Object.entries(this.groups())) {
      const tab = g.tabs.find((t) => tabKeyOf(entryPointId(t.entryPoint), t.instance) === key);
      if (tab) return { groupId: gid, tab };
    }
    for (const [gid, g] of Object.entries(this.groups())) {
      const tab = g.tabs.find((t) => entryPointId(t.entryPoint) === key);
      if (tab) return { groupId: gid, tab };
    }
    return null;
  }

  /** Resolves an `app` URL param to a known entry point (tolerates instance suffixes and bare moduleKeys). */
  findEntryPointByAppKey(app: string): PortalEntryPoint | undefined {
    const eps = this.entryPoints().filter((ep) => ep.active !== false);
    const direct = eps.find((ep) => entryPointId(ep) === app);
    if (direct) return direct;
    const stripped = app.replace(/:\d+$/, '');
    if (stripped !== app) {
      const { moduleKey, entryKey } = parseEntryPointId(stripped);
      return eps.find((ep) => ep.moduleKey === moduleKey && ep.entryKey === entryKey);
    }
    if (!app.includes(':')) {
      return eps.find((ep) => ep.moduleKey === app && ep.entryKey === 'main');
    }
    return undefined;
  }

  closeTab(groupId: string, tabId: number): void {
    if (tabId === this.homeTabId) return; // the Home tab cannot be closed
    const g = this.groups()[groupId];
    if (!g) return;
    const idx = g.tabs.findIndex((t) => t.id === tabId);
    const remaining = g.tabs.filter((t) => t.id !== tabId);
    const nextActive =
      g.activeId === tabId ? (remaining[Math.min(idx, remaining.length - 1)]?.id ?? null) : g.activeId;
    this.groups.update((gs) => ({
      ...gs,
      [groupId]: { ...gs[groupId], tabs: withInstances(remaining), activeId: nextActive },
    }));
    if (remaining.length === 0) this.removeGroup(groupId);
    this.syncAfterMutation();
  }

  closeArea(groupId: string): void {
    // The area holding the Home tab is unclosable (the tab itself is).
    const g = this.groups()[groupId];
    if (g?.tabs.some((t) => t.id === this.homeTabId)) return;
    this.removeGroup(groupId);
    this.syncAfterMutation();
  }

  openAppInGroup(ep: PortalEntryPoint, groupId: string): void {
    if (!this.groups()[groupId]) return;
    const id = entryPointId(ep);
    const existing = this.groups()[groupId].tabs.find((t) => entryPointId(t.entryPoint) === id);
    if (existing) {
      this.activate(groupId, existing.id, true);
      return;
    }
    const tab = { id: this.nextTabId++, entryPoint: ep, instance: 0 };
    this.groups.update((gs) => ({
      ...gs,
      [groupId]: {
        ...gs[groupId],
        tabs: withInstances([...gs[groupId].tabs, tab]),
      },
    }));
    this.activate(groupId, tab.id, true);
    this.saveSessionSnapshot();
  }

  /** Closes every tab of the group except the (unclosable) Home tab. */
  closeAllExceptHome(groupId: string): void {
    const g = this.groups()[groupId];
    if (!g) return;
    const homeTab = g.tabs.find((t) => t.id === this.homeTabId);
    if (!homeTab) return;
    if (g.tabs.length === 1) {
      this.activate(groupId, homeTab.id, true);
      return;
    }
    this.groups.update((gs) => ({
      ...gs,
      [groupId]: {
        ...gs[groupId],
        tabs: withInstances([homeTab]),
        activeId: homeTab.id,
      },
    }));
    this.syncAfterMutation();
  }

  split(groupId: string, dir: SplitDir): void {
    if (!this.groups()[groupId]) return;
    const newGroup: TabGroup = { id: `g${this.nextGroupId++}`, tabs: [], activeId: null };
    this.groups.update((gs) => ({ ...gs, [newGroup.id]: newGroup }));
    const splitId = `s${this.nextSplitId++}`;
    const replace = (node: LayoutNode): LayoutNode => {
      if (node.kind === 'leaf' && node.groupId === groupId) {
        return {
          kind: 'split',
          id: splitId,
          dir,
          ratio: 0.5,
          a: { kind: 'leaf', groupId },
          b: { kind: 'leaf', groupId: newGroup.id },
        };
      }
      if (node.kind === 'split') return { ...node, a: replace(node.a), b: replace(node.b) };
      return node;
    };
    this.layout.update((l) => (l ? replace(l) : null));
    this.focusedGroupId.set(newGroup.id);
    this.saveSessionSnapshot();
  }

  resizeSplit(id: string, ratio: number): void {
    const r = Math.min(0.85, Math.max(0.15, ratio));
    const upd = (node: LayoutNode): LayoutNode => {
      if (node.kind === 'split') {
        if (node.id === id) return { ...node, ratio: r };
        return { ...node, a: upd(node.a), b: upd(node.b) };
      }
      return node;
    };
    this.layout.update((l) => (l ? upd(l) : l));
  }

  onDrop(event: CdkDragDrop<Tab[]>): void {
    const srcEl = event.previousContainer.element.nativeElement as HTMLElement;
    const dstEl = event.container.element.nativeElement as HTMLElement;
    const srcId = srcEl.closest('[data-group]')?.getAttribute('data-group') ?? null;
    const dstId = dstEl.closest('[data-group]')?.getAttribute('data-group') ?? null;
    const groups = this.groups();
    const src = srcId ? groups[srcId] : undefined;
    const dst = dstId ? groups[dstId] : undefined;
    if (!src || !dst) return;
    const srcKey = src.id;
    const dstKey = dst.id;
    const draggedTab = src.tabs[event.previousIndex];
    if (!draggedTab || draggedTab.id === this.homeTabId) return; // Home tab is a fixture

    if (srcKey === dstKey) {
      const next = [...src.tabs];
      const homeFirst = next.some((t) => t.id === this.homeTabId);
      const target = homeFirst ? Math.max(1, event.currentIndex) : event.currentIndex;
      moveItemInArray(next, event.previousIndex, target);
      this.groups.update((gs) => ({ ...gs, [srcKey]: { ...gs[srcKey], tabs: withInstances(next) } }));
      return;
    }

    const tab = draggedTab;
    const nextSrc = src.tabs.filter((_, i) => i !== event.previousIndex);
    const removedActive = src.activeId === tab.id;
    const nextActiveSrc = removedActive
      ? (nextSrc[Math.min(event.previousIndex, nextSrc.length - 1)]?.id ?? null)
      : src.activeId;
    // The Home tab always stays first in its group.
    const homeFirst = dst.tabs.some((t) => t.id === this.homeTabId);
    const insertAt = homeFirst ? Math.max(1, event.currentIndex) : event.currentIndex;
    const nextDst = [...dst.tabs];
    nextDst.splice(Math.min(insertAt, nextDst.length), 0, tab);
    this.groups.update((gs) => ({
      ...gs,
      [srcKey]: { ...gs[srcKey], tabs: withInstances(nextSrc), activeId: nextActiveSrc },
      [dstKey]: { ...gs[dstKey], tabs: withInstances(nextDst), activeId: tab.id },
    }));
    this.focusedGroupId.set(dstKey);
    this.syncAfterMutation();
  }

  async saveWorkspace(name: string, description = '', locked = false, color = '', status = ''): Promise<void> {
    const data = this.serializeCurrent();
    const snap: WorkspaceSnapshot = {
      name,
      description,
      savedAt: Date.now(),
      layout: data.layout,
      groups: data.groups,
      focusedGroupId: data.focusedGroupId,
      color: data.color,
      status,
      hideSingleTabToolbar: this.hideSingleTabToolbar(),
      locked,
    };
    const existingId = this.cachedSnapshot?.id ?? null;
    if (existingId) {
      const res = await fetch(`/api/workspaces/${existingId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name,
          description,
          layout: snap.layout,
          groups: snap.groups,
          focusedGroupId: snap.focusedGroupId,
          hideSingleTabToolbar: snap.hideSingleTabToolbar,
          locked: snap.locked,
          color: snap.color,
          status: snap.status,
        }),
      });
      const { id } = await res.json();
      snap.id = id;
    } else {
      const res = await fetch('/api/workspaces', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name,
          description,
          layout: snap.layout,
          groups: snap.groups,
          focusedGroupId: snap.focusedGroupId,
          hideSingleTabToolbar: snap.hideSingleTabToolbar,
          locked: snap.locked,
          status: snap.status,
        }),
      });
      const { id, name: savedName } = await res.json();
      snap.id = id;
      snap.name = savedName;
    }
    this.cachedSnapshot = snap;
    this.activeWorkspace.set(snap.name);
    this.description.set(description);
    this.locked.set(locked);
    this.workspaceStatus.set(status);
    if (snap.id) localStorage.setItem(ACTIVE_KEY, snap.id);
    this.dirty.set(false);
    this.syncUrl('replace');
    await this.refreshList();
  }

  async saveAsNewWorkspace(name: string, description = '', locked = false, color = '', status = ''): Promise<void> {
    const data = this.serializeCurrent();
    const res = await fetch('/api/workspaces', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name,
        description,
        layout: data.layout,
        groups: data.groups,
        focusedGroupId: data.focusedGroupId,
        hideSingleTabToolbar: this.hideSingleTabToolbar(),
        locked,
        color: color || data.color,
        status,
      }),
    });
    const { id, name: savedName } = await res.json();
    const snap: WorkspaceSnapshot = {
      id,
      name: savedName,
      description,
      savedAt: Date.now(),
      layout: data.layout,
      groups: data.groups,
      focusedGroupId: data.focusedGroupId,
      hideSingleTabToolbar: this.hideSingleTabToolbar(),
      locked,
      color: color || data.color,
      status,
    };
    this.cachedSnapshot = snap;
    this.activeWorkspace.set(savedName);
    this.description.set(description);
    this.locked.set(locked);
    this.workspaceStatus.set(status);
    localStorage.setItem(ACTIVE_KEY, id);
    this.dirty.set(false);
    this.syncUrl('push');
    await this.refreshList();
  }

  async loadWorkspace(name: string, enterEditMode = true, updateHash = true): Promise<void> {
    if (this.hasTabs() && this.dirty()) {
      const snap: WorkspaceSnapshot = {
        name: this.activeWorkspace() || 'home',
        savedAt: Date.now(),
        layout: this.layout(),
        groups: { ...this.serializeCurrent().groups },
        focusedGroupId: this.focusedGroupId(),
        hideSingleTabToolbar: this.hideSingleTabToolbar(),
        locked: this.locked(),
      };
      sessionStorage.setItem(HOME_SNAPSHOT_KEY, JSON.stringify(snap));
    }
    try {
      const res = await fetch(`/api/workspaces/${encodeURIComponent(name)}`);
      if (!res.ok) return;
      const snap = (await res.json()) as WorkspaceSnapshot;
      this.applySnapshot(snap);
      this.cachedSnapshot = snap;
      this.activeWorkspace.set(name);
      if (snap.id) localStorage.setItem(ACTIVE_KEY, snap.id);
      this.hideSingleTabToolbar.set(snap.hideSingleTabToolbar ?? false);
      this.locked.set(!!snap.locked);
      this.description.set(snap.description ?? '');
      this.editMode.set(snap.locked ? false : enterEditMode);
      if (updateHash) {
        this.syncUrl('replace');
      }
    } catch {
      // ignore network errors
    }
    if (!this.activeWorkspace() && this.hasTabs()) {
      const stored = sessionStorage.getItem(HOME_SNAPSHOT_KEY);
      if (!stored) {
        sessionStorage.removeItem(HOME_SNAPSHOT_KEY);
      }
    }
  }

  async deleteWorkspace(name: string): Promise<void> {
    await fetch(`/api/workspaces/${encodeURIComponent(name)}`, { method: 'DELETE' });
    if (this.activeWorkspace() === name) {
      this.activeWorkspace.set(null);
      this.cachedSnapshot = null;
      this.description.set('');
      this.workspaceColor.set('');
      localStorage.removeItem(ACTIVE_KEY);
      this.syncUrl('push');
      this.groups.set({});
      this.layout.set(null);
      this.focusedGroupId.set(null);
      this.goHome();
    }
    await this.refreshList();
  }

  createNewWorkspace(): void {
    this.groups.set({});
    this.layout.set(null);
    this.focusedGroupId.set(null);
    this.nextTabId = 1;
    this.nextGroupId = 1;
    this.nextSplitId = 1;
    this.cachedSnapshot = null;
    this.activeWorkspace.set(null);
    localStorage.removeItem(ACTIVE_KEY);
    this.dirty.set(true);
    this.editMode.set(true);
    this.hideSingleTabToolbar.set(false);
    this.locked.set(false);
    this.description.set('');
    this.syncUrl('push');
    this.ensureHomeTab();
  }

  openHomeWorkspace(): void {
    if (!this.activeWorkspace() && this.hasTabs()) return;
    const stored = sessionStorage.getItem(HOME_SNAPSHOT_KEY);
    const snapFromStorage = stored ? JSON.parse(stored) : null;
    if (snapFromStorage) {
      this.applySnapshot(snapFromStorage);
      this.cachedSnapshot = null;
      this.activeWorkspace.set(null);
      this.workspaceColor.set('');
      this.editMode.set(true);
      this.dirty.set(true);
      this.hideSingleTabToolbar.set(false);
      this.locked.set(false);
      this.description.set('');
      localStorage.removeItem(ACTIVE_KEY);
      this.ensureHomeTab();
      this.syncUrl('push');
    } else {
      this.createNewWorkspace();
    }
  }

  async discardChanges(): Promise<void> {
    const snap = this.cachedSnapshot;
    if (snap) {
      this.applySnapshot(snap);
      this.description.set(snap.description ?? '');
    }
    this.dirty.set(false);
    this.editMode.set(false);
  }

  async renameWorkspace(oldName: string, newName: string): Promise<void> {
    if (!newName || newName === oldName) return;
    await this.saveWorkspace(newName, this.description(), this.locked(), this.workspaceColor(), this.workspaceStatus());
    await fetch(`/api/workspaces/${encodeURIComponent(oldName)}`, { method: 'DELETE' });
    this.syncUrl('replace');
    await this.refreshList();
  }

  async restore(): Promise<void> {
    await this.refreshList();
    const storedId = localStorage.getItem(ACTIVE_KEY);
    let loaded = false;
    if (storedId && this.workspacesGate()) {
      const meta = this.workspaces().find((w) => w.id === storedId);
      if (meta) {
        await this.loadWorkspace(meta.name, false);
        loaded = true;
      }
    }
    if (!loaded) {
      const homeSnap = sessionStorage.getItem(HOME_SNAPSHOT_KEY);
      if (homeSnap) {
        try {
          const snap = JSON.parse(homeSnap) as WorkspaceSnapshot;
          this.applySnapshot(snap);
          this.cachedSnapshot = null;
          this.activeWorkspace.set(null);
          this.editMode.set(true);
          this.dirty.set(true);
          this.hideSingleTabToolbar.set(false);
          this.locked.set(false);
          this.description.set('');
          loaded = true;
          this.ensureHomeTab();
          this.syncUrl('replace');
        } catch { /* ignore parse errors */ }
      }
    }
    if (!loaded) {
      this.ensureHomeTab();
      // Fresh session: show the Home tab rather than an empty area.
      if (this.homeTabId != null) {
        const gid = Object.entries(this.groups()).find(([, g]) => g.tabs.some((t) => t.id === this.homeTabId))?.[0];
        if (gid) this.activate(gid, this.homeTabId, false);
      }
    }
  }

  private onBeforeUnload = (): void => {
    if (!this.activeWorkspace() && this.hasTabs()) {
      const snap: WorkspaceSnapshot = {
        name: 'home',
        savedAt: Date.now(),
        layout: this.layout(),
        groups: this.serializeCurrent().groups,
        focusedGroupId: this.focusedGroupId(),
        hideSingleTabToolbar: this.hideSingleTabToolbar(),
        locked: this.locked(),
      };
      sessionStorage.setItem(HOME_SNAPSHOT_KEY, JSON.stringify(snap));
    }
  };

  private async refreshList(): Promise<void> {
    try {
      const res = await fetch('/api/workspaces');
      if (!res.ok) return;
      const { workspaces } = (await res.json()) as { workspaces: WorkspaceMeta[] };
      this.workspaces.set(workspaces);
    } catch {
      // ignore
    }
  }

  private addGroup(): TabGroup {
    const group: TabGroup = { id: `g${this.nextGroupId++}`, tabs: [], activeId: null };
    this.groups.update((gs) => ({ ...gs, [group.id]: group }));
    if (!this.layout()) this.layout.set({ kind: 'leaf', groupId: group.id });
    return group;
  }

  private removeGroup(groupId: string): void {
    this.groups.update((gs) => {
      const { [groupId]: _removed, ...rest } = gs;
      return rest;
    });
    const layout = this.layout();
    if (!layout) return;
    const next = removeLeaf(layout, groupId);
    this.layout.set(next);
    if (this.focusedGroupId() === groupId) {
      this.focusedGroupId.set(next ? firstLeaf(next) : null);
    }
  }

  private syncAfterMutation(): void {
    if (!this.hasTabs()) {
      this.groups.set({});
      this.layout.set(null);
      this.focusedGroupId.set(null);
    }
    this.saveSessionSnapshot();
    this.syncUrl('replace');
  }

  private saveSessionSnapshot(): void {
    if (!this.activeWorkspace() && this.hasTabs()) {
      const snap: WorkspaceSnapshot = {
        name: 'home',
        savedAt: Date.now(),
        layout: this.layout(),
        groups: this.serializeCurrent().groups,
        focusedGroupId: this.focusedGroupId(),
        hideSingleTabToolbar: this.hideSingleTabToolbar(),
        locked: this.locked(),
      };
      sessionStorage.setItem(HOME_SNAPSHOT_KEY, JSON.stringify(snap));
    }
  }

  private serializeCurrent(): Omit<WorkspaceSnapshot, 'name' | 'savedAt' | 'hideSingleTabToolbar' | 'description' | 'locked' | 'status'> {
    const groups: Record<string, SavedGroup> = {};
    for (const [gid, g] of Object.entries(this.groups())) {
      // The Home tab is a view fixture, never workspace state.
      const tabs = g.tabs.filter((t) => t.id !== this.homeTabId);
      const activeTab = g.tabs.find((t) => t.id === g.activeId);
      let activeIdx: number | null;
      if (tabs.length === 0) {
        activeIdx = null;
      } else if (!activeTab) {
        activeIdx = 0;
      } else if (activeTab.id === this.homeTabId) {
        activeIdx = 0; // home active → persist the first serialized tab as active
      } else {
        const serializedIdx = tabs.findIndex((t) => t.id === activeTab.id);
        activeIdx = serializedIdx >= 0 ? serializedIdx : 0;
      }
      groups[gid] = { tabs: tabs.map((t) => entryPointId(t.entryPoint)), activeIdx };
    }
    return { layout: this.layout(), groups, focusedGroupId: this.focusedGroupId(), color: this.workspaceColor() };
  }

  private applySnapshot(snap: WorkspaceSnapshot): void {
    const groups: Record<string, TabGroup> = {};
    for (const [gid, sg] of Object.entries(snap.groups ?? {})) {
      const tabs: Tab[] = [];
      for (const key of sg.tabs ?? []) {
        // Support both old format (moduleKey only) and new format (moduleKey:entryKey)
        const { moduleKey, entryKey } = parseEntryPointId(key);
        const ep = this.findEntryPoint(moduleKey, entryKey);
        if (ep && ep.type !== 'link') tabs.push({ id: this.nextTabId++, entryPoint: ep, instance: 0 });
      }
      const resolved = withInstances(tabs);
      const activeId =
        sg.activeIdx != null && resolved[sg.activeIdx] ? resolved[sg.activeIdx].id : (resolved[resolved.length - 1]?.id ?? null);
      groups[gid] = { id: gid, tabs: resolved, activeId };
    }
    let layout = snap.layout ?? null;
    this.groups.set(groups);
    this.layout.set(layout);
    this.focusedGroupId.set(layout ? (snap.focusedGroupId && groups[snap.focusedGroupId] ? snap.focusedGroupId : firstLeaf(layout)) : null);
    this.workspaceColor.set(snap.color ?? '');
    this.workspaceStatus.set(snap.status ?? '');
    this.bumpIds(snap);
  }

  private bumpIds(snap: WorkspaceSnapshot): void {
    const walk = (n: LayoutNode): void => {
      if (n.kind === 'split') {
        const m = /^s(\d+)$/.exec(n.id);
        if (m) this.nextSplitId = Math.max(this.nextSplitId, Number(m[1]) + 1);
        walk(n.a);
        walk(n.b);
      }
    };
    if (snap.layout) walk(snap.layout);
    for (const gid of Object.keys(snap.groups ?? {})) {
      const m = /^g(\d+)$/.exec(gid);
      if (m) this.nextGroupId = Math.max(this.nextGroupId, Number(m[1]) + 1);
    }
  }
}
