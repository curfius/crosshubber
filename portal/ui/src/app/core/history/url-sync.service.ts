import { Injectable } from '@angular/core';
import { parseLegacyNav, parseNavState, serializeNavUrl, type NavState, type PortalHistoryEntry } from './nav-state';

export interface NavRead {
  nav: NavState;
  /** tabKey -> last known module path snapshot carried by this history entry. */
  paths: Record<string, string>;
}

type PopHandler = (read: NavRead) => void;

/**
 * Single owner of the History API (D3): the ONLY file allowed to call
 * history.pushState/replaceState or listen to popstate. Every other file
 * goes through this service.
 *
 * The URL is the human-readable projection; history.state (PortalHistoryEntry)
 * is the source of truth for restore. When state is null (pasted URL, bfcache
 * edge), read() falls back to parsing the URL.
 */
@Injectable({ providedIn: 'root' })
export class UrlSyncService {
  private readonly handlers = new Set<PopHandler>();

  private readonly onPop = (): void => {
    const read = this.read();
    for (const h of this.handlers) h(read);
  };

  constructor() {
    window.addEventListener('popstate', this.onPop);
  }

  /** Current navigation state: structured history entry when present, URL parse otherwise. */
  read(): NavRead {
    const state = history.state as PortalHistoryEntry | null;
    if (state && state.v === 1) {
      return {
        nav: { ws: state.ws ?? null, app: state.app ?? null, path: state.path ?? null },
        paths: { ...(state.paths ?? {}) },
      };
    }
    return { nav: parseNavState(location), paths: {} };
  }

  push(nav: NavState, paths: Record<string, string> = {}): void {
    history.pushState(this.entry(nav, paths), '', serializeNavUrl(nav));
  }

  replace(nav: NavState, paths: Record<string, string> = {}): void {
    history.replaceState(this.entry(nav, paths), '', serializeNavUrl(nav));
  }

  /** Subscribes to popstate; returns an unsubscribe function. */
  onPopState(handler: PopHandler): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  /** One-time legacy hash conversion (D9) — evaluated on cold load only. */
  readLegacyHash(): NavState | null {
    return parseLegacyNav(location);
  }

  private entry(nav: NavState, paths: Record<string, string>): PortalHistoryEntry {
    return { v: 1, ws: nav.ws, app: nav.app, path: nav.path, paths: { ...paths } };
  }
}
