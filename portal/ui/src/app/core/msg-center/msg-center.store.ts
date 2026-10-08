import { computed, Injectable, signal } from '@angular/core';

import { apiFetch, ApiError } from '../http/api-fetch';
import { I18nService } from '../i18n/i18n.service';

/** Task state on the message row (server contract: open | claimed | done). */
export type MsgCenterStatus = 'open' | 'claimed' | 'done';
export type MsgCenterType = 'notification' | 'message' | 'task';

/** Envelope i18n map (en required, other languages optional). */
export type I18nMap = Record<string, string>;

export interface MsgCenterBodySection {
  title?: I18nMap;
  text?: I18nMap;
}

export interface MsgCenterItem {
  id: number;
  eventId: string;
  msgType: MsgCenterType;
  moduleKey: string;
  senderName: string | null;
  senderColor: string | null;
  title: I18nMap | null;
  body: (I18nMap & { sections?: MsgCenterBodySection[] }) | null;
  severity: string | null;
  threadId: string | null;
  link: { moduleKey: string; path: string } | null;
  task: Record<string, unknown> | null;
  status: MsgCenterStatus;
  claimedBySub: string | null;
  claimedByName: string | null;
  claimedAt: string | null;
  occurredAt: string;
  read: boolean;
  respondedByMe: boolean;
  draftedByMe: boolean;
}

export type MsgCenterTab = 'inbox' | 'notifications' | 'tasks' | 'history';

/** Group row for the browse list (server DTO). */
export interface MsgCenterGroup {
  id: number;
  key: string;
  name: string;
  visibility: 'open' | 'closed';
  retired: boolean;
  owners: string[];
  memberCount: number;
  myMembership: boolean;
  iAmOwner: boolean;
  emailFlag: boolean;
}

const POLL_INTERVAL_MS = 15_000;

/**
 * Message-center store (plan section 9): signal-first, 15s badge polling (no SSE in v1).
 *
 * Error policy is explicit: load failures surface in `error` (the UI shows a retry state) and
 * the unread badge keeps its last known value — a transient backend hiccup must not blank the
 * badge or silently swallow `!res.ok` (apiFetch throws, we catch and record).
 */
@Injectable({ providedIn: 'root' })
export class MsgCenterStore {
  private readonly i18n: I18nService;

  constructor(i18n: I18nService) {
    this.i18n = i18n;
  }

  readonly unread = signal<number>(0);
  readonly items = signal<MsgCenterItem[]>([]);
  readonly tab = signal<MsgCenterTab>('inbox');
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  /** Current user's sub — claimedBySub comparison for claim-mode UX. */
  readonly mySub = signal<string | null>(null);

  private cursor: { at: string; id: number } | null = null;
  private pollTimer: ReturnType<typeof setInterval> | null = null;

  /** Items visible under the active tab (tab semantics are client-side). */
  readonly visibleItems = computed(() => {
    const tab = this.tab();
    return this.items().filter((item) => this.matchesTab(item, tab));
  });

  readonly canLoadMore = computed(() => this.cursor !== null);

  /** Starts unread polling; safe to call repeatedly (idempotent). */
  start(): void {
    if (this.pollTimer !== null) return;
    void this.captureIdentity();
    void this.refreshUnread();
    this.pollTimer = setInterval(() => void this.refreshUnread(), POLL_INTERVAL_MS);
  }

  /** One-shot identity capture for claim-state comparison (fail-soft default null). */
  private async captureIdentity(): Promise<void> {
    try {
      const res = await apiFetch('/api/config');
      const payload = (await res.json()) as { user?: { sub?: string } };
      this.mySub.set(payload.user?.sub ?? null);
    } catch {
      // documented default: unknown identity → claimedBy comparisons fall back to server truth
    }
  }

  stop(): void {
    if (this.pollTimer !== null) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
  }

  /** Claims a claim-mode task; returns the takeover draft offer (may be empty). */
  async claimTask(
    id: number,
  ): Promise<{ fromName: string | null; savedAt: string | null; draft: Record<string, unknown> }> {
    const res = await apiFetch(`/api/msgcenter/tasks/${id}/claim`, { method: 'POST' });
    const payload = (await res.json()) as {
      fromName: string | null;
      savedAt: string | null;
      draft: Record<string, unknown>;
    };
    this.items.update((items) =>
      items.map((item) =>
        item.id === id
          ? {
              ...item,
              status: 'claimed' as const,
              claimedBySub: this.mySub(),
              claimedByName: null,
            }
          : item,
      ),
    );
    await this.refreshUnread();
    return payload;
  }

  /** Submits a response; returns whether the task closed. */
  async respondTask(
    id: number,
    outcome: string,
    data: Record<string, unknown> | null,
    note: string | null,
  ): Promise<{ closed: boolean; status: string }> {
    const res = await apiFetch(`/api/msgcenter/tasks/${id}/respond`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ outcome, data, note }),
    });
    const payload = (await res.json()) as { closed: boolean; status: string };
    this.items.update((items) =>
      items.map((item) =>
        item.id === id
          ? {
              ...item,
              read: true,
              status: payload.status as MsgCenterStatus,
              respondedByMe: true,
            }
          : item,
      ),
    );
    await this.refreshUnread();
    return payload;
  }

  /** Steps back from a claimed task; drafts stay for takeover unless discard. */
  async releaseTask(id: number, discardDraft: boolean): Promise<void> {
    await apiFetch(`/api/msgcenter/tasks/${id}/release`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ discardDraft }),
    });
    this.items.update((items) =>
      items.map((item) =>
        item.id === id
          ? { ...item, status: 'open' as const, claimedBySub: null, claimedByName: null }
          : item,
      ),
    );
    await this.refreshUnread();
  }

  /** Back to open: clears claim + drafts (audit rows survive server-side). */
  async resetTask(id: number): Promise<void> {
    await apiFetch(`/api/msgcenter/tasks/${id}/reset`, { method: 'POST' });
    this.items.update((items) =>
      items.map((item) =>
        item.id === id
          ? {
              ...item,
              status: 'open' as const,
              claimedBySub: null,
              claimedByName: null,
              draftedByMe: false,
            }
          : item,
      ),
    );
    await this.refreshUnread();
  }

  async saveDraftTask(
    id: number,
    data: Record<string, unknown>,
    note: string | null,
  ): Promise<void> {
    await apiFetch(`/api/msgcenter/tasks/${id}/draft`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ data, note }),
    });
    this.items.update((items) =>
      items.map((item) => (item.id === id ? { ...item, draftedByMe: true } : item)),
    );
  }

  async discardDraftTask(id: number): Promise<void> {
    await apiFetch(`/api/msgcenter/tasks/${id}/draft`, { method: 'DELETE' });
    this.items.update((items) =>
      items.map((item) => (item.id === id ? { ...item, draftedByMe: false } : item)),
    );
  }

  async adoptDraftTask(id: number): Promise<Record<string, unknown>> {
    const res = await apiFetch(`/api/msgcenter/tasks/${id}/draft/adopt`, { method: 'POST' });
    const payload = (await res.json()) as { data: Record<string, unknown> };
    return payload.data ?? {};
  }

  // ── Groups (membership = subscription) ────────────────────────────────

  readonly groups = signal<MsgCenterGroup[]>([]);
  readonly groupsLoading = signal(false);

  async loadGroups(): Promise<void> {
    this.groupsLoading.set(true);
    try {
      const res = await apiFetch('/api/msgcenter/groups');
      const payload = (await res.json()) as { groups: MsgCenterGroup[] };
      this.groups.set(payload.groups ?? []);
    } catch (err) {
      this.error.set(err instanceof ApiError ? `API ${err.status}` : String(err));
    } finally {
      this.groupsLoading.set(false);
    }
  }

  async joinGroup(key: string): Promise<void> {
    await apiFetch(`/api/msgcenter/groups/${key}/join`, { method: 'POST' });
    await this.loadGroups();
  }

  async leaveGroup(key: string): Promise<void> {
    await apiFetch(`/api/msgcenter/groups/${key}/leave`, { method: 'POST' });
    await this.loadGroups();
  }

  selectTab(tab: MsgCenterTab): void {
    this.tab.set(tab);
    void this.load(true);
  }

  /** (Re)loads the first page for the active tab. */
  async load(reset: boolean): Promise<void> {
    if (reset) {
      this.cursor = null;
      this.items.set([]);
    }
    this.loading.set(true);
    this.error.set(null);
    try {
      const query = new URLSearchParams();
      const type = this.tabType(this.tab());
      if (type) query.set('type', type);
      if (this.cursor) {
        query.set('cursorAt', this.cursor.at);
        query.set('cursorId', String(this.cursor.id));
      }
      query.set('limit', '50');
      const res = await apiFetch(`/api/msgcenter/messages?${query.toString()}`);
      const payload = (await res.json()) as { items: MsgCenterItem[] };
      const incoming = payload.items ?? [];
      this.items.update((current) => (reset ? incoming : [...current, ...incoming]));
      this.updateCursor(incoming);
    } catch (err) {
      this.error.set(err instanceof ApiError ? `API ${err.status}` : String(err));
    } finally {
      this.loading.set(false);
    }
  }

  /** Loads the next cursor page (no-op while a load is running). */
  async loadMore(): Promise<void> {
    if (this.cursor === null || this.loading()) return;
    await this.load(false);
  }

  /** Marks one message read; optimistic local flip, unread recount afterwards. */
  async markRead(id: number): Promise<void> {
    this.items.update((items) =>
      items.map((item) => (item.id === id ? { ...item, read: true } : item)),
    );
    try {
      await apiFetch(`/api/msgcenter/messages/${id}/read`, { method: 'POST' });
      await this.refreshUnread();
    } catch (err) {
      // revert the optimistic flip so the badge/list stay truthful
      this.items.update((items) =>
        items.map((item) => (item.id === id ? { ...item, read: false } : item)),
      );
      this.error.set(err instanceof ApiError ? `API ${err.status}` : String(err));
    }
  }

  async refreshUnread(): Promise<void> {
    try {
      const res = await apiFetch('/api/msgcenter/unread');
      const payload = (await res.json()) as { unread: number };
      this.unread.set(payload.unread ?? 0);
    } catch {
      // documented default: badge keeps the last value on transient failures
    }
  }

  /** Picks the best language value from an envelope i18n map (active > fallback > default > en). */
  pickI18n(map: I18nMap | null | undefined): string {
    if (!map) return '';
    const order = [
      this.i18n.active(),
      this.i18n.config()?.fallbackLanguage,
      this.i18n.config()?.defaultLanguage,
      'en',
    ];
    for (const code of order) {
      const value = code ? map[code] : undefined;
      if (value) return value;
    }
    return Object.values(map)[0] ?? '';
  }

  private updateCursor(incoming: MsgCenterItem[]): void {
    if (incoming.length < 50) {
      this.cursor = null;
      return;
    }
    const last = incoming[incoming.length - 1];
    this.cursor = { at: last.occurredAt, id: last.id };
  }

  private tabType(tab: MsgCenterTab): string | null {
    if (tab === 'notifications') return 'notification';
    if (tab === 'tasks') return 'task';
    return null;
  }

  private matchesTab(item: MsgCenterItem, tab: MsgCenterTab): boolean {
    if (tab === 'inbox') {
      return !item.read && item.status !== 'done' && !item.respondedByMe;
    }
    if (tab === 'notifications') return item.msgType === 'notification' || item.msgType === 'message';
    if (tab === 'tasks') return item.msgType === 'task' && item.status !== 'done';
    return item.status === 'done' || item.respondedByMe;
  }
}
