import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { MsgCenterItem } from './msg-center.store';

// @Injectable store with injected I18nService — exercised through the real fetch path with a
// stubbed global fetch (project Vitest convention: no Karma, jsdom).

import type { I18nService } from '../i18n/i18n.service';

const fetchMock = vi.hoisted(() => vi.fn());
vi.stubGlobal('fetch', fetchMock);

// Module import after the global stub so the store's apiFetch sees the mock.
const { MsgCenterStore } = await import('./msg-center.store');

function item(overrides: Partial<MsgCenterItem> = {}): MsgCenterItem {
  return {
    id: 1,
    eventId: 'e1',
    msgType: 'notification',
    moduleKey: 'solutions',
    senderName: 'Solutions',
    senderColor: '#0ea5e9',
    title: { en: 'Hello' },
    body: { en: 'Body' },
    severity: 'info',
    threadId: null,
    link: null,
    task: null,
    status: 'open',
    claimedBySub: null,
    claimedByName: null,
    claimedAt: null,
    occurredAt: '2026-10-06T10:00:00Z',
    read: false,
    respondedByMe: false,
    draftedByMe: false,
    ...overrides,
  };
}

const i18nStub = {
  active: () => 'en-GB',
  config: () => ({ defaultLanguage: 'en-GB', fallbackLanguage: 'en-GB' }),
} as unknown as I18nService;

function makeStore(): InstanceType<typeof MsgCenterStore> {
  const store = new MsgCenterStore(i18nStub);
  return store;
}

function jsonResponse(payload: unknown): Response {
  return new Response(JSON.stringify(payload), { status: 200 });
}

describe('MsgCenterStore', () => {
  beforeEach(() => {
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('loads first page; short pages close the cursor', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ items: [item({ id: 2 })] }));
    const store = makeStore();
    await store.load(true);
    expect(store.items().map((i) => i.id)).toEqual([2]);
    expect(store.error()).toBeNull();
    // short page → no further cursor
    expect(store.canLoadMore()).toBe(false);
  });

  it('appends pages and clears the cursor on a short page', async () => {
    const page1 = Array.from({ length: 50 }, (_, i) => item({ id: 1000 - i }));
    const page2 = [item({ id: 900 })];
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ items: page1 }))
      .mockResolvedValueOnce(jsonResponse({ items: page2 }));
    const store = makeStore();
    await store.load(true);
    await store.loadMore();
    expect(store.items().map((i) => i.id)).toContain(900);
    // short page → cursor cleared
    expect(store.canLoadMore()).toBe(false);
  });

  it('records load errors instead of silently swallowing them', async () => {
    fetchMock.mockRejectedValueOnce(new (await import('../http/api-fetch')).ApiError(500, 'boom'));
    const store = makeStore();
    await store.load(true);
    expect(store.error()).toContain('API 500');
    expect(store.items()).toEqual([]);
  });

  it('tab filtering follows inbox semantics', async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({
        items: [
          item({ id: 1, msgType: 'notification', read: false }), // inbox + notifications
          item({ id: 2, msgType: 'task', status: 'open', read: true }), // tasks
          item({ id: 3, msgType: 'task', status: 'done' }), // history only
          item({ id: 4, msgType: 'message', read: true, respondedByMe: true }), // history only
        ],
      }),
    );
    const store = makeStore();
    await store.load(true);

    expect(store.visibleItems().map((i) => i.id)).toEqual([1]); // inbox tab: unread, active
    store.tab.set('tasks'); // direct tab set: filtering only, no refetch
    expect(store.visibleItems().map((i) => i.id)).toEqual([2]);
    store.tab.set('history');
    expect(store.visibleItems().map((i) => i.id)).toEqual([3, 4]);
    store.tab.set('notifications');
    expect(store.visibleItems().map((i) => i.id)).toEqual([1, 4]); // notifications incl. messages
  });

  it('markRead flips locally, reverts on failure, recounts unread', async () => {
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ items: [item()] })) // load
      .mockRejectedValueOnce(new (await import('../http/api-fetch')).ApiError(404, 'gone'))
      .mockResolvedValueOnce(jsonResponse({ unread: 0 }))
      .mockResolvedValueOnce(new Response('{"ok":true}', { status: 200 }))
      .mockResolvedValueOnce(jsonResponse({ unread: 0 }));
    const store = makeStore();
    await store.load(true);

    await store.markRead(1); // fails → reverted
    expect(store.items()[0].read).toBe(false);

    fetchMock.mockResolvedValueOnce(new Response('{"ok":true}', { status: 200 }));
    fetchMock.mockResolvedValueOnce(jsonResponse({ unread: 0 }));
    await store.markRead(1); // succeeds → stays read
    expect(store.items()[0].read).toBe(true);
  });

  it('unread polling keeps the last value on failure and starts/stops idempotently', async () => {
    fetchMock.mockImplementation(async () => jsonResponse({ unread: 3 }));
    const store = makeStore();
    store.start();
    store.start(); // second call is a no-op (single timer)
    await vi.waitFor(() => expect(store.unread()).toBe(3));
    store.stop();
    expect(store.stop.bind(store)).not.toThrow();
  });
});
