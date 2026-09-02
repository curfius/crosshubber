// ── Navigation state types & URL grammar ───────────────────────────────
//
// URL grammar (D1):
//   Home:      /?app=<tabKey>&path=<modulePath>
//   Workspace: /w/<encodeURIComponent(name)>?app=<tabKey>&path=<modulePath>
//
// `app`  = entryPointId[:instance] of the focused group's active tab
//          (entryPointId = `moduleKey:entryKey`, instance >= 2 for duplicates).
// `path` = module-internal path of that same tab. Both params are omitted
// when not applicable, producing clean URLs like `/w/orders`.

export interface NavState {
  ws: string | null;
  app: string | null;
  path: string | null;
}

/**
 * Structured payload carried in history.state on every entry written by
 * UrlSyncService (D2). The URL is the human-readable projection; this state
 * is the source of truth for restore.
 */
export interface PortalHistoryEntry extends NavState {
  v: 1;
  /** tabKey -> last known module path (session scope). */
  paths: Record<string, string>;
}

const WORKSPACE_RE = /^\/w\/(.+)$/;

export function parseWorkspaceFromPathname(pathname: string): string | null {
  const m = WORKSPACE_RE.exec(pathname);
  return m ? decodeURIComponent(m[1]) : null;
}

/** Parses the current URL into a NavState. Never null — absence of params is a valid state. */
export function parseNavState(loc: { pathname: string; search: string }): NavState {
  const params = new URLSearchParams(loc.search);
  return {
    ws: parseWorkspaceFromPathname(loc.pathname),
    app: params.get('app'),
    path: params.get('path'),
  };
}

/** Serializes a NavState to a path+query URL. Params omitted when not applicable. */
export function serializeNavUrl(state: NavState): string {
  const base = state.ws ? '/w/' + encodeURIComponent(state.ws) : '/';
  const params = new URLSearchParams();
  if (state.app) params.set('app', state.app);
  if (state.path) params.set('path', state.path);
  const q = params.toString();
  return q ? `${base}?${q}` : base;
}

/**
 * Legacy hash grammar (D9): `#<epId>[:n][/path]` where epId = `moduleKey:entryKey`.
 * Bare `moduleKey` hashes (the pre-fix bridge format) are tolerated too — the
 * coordinator resolves unknown app keys against `moduleKey:main` on cold load.
 */
export function parseLegacyNav(loc: { pathname: string; hash: string }): NavState | null {
  const h = loc.hash;
  if (!h || h.length <= 1) return null;
  const raw = h.substring(1);
  const slashIdx = raw.indexOf('/');
  const app = slashIdx >= 0 ? raw.substring(0, slashIdx) : raw;
  const path = slashIdx >= 0 ? raw.substring(slashIdx + 1) : '';
  return {
    ws: parseWorkspaceFromPathname(loc.pathname),
    app: app || null,
    path: path || null,
  };
}

/**
 * Stable session key for a tab: `moduleKey:entryKey`, or `moduleKey:entryKey:n`
 * for duplicated multi tabs. Used for the tabPaths map and the `app` URL param.
 */
export function tabKeyOf(epId: string, instance: number): string {
  return instance > 1 ? `${epId}:${instance}` : epId;
}
