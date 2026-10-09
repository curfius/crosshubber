import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CdkDragDrop } from '@angular/cdk/drag-drop';
import { moduleContentId, type PortalModuleContent, type Tab } from '../models';
import { WorkbenchService } from './workspaces.store';

type FetchMock = ReturnType<typeof vi.fn>;

function okResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    json: async () => body,
    headers: new Headers(),
  } as unknown as Response;
}

function failResponse(status: number, body: unknown): Response {
  return {
    ok: false,
    status,
    json: async () => body,
    headers: new Headers(),
  } as unknown as Response;
}

describe('WorkbenchService workspace persistence', () => {
  let wb: WorkbenchService;
  let fetchMock: FetchMock;

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    TestBed.configureTestingModule({});
    wb = TestBed.inject(WorkbenchService);
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('saveWorkspace PUTs when a snapshot id exists and applies the saved state', async () => {
    fetchMock
      .mockResolvedValueOnce(okResponse({ workspaces: [] }))
      .mockResolvedValueOnce(okResponse({ id: 'ws-1', name: 'w1' }))
      .mockResolvedValueOnce(okResponse({ workspaces: [{ id: 'ws-1', name: 'w1' }] }));
    await wb.restore();
    await wb.saveWorkspace('w1');
    expect(wb.activeWorkspace()).toBe('w1');
    expect(wb.dirty()).toBe(false);
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/workspaces', expect.anything());
  });

  it('saveWorkspace keeps dirty state when the server rejects the save', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    fetchMock
      .mockResolvedValueOnce(okResponse({ workspaces: [] }))
      .mockResolvedValueOnce(failResponse(409, { error: 'conflict' }));
    await wb.restore();
    await wb.saveWorkspace('w1');
    expect(wb.activeWorkspace()).toBeNull();
    expect(localStorage.getItem('portal.activeWorkspace')).toBeNull();
    expect(errorSpy).toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it('deleteWorkspace does not clear state when the DELETE fails', async () => {
    fetchMock.mockResolvedValue(failResponse(500, { error: 'nope' }));
    await wb.deleteWorkspace('w1');
    expect(wb.activeWorkspace()).toBeNull();
    expect(fetchMock).toHaveBeenCalledWith('/api/workspaces/w1', { method: 'DELETE' });
  });

  it('deleteWorkspace of the active workspace resets to home and clears active key', async () => {
    fetchMock
      .mockResolvedValueOnce(okResponse({ workspaces: [{ id: 'ws-1', name: 'w1', savedAt: 1 }] }))
      .mockResolvedValueOnce(okResponse({ id: 'ws-1', name: 'w1' }))
      .mockResolvedValueOnce(okResponse({ ok: true }))
      .mockResolvedValueOnce(okResponse({ workspaces: [] }));
    await wb.restore();
    await wb.loadWorkspace('w1');
    expect(wb.activeWorkspace()).toBe('w1');
    await wb.deleteWorkspace('w1');
    expect(wb.activeWorkspace()).toBeNull();
    expect(localStorage.getItem('portal.activeWorkspace')).toBeNull();
  });

  it('renameWorkspace renames a non-active workspace via GET+PUT without touching local state', async () => {
    fetchMock
      .mockResolvedValueOnce(
        okResponse({ id: 'id-b', name: 'b', layout: null, groups: {}, savedAt: 1 }),
      )
      .mockResolvedValueOnce(okResponse({ ok: true, id: 'id-b', name: 'c' }))
      .mockResolvedValueOnce(okResponse({ workspaces: [{ id: 'id-b', name: 'c', savedAt: 1 }] }));
    await wb.renameWorkspace('b', 'c');
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/workspaces/b');
    const putCall = fetchMock.mock.calls[1];
    expect(putCall[0]).toBe('/api/workspaces/id-b');
    expect((putCall[1] as RequestInit).method).toBe('PUT');
    expect(String((putCall[1] as RequestInit).body)).toContain('"name":"c"');
    expect(fetchMock).toHaveBeenNthCalledWith(3, '/api/workspaces');
    expect(wb.activeWorkspace()).toBeNull();
  });

  it('renameWorkspace of the active workspace updates the active name and URL', async () => {
    fetchMock
      .mockResolvedValueOnce(okResponse({ workspaces: [{ id: 'ws-1', name: 'w1', savedAt: 1 }] }))
      .mockResolvedValueOnce(okResponse({ id: 'ws-1', name: 'w1', layout: null, groups: {}, savedAt: 1 }))
      .mockResolvedValueOnce(okResponse({ id: 'ws-1', name: 'w1', layout: null, groups: {}, savedAt: 1 }))
      .mockResolvedValueOnce(okResponse({ ok: true, id: 'ws-1', name: 'w2' }))
      .mockResolvedValueOnce(okResponse({ workspaces: [{ id: 'ws-1', name: 'w2', savedAt: 1 }] }));
    await wb.restore();
    await wb.loadWorkspace('w1');
    expect(wb.activeWorkspace()).toBe('w1');
    await wb.renameWorkspace('w1', 'w2');
    expect(wb.activeWorkspace()).toBe('w2');
    expect(location.pathname).toBe('/w/w2');
  });

  it('renameWorkspace leaves state unchanged when the lookup fails', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    fetchMock.mockResolvedValueOnce(failResponse(404, { error: 'not found' }));
    await wb.renameWorkspace('ghost', 'x');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(wb.activeWorkspace()).toBeNull();
    expect(errorSpy).toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it('saveSessionSnapshot persists home state to sessionStorage', () => {
    wb.setModuleContents([]);
    wb.openApp({
      moduleKey: 'portal-dashboard',
      contentKey: 'main',
      category: 'applications',
      name: 'Dashboard',
      type: 'iframe',
      url: 'https://dashboard.example.com',
      parentContentKey: null,
      groupKey: null,
      sortOrder: 0,
    });
    expect(sessionStorage.getItem('portal.homeSnapshot')).not.toBeNull();
    const snap = JSON.parse(sessionStorage.getItem('portal.homeSnapshot')!) as { name: string };
    expect(snap.name).toBe('home');
  });
});

describe('WorkbenchService workarea interactions', () => {
  let wb: WorkbenchService;
  let fetchMock: FetchMock;

  function ep(moduleKey: string, contentKey: string): PortalModuleContent {
    return {
      moduleKey,
      contentKey,
      category: 'applications',
      name: moduleKey,
      type: 'iframe',
      url: `https://${moduleKey}.example.com`,
      parentContentKey: null,
      groupKey: null,
      sortOrder: 0,
      active: true,
    };
  }

  const eps = [ep('navigation', 'portal'), ep('docs', 'main'), ep('wiki', 'main'), ep('api', 'main')];
  const [, docsEp, wikiEp, apiEp] = eps;

  /** Minimal CDK drop event: the store resolves groups via [data-group] and the tab via item.data. */
  function dropEvent(
    srcGroup: string,
    dstGroup: string,
    tab: Tab,
    previousIndex: number,
    currentIndex: number,
  ): CdkDragDrop<Tab[], Tab> {
    const el = (group: string): HTMLElement => {
      const div = document.createElement('div');
      div.setAttribute('data-group', group);
      return div;
    };
    return {
      previousContainer: { element: { nativeElement: el(srcGroup) } },
      container: { element: { nativeElement: el(dstGroup) } },
      item: { data: tab },
      previousIndex,
      currentIndex,
    } as unknown as CdkDragDrop<Tab[], Tab>;
  }

  const keys = (gid: string): string[] =>
    (wb.groups()[gid]?.tabs ?? []).map((t) => moduleContentId(t.content));
  const findTab = (gid: string, key: string): Tab =>
    wb.groups()[gid].tabs.find((t) => moduleContentId(t.content) === key)!;

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    TestBed.configureTestingModule({});
    wb = TestBed.inject(WorkbenchService);
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    wb.setModuleContents(eps);
    wb.ensureHomeTab();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('reorders by tab identity when the Home fixture offsets CDK indices', () => {
    const g1 = wb.primaryGroupId()!;
    wb.openApp(docsEp);
    wb.openApp(wikiEp);
    wb.openApp(apiEp);
    expect(keys(g1)).toEqual(['navigation:portal', 'docs:main', 'wiki:main', 'api:main']);

    // CDK indices cover drag items only (Home renders without cdkDrag): api is index 2,
    // target 0 = before docs — not before Home.
    wb.onDrop(dropEvent(g1, g1, findTab(g1, 'api:main'), 2, 0));
    expect(keys(g1)).toEqual(['navigation:portal', 'api:main', 'docs:main', 'wiki:main']);

    // Without Home in the strip the raw indices apply directly.
    wb.toggleHomeVisible();
    expect(keys(g1)).toEqual(['api:main', 'docs:main', 'wiki:main']);
    wb.onDrop(dropEvent(g1, g1, findTab(g1, 'wiki:main'), 1, 0));
    expect(keys(g1)).toEqual(['wiki:main', 'api:main', 'docs:main']);

    wb.toggleHomeVisible();
    expect(keys(g1)).toEqual(['navigation:portal', 'wiki:main', 'api:main', 'docs:main']);
  });

  it('moves a tab across areas by identity, inserting after the destination Home fixture', () => {
    const g1 = wb.primaryGroupId()!;
    wb.openApp(docsEp);
    wb.openApp(wikiEp);
    wb.split(g1, 'vertical');
    const g2 = Object.keys(wb.groups()).find((id) => id !== g1)!;
    wb.openAppInGroup(apiEp, g2);
    expect(keys(g2)).toEqual(['api:main']);

    wb.onDrop(dropEvent(g2, g1, findTab(g2, 'api:main'), 0, 0));

    expect(keys(g1)).toEqual(['navigation:portal', 'api:main', 'docs:main', 'wiki:main']);
    expect(keys(g2)).toEqual([]);
    expect(wb.focusedGroupId()).toBe(g1);
    const g = wb.groups()[g1];
    expect(moduleContentId(g.tabs.find((t) => t.id === g.activeId)!.content)).toBe('api:main');
  });

  it('closeArea on the primary group keeps the area and closes its non-home tabs', () => {
    const g1 = wb.primaryGroupId()!;
    wb.openApp(docsEp);
    wb.openApp(wikiEp);
    wb.split(g1, 'vertical');
    const g2 = Object.keys(wb.groups()).find((id) => id !== g1)!;
    wb.openAppInGroup(apiEp, g2);

    wb.closeArea(g1);

    expect(keys(g1)).toEqual(['navigation:portal']);
    expect(wb.layout()!.kind).toBe('split');
    expect(keys(g2)).toEqual(['api:main']);
    const g = wb.groups()[g1];
    expect(wb.isHomeTab(g.tabs.find((t) => t.id === g.activeId)!)).toBe(true);
  });

  it('closeArea removes an empty group immediately and collapses the split', () => {
    const g1 = wb.primaryGroupId()!;
    wb.split(g1, 'vertical');
    const g2 = Object.keys(wb.groups()).find((id) => id !== g1)!;

    wb.closeArea(g2);

    expect(wb.groups()[g2]).toBeUndefined();
    expect(wb.layout()!.kind).toBe('leaf');
  });

  it('closeArea removes a non-primary group together with its tabs', () => {
    const g1 = wb.primaryGroupId()!;
    wb.split(g1, 'vertical');
    const g2 = Object.keys(wb.groups()).find((id) => id !== g1)!;
    wb.openAppInGroup(apiEp, g2);

    wb.closeArea(g2);

    expect(wb.groups()[g2]).toBeUndefined();
    expect(keys(g1)).toEqual(['navigation:portal']);
    expect(wb.layout()!.kind).toBe('leaf');
  });

  it('goHome re-shows a hidden Home tab and activates it', () => {
    wb.openApp(docsEp);
    wb.toggleHomeVisible();
    expect(wb.homeVisible()).toBe(false);

    wb.goHome();

    expect(wb.homeVisible()).toBe(true);
    const g1 = wb.primaryGroupId()!;
    expect(keys(g1)[0]).toBe('navigation:portal');
    const g = wb.groups()[g1];
    expect(wb.isHomeTab(g.tabs.find((t) => t.id === g.activeId)!)).toBe(true);
  });

  it('persists homeVisible on save and defaults to shown when the detail omits it', async () => {
    fetchMock
      .mockResolvedValueOnce(okResponse({ id: 'ws-1', name: 'w1' }))
      .mockResolvedValueOnce(
        okResponse({ workspaces: [{ id: 'ws-1', name: 'w1', savedAt: 1, description: '', color: '', status: '' }] }),
      );
    wb.openApp(docsEp);
    wb.toggleHomeVisible();
    await wb.saveWorkspace('w1');

    const post = fetchMock.mock.calls[0];
    expect(String((post[1] as RequestInit).body)).toContain('"homeVisible":false');
    expect(wb.activeWorkspace()).toBe('w1');

    // Legacy detail without homeVisible → the fixture comes back.
    fetchMock.mockResolvedValueOnce(
      okResponse({
        id: 'ws-1',
        name: 'w1',
        description: '',
        layout: { kind: 'leaf', groupId: 'g1' },
        groups: { g1: { tabs: ['docs:main'], activeIdx: 0 } },
        focusedGroupId: 'g1',
        hideSingleTabToolbar: false,
        locked: false,
        color: '',
        status: '',
        savedAt: 1,
      }),
    );
    await wb.loadWorkspace('w1', false);
    expect(wb.homeVisible()).toBe(true);
    expect(keys(wb.primaryGroupId()!)).toEqual(['navigation:portal', 'docs:main']);

    // Explicit false → the fixture stays out of the strip.
    fetchMock.mockResolvedValueOnce(
      okResponse({
        id: 'ws-1',
        name: 'w1',
        description: '',
        layout: { kind: 'leaf', groupId: 'g1' },
        groups: { g1: { tabs: ['docs:main'], activeIdx: 0 } },
        focusedGroupId: 'g1',
        hideSingleTabToolbar: false,
        homeVisible: false,
        locked: false,
        color: '',
        status: '',
        savedAt: 1,
      }),
    );
    await wb.loadWorkspace('w1', false);
    expect(wb.homeVisible()).toBe(false);
    expect(keys(wb.primaryGroupId()!)).toEqual(['docs:main']);
  });

  it('normalizes legacy split dir values on load', async () => {
    fetchMock.mockResolvedValueOnce(
      okResponse({
        id: 'ws-1',
        name: 'w1',
        description: '',
        layout: {
          kind: 'split',
          id: 's9',
          dir: 'row',
          ratio: 0.6,
          a: { kind: 'leaf', groupId: 'g1' },
          b: {
            kind: 'split',
            id: 's10',
            dir: 'col',
            ratio: 0.4,
            a: { kind: 'leaf', groupId: 'g2' },
            b: { kind: 'leaf', groupId: 'g3' },
          },
        },
        groups: {
          g1: { tabs: ['docs:main'], activeIdx: 0 },
          g2: { tabs: ['wiki:main'], activeIdx: 0 },
          g3: { tabs: ['api:main'], activeIdx: 0 },
        },
        focusedGroupId: 'g1',
        hideSingleTabToolbar: false,
        locked: false,
        color: '',
        status: '',
        savedAt: 1,
      }),
    );
    await wb.loadWorkspace('w1', false);

    const layout = wb.layout();
    expect(layout).not.toBeNull();
    if (layout && layout.kind === 'split') {
      expect(layout.dir).toBe('vertical');
      expect(layout.b.kind === 'split' ? layout.b.dir : null).toBe('horizontal');
    } else {
      throw new Error('expected a split layout');
    }
  });
});
