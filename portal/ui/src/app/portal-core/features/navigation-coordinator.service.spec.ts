import { TestBed } from '@angular/core/testing';
import { describe, it, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest';
import { NavigationCoordinator } from './navigation-coordinator.service';
import { UrlSyncService } from '../../core/history/url-sync.service';
import { Bridge } from '../../core/bridge/bridge.service';
import { WorkbenchService } from './workspaces/workspaces.store';
import type { PortalEntryPoint } from '../../core/models';

const ORIGIN = window.location.origin;

function ep(
  moduleKey: string,
  entryKey: string,
  type: PortalEntryPoint['type'],
  url?: string,
  category: PortalEntryPoint['category'] = 'applications',
): PortalEntryPoint {
  return {
    moduleKey,
    entryKey,
    category,
    name: `${moduleKey}/${entryKey}`,
    type,
    url: type === 'iframe' ? url : undefined,
    entryUrl: type === 'mfe' ? url : undefined,
    loadPath: type === 'embedded' ? entryKey : undefined,
    parentEntryKey: null,
    groupKey: null,
    sortOrder: 0,
  };
}

function replaceUrl(url: string): void {
  history.replaceState(null, '', url);
}

function postNavigate(moduleKey: string, path: string, origin = ORIGIN): void {
  window.dispatchEvent(
    new MessageEvent('message', { data: { source: `iframe:${moduleKey}`, type: 'portal:navigate', path }, origin }),
  );
}

const tick = () => new Promise((r) => setTimeout(r, 30));

describe('NavigationCoordinator', () => {
  let wb: WorkbenchService;
  let coordinator: NavigationCoordinator;
  let urlSync: UrlSyncService;
  let docs: PortalEntryPoint;
  let wiki: PortalEntryPoint;
  let warnSpy: ReturnType<typeof vi.spyOn>;

  beforeAll(() => {
    TestBed.configureTestingModule({});
    wb = TestBed.inject(WorkbenchService);
    TestBed.inject(Bridge);
    coordinator = TestBed.inject(NavigationCoordinator);
    urlSync = TestBed.inject(UrlSyncService);
  });

  beforeEach(() => {
    replaceUrl('/');
    localStorage.clear();
    sessionStorage.clear();
    wb.replaceAllTabPaths({});
    wb.groups.set({});
    wb.layout.set(null);
    wb.focusedGroupId.set(null);
    wb.activeWorkspace.set(null);
    docs = ep('docs', 'main', 'iframe', `${ORIGIN}/docs/index.html`);
    wiki = ep('wiki', 'main', 'iframe', 'https://wiki.example.com/home');
    wb.setEntryPoints([docs, wiki]);
    warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ workspaces: [] }) })));
  });

  afterEach(() => {
    warnSpy.mockRestore();
    vi.unstubAllGlobals();
  });

  it('pushes a history entry when the user opens/activates a tab', () => {
    wb.openApp(docs);
    expect(location.search).toBe('?app=docs%3Amain');
    expect((history.state as { v?: number }).v).toBe(1);
    expect(urlSync.read().nav).toEqual({ ws: null, app: 'docs:main', path: null });
  });

  it('replaces (no new entry) on structural mutations', () => {
    wb.openApp(docs);
    const len = history.length;
    wb.openApp(wiki);
    const afterOpen = history.length;
    const gid = Object.keys(wb.groups())[0];
    const wikiTab = wb.groups()[gid].tabs.find((t) => t.entryPoint.moduleKey === 'wiki')!;
    wb.closeTab(gid, wikiTab.id);
    expect(history.length).toBe(afterOpen);
    expect(location.search).toBe('?app=docs%3Amain');
    expect(len).toBeLessThan(afterOpen);
  });

  it('pushes when the focused active tab reports navigation', () => {
    wb.openApp(docs);
    postNavigate('docs', '/intro');
    expect(location.search).toBe('?app=docs%3Amain&path=%2Fintro');
    expect(wb.getTabPath('docs:main')).toBe('/intro');
  });

  it('keeps background-tab navigation in memory only', () => {
    wb.openApp(docs);
    wb.openApp(wiki); // wiki now active, docs backgrounded
    postNavigate('docs', '/intro');
    expect(wb.getTabPath('docs:main')).toBe('/intro');
    expect(location.search).toBe('?app=wiki%3Amain');
  });

  it('drops echo navigations (module -> URL -> restore -> module loop prevention)', () => {
    wb.openApp(docs);
    const len = history.length;
    postNavigate('docs', '/intro');
    const afterFirst = history.length;
    postNavigate('docs', '/intro');
    expect(history.length).toBe(afterFirst);
    expect(afterFirst).toBeGreaterThan(len);
  });

  it('restores tab and module path on popstate', async () => {
    wb.openApp(docs);
    postNavigate('docs', '/intro');
    postNavigate('docs', '/deep');
    history.back();
    await tick();
    expect(location.search).toBe('?app=docs%3Amain&path=%2Fintro');
    expect(wb.getTabPath('docs:main')).toBe('/intro');
    // restore-before-ready: the path waits for the module to mount
    expect(coordinator.consumePendingPath('docs')).toBe('/intro');
  });

  it('auto-opens a closed app on cold load with deep-link params', async () => {
    replaceUrl('/?app=docs:main&path=%2Fintro');
    await coordinator.applyState(urlSync.read(), { cold: true });
    const groups = wb.groups();
    const tabs = Object.values(groups).flatMap((g) => g.tabs);
    expect(tabs.length).toBe(1);
    expect(tabs[0].entryPoint.moduleKey).toBe('docs');
    expect(wb.getTabPath('docs:main')).toBe('/intro');
    expect(coordinator.consumePendingPath('docs')).toBe('/intro');
    expect((history.state as { v?: number }).v).toBe(1);
  });

  it('resolves bare moduleKey app params on cold load', async () => {
    replaceUrl('/?app=docs');
    await coordinator.applyState(urlSync.read(), { cold: true });
    const tabs = Object.values(wb.groups()).flatMap((g) => g.tabs);
    expect(tabs.map((t) => t.entryPoint.moduleKey)).toEqual(['docs']);
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('warns and ignores unknown app keys', async () => {
    replaceUrl('/?app=nope:main');
    await coordinator.applyState(urlSync.read(), { cold: true });
    expect(Object.values(wb.groups()).flatMap((g) => g.tabs)).toEqual([]);
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('nope:main'));
  });

  it('never auto-opens closed apps on popstate', async () => {
    wb.openApp(docs);
    replaceUrl('/?app=wiki%3Amain'); // simulate a URL that never had an entry
    coordinator['applyApp']('wiki:main', null, false);
    const tabs = Object.values(wb.groups()).flatMap((g) => g.tabs);
    expect(tabs.map((t) => t.entryPoint.moduleKey)).toEqual(['docs']);
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('loads a workspace from a deep-linked URL on cold load', async () => {
    const snapshot = {
      name: 'orders',
      savedAt: Date.now(),
      layout: { kind: 'leaf', groupId: 'g1' },
      groups: { g1: { tabs: ['docs:main'], activeIdx: 0 } },
      focusedGroupId: 'g1',
      hideSingleTabToolbar: false,
      locked: false,
    };
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: unknown) =>
        String(url).includes('/api/workspaces/orders')
          ? { ok: true, json: async () => snapshot }
          : { ok: true, json: async () => ({ workspaces: [] }) },
      ),
    );
    replaceUrl('/w/orders?app=docs%3Amain&path=%2Fintro');
    await coordinator.applyState(urlSync.read(), { cold: true });
    expect(wb.activeWorkspace()).toBe('orders');
    expect(wb.getActiveTabKey()).toBe('docs:main');
    expect(wb.getTabPath('docs:main')).toBe('/intro');
    expect(location.pathname).toBe('/w/orders');
  });

  it('replays a pending path to embedded modules registering later', async () => {
    replaceUrl('/?app=docs:main&path=%2Fintro');
    await coordinator.applyState(urlSync.read(), { cold: true });
    const received: string[] = [];
    coordinator.onRestore('docs', (p) => received.push(p));
    await tick();
    expect(received).toEqual(['/intro']);
  });

  it('switches to home on popstate to a bare URL', async () => {
    wb.openApp(docs);
    wb.ensureHomeTab();
    replaceUrl('/');
    history.back();
    await tick();
    // popstate applied: docs tab active again (opened before the home tab)
    expect(wb.getActiveTabKey()).toBe('docs:main');
  });
});
