import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { NavigationStore } from './navigation.store';
import type { PinnedNode } from './navigation.models';

describe('NavigationStore', () => {
  let store: NavigationStore;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    store = new NavigationStore();
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('load() fetches pinned tree, user settings and features', async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url === '/api/navigation/pinned-apps') {
        return Promise.resolve({ ok: true, json: async () => ({ tree: [{ id: '1', nodeType: 'item', ref: 'mod:a', children: [] }] }) });
      }
      if (url === '/api/navigation/user-settings') {
        return Promise.resolve({
          ok: true,
          json: async () => ({ settings: { sidebar: { showPinned: true, showWorkspaces: false, apps: ['mod:a'] }, sidebarExpanded: [] } }),
        });
      }
      return Promise.resolve({ ok: true, json: async () => ({ pinnedAppsEnabled: false, workspacesEnabled: true }) });
    });

    await store.load();

    expect(store.loaded()).toBe(true);
    expect(store.pinnedRefs()).toEqual(['mod:a']);
    expect(store.sidebar().showWorkspaces).toBe(false);
    expect(store.features()).toEqual({ pinnedAppsEnabled: false, workspacesEnabled: true });
  });

  it('pin() posts the ref and refreshes the tree', async () => {
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (url === '/api/navigation/pinned-apps' && init?.method === 'POST') {
        return Promise.resolve({ ok: true, json: async () => ({ ok: true }) });
      }
      if (url === '/api/navigation/pinned-apps') {
        return Promise.resolve({
          ok: true,
          json: async () => ({ tree: [{ id: 'u1', nodeType: 'item', ref: 'mod:x', children: [] }] }),
        });
      }
      return Promise.resolve({ ok: true, json: async () => ({}) });
    });

    await store.pin('mod:x');

    expect(fetchMock).toHaveBeenCalledWith('/api/navigation/pinned-apps', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ref: 'mod:x' }),
    });
    expect(store.isPinned('mod:x')).toBe(true);
  });

  it('unpin() removes the ref locally and remotely', async () => {
    store.pinnedTree.set([{ id: 'u1', nodeType: 'item', ref: 'mod:x', children: [] }]);
    fetchMock.mockResolvedValue({ ok: true, json: async () => ({ ok: true }) });

    await store.unpin('mod:x');

    expect(fetchMock).toHaveBeenCalledWith('/api/navigation/pinned-apps/items/mod%3Ax', { method: 'DELETE' });
    expect(store.isPinned('mod:x')).toBe(false);
  });

  it('saveTree() PUTs the full tree and adopts server ids', async () => {
    const tree: PinnedNode[] = [
      { id: 'local-1', nodeType: 'folder', name: 'F', children: [{ id: 'local-2', nodeType: 'item', ref: 'mod:a', children: [] }] },
    ];
    const serverTree: PinnedNode[] = [
      { id: 'uuid-1', nodeType: 'folder', name: 'F', children: [{ id: 'uuid-2', nodeType: 'item', ref: 'mod:a', children: [] }] },
    ];
    fetchMock.mockResolvedValue({ ok: true, json: async () => ({ tree: serverTree }) });

    const ok = await store.saveTree(tree);

    expect(ok).toBe(true);
    expect(fetchMock).toHaveBeenCalledWith('/api/navigation/pinned-apps', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(tree),
    });
    expect(store.pinnedTree()).toEqual(serverTree);
  });

  it('saveSidebar() shallow-merges the sidebar config', async () => {
    store.userSettings.set({
      sidebar: { showPinned: true, showWorkspaces: true, apps: ['mod:a'] },
      sidebarExpanded: [],
    });
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => ({
        settings: { sidebar: { showPinned: true, showWorkspaces: false, apps: ['mod:a'] }, sidebarExpanded: [] },
      }),
    });

    await store.saveSidebar({ showWorkspaces: false });

    expect(fetchMock).toHaveBeenCalledWith('/api/navigation/user-settings', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sidebar: { showWorkspaces: false } }),
    });
    expect(store.sidebar().showWorkspaces).toBe(false);
    expect(store.sidebar().apps).toEqual(['mod:a']);
  });

  it('saveFeatures() updates the signal from the server response', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => ({ pinnedAppsEnabled: false, workspacesEnabled: false }),
    });

    const ok = await store.saveFeatures({ pinnedAppsEnabled: false });

    expect(ok).toBe(true);
    expect(store.features()).toEqual({ pinnedAppsEnabled: false, workspacesEnabled: false });
  });

  it('togglePin() is a no-op when pinned apps are disabled', async () => {
    store.features.set({ pinnedAppsEnabled: false, workspacesEnabled: true });

    await store.togglePin('mod:x');

    expect(fetchMock).not.toHaveBeenCalled();
    expect(store.isPinned('mod:x')).toBe(false);
  });
});
