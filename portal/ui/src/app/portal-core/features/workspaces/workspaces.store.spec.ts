import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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
    wb.setEntryPoints([]);
    wb.openApp({
      moduleKey: 'portal-dashboard',
      entryKey: 'main',
      category: 'applications',
      name: 'Dashboard',
      type: 'iframe',
      url: 'https://dashboard.example.com',
      parentEntryKey: null,
      groupKey: null,
      sortOrder: 0,
    });
    expect(sessionStorage.getItem('portal.homeSnapshot')).not.toBeNull();
    const snap = JSON.parse(sessionStorage.getItem('portal.homeSnapshot')!) as { name: string };
    expect(snap.name).toBe('home');
  });
});
