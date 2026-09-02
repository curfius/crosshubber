import { describe, it, expect, beforeEach, vi } from 'vitest';
import { UrlSyncService } from './url-sync.service';
import { parseLegacyNav, parseNavState, serializeNavUrl, tabKeyOf, type NavState } from './nav-state';

const PATHS = { 'docs:main': '/intro' };

function replaceUrl(url: string): void {
  history.replaceState(null, '', url);
}

describe('serializeNavUrl', () => {
  it('produces clean URLs when nothing applies', () => {
    expect(serializeNavUrl({ ws: null, app: null, path: null })).toBe('/');
  });

  it('encodes workspace names', () => {
    expect(serializeNavUrl({ ws: 'orders', app: null, path: null })).toBe('/w/orders');
  });

  it('omits absent params', () => {
    expect(serializeNavUrl({ ws: null, app: 'docs:main', path: null })).toBe('/?app=docs%3Amain');
    expect(serializeNavUrl({ ws: 'w1', app: null, path: '/intro' })).toBe('/w/w1?path=%2Fintro');
  });
});

describe('parseNavState', () => {
  it('round-trips workspace names with special characters', () => {
    const url = serializeNavUrl({ ws: 'a b:c#d', app: null, path: null });
    replaceUrl(url);
    expect(parseNavState(location)).toEqual({ ws: 'a b:c#d', app: null, path: null });
  });

  it('round-trips app and module path with special characters', () => {
    const url = serializeNavUrl({ ws: null, app: 'docs:main', path: '/a/b c' });
    replaceUrl(url);
    expect(parseNavState(location)).toEqual({ ws: null, app: 'docs:main', path: '/a/b c' });
  });
});

describe('tabKeyOf', () => {
  it('appends the instance only for duplicates', () => {
    expect(tabKeyOf('docs:main', 1)).toBe('docs:main');
    expect(tabKeyOf('docs:main', 2)).toBe('docs:main:2');
  });
});

describe('parseLegacyNav', () => {
  it('returns null without a hash', () => {
    expect(parseLegacyNav({ pathname: '/', hash: '' })).toBeNull();
  });

  it('parses a bare epId', () => {
    expect(parseLegacyNav({ pathname: '/', hash: '#docs:main' })).toEqual({
      ws: null, app: 'docs:main', path: null,
    });
  });

  it('parses an instance suffix', () => {
    expect(parseLegacyNav({ pathname: '/w/w1', hash: '#docs:main:2' })).toEqual({
      ws: 'w1', app: 'docs:main:2', path: null,
    });
  });

  it('parses epId with module path', () => {
    expect(parseLegacyNav({ pathname: '/', hash: '#docs:main/intro' })).toEqual({
      ws: null, app: 'docs:main', path: 'intro',
    });
  });

  it('parses instance suffix with nested module path', () => {
    expect(parseLegacyNav({ pathname: '/', hash: '#docs:main:2/intro/inner' })).toEqual({
      ws: null, app: 'docs:main:2', path: 'intro/inner',
    });
  });

  it('tolerates the legacy bare-moduleKey bridge format', () => {
    expect(parseLegacyNav({ pathname: '/', hash: '#wiki/page1' })).toEqual({
      ws: null, app: 'wiki', path: 'page1',
    });
  });
});

describe('UrlSyncService', () => {
  let svc: UrlSyncService;

  beforeEach(() => {
    replaceUrl('/');
    svc = new UrlSyncService();
  });

  it('push writes both the query URL and structured state', () => {
    const nav: NavState = { ws: 'orders', app: 'docs:main', path: '/intro' };
    svc.push(nav, PATHS);
    expect(location.pathname).toBe('/w/orders');
    expect(location.search).toBe('?app=docs%3Amain&path=%2Fintro');
    const read = svc.read();
    expect(read.nav).toEqual(nav);
    expect(read.paths).toEqual(PATHS);
  });

  it('replace normalizes the current entry without adding history steps', () => {
    replaceUrl('/w/orders?app=docs%3Amain&path=%2Fintro');
    const nav = svc.read().nav;
    svc.replace(nav, PATHS);
    expect(svc.read()).toEqual({ nav, paths: PATHS });
  });

  it('falls back to URL parsing when history.state is null', () => {
    replaceUrl('/w/foo?app=a:b&path=%2Fx');
    expect(svc.read()).toEqual({
      nav: { ws: 'foo', app: 'a:b', path: '/x' },
      paths: {},
    });
  });

  it('push keeps the path unchanged except for the workspace part', () => {
    svc.push({ ws: null, app: 'a:b', path: null });
    expect(location.pathname).toBe('/');
    svc.push({ ws: 'w1', app: 'a:b', path: null });
    expect(location.pathname).toBe('/w/w1');
  });

  it('notifies popstate handlers with the restored entry', async () => {
    svc.push({ ws: null, app: 'a:b', path: '/first' }, {});
    svc.push({ ws: null, app: 'a:b', path: '/second' }, {});
    const fired = new Promise((resolve) => svc.onPopState(resolve));
    history.back();
    const read = (await fired) as { nav: NavState; paths: Record<string, string> };
    expect(read.nav.path).toBe('/first');
  });

  it('onPopState returns an unsubscribe function', async () => {
    svc.push({ ws: null, app: 'a:b', path: '/first' }, {});
    svc.push({ ws: null, app: 'a:b', path: '/second' }, {});
    const handler = vi.fn();
    const off = svc.onPopState(handler);
    off();
    history.back();
    await new Promise((r) => setTimeout(r, 20));
    expect(handler).not.toHaveBeenCalled();
  });

  it('readLegacyHash exposes the legacy grammar', () => {
    replaceUrl('/w/orders#docs:main:2/intro');
    expect(svc.readLegacyHash()).toEqual({ ws: 'orders', app: 'docs:main:2', path: 'intro' });
    replaceUrl('/w/orders');
    expect(svc.readLegacyHash()).toBeNull();
  });
});
