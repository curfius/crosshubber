import { TestBed } from '@angular/core/testing';
import { describe, it, expect, beforeAll, beforeEach, afterEach, vi } from 'vitest';
import { Bridge } from './bridge.service';
import { WorkbenchService } from '../../portal-core/features/workspaces/workspaces.store';
import type { PortalEntryPoint } from '../models';

const ORIGIN = window.location.origin;

let entryPoints: PortalEntryPoint[];
let warnSpy: ReturnType<typeof vi.spyOn>;

function ep(moduleKey: string, type: 'iframe' | 'mfe', rawUrl: string): PortalEntryPoint {
  return {
    moduleKey,
    entryKey: 'main',
    category: 'applications',
    name: moduleKey,
    type,
    url: type === 'iframe' ? rawUrl : undefined,
    entryUrl: type === 'mfe' ? rawUrl : undefined,
    parentEntryKey: null,
    groupKey: null,
    sortOrder: 0,
  };
}

function postMessage(data: unknown, origin: string): void {
  window.dispatchEvent(new MessageEvent('message', { data, origin }));
}

describe('Bridge security', () => {
  let bridge: Bridge;
  let navigated: { moduleKey: string; path: string }[];

  beforeAll(() => {
    TestBed.configureTestingModule({
      providers: [{ provide: WorkbenchService, useValue: { getEntryPoints: () => entryPoints } }],
    });
    bridge = TestBed.inject(Bridge);
    bridge.moduleNavigate.subscribe((n) => navigated.push(n));
  });

  beforeEach(() => {
    entryPoints = [];
    bridge.ready.clear();
    navigated = [];
    warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    warnSpy.mockRestore();
  });

  it('accepts a navigate from a registered same-origin iframe', () => {
    entryPoints = [ep('docs', 'iframe', `${ORIGIN}/docs/index.html`)];
    postMessage({ source: 'iframe:docs', type: 'portal:navigate', path: '/intro' }, ORIGIN);
    expect(navigated).toEqual([{ moduleKey: 'docs', path: '/intro' }]);
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('accepts a navigate from a registered cross-origin iframe', () => {
    entryPoints = [ep('wiki', 'iframe', 'https://wiki.example.com/home')];
    postMessage({ source: 'iframe:wiki', type: 'portal:navigate', path: '/page1' }, 'https://wiki.example.com');
    expect(navigated).toEqual([{ moduleKey: 'wiki', path: '/page1' }]);
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('rejects messages from an unregistered origin', () => {
    entryPoints = [ep('docs', 'iframe', `${ORIGIN}/docs/index.html`)];
    postMessage({ source: 'iframe:docs', type: 'portal:navigate', path: '/x' }, 'https://evil.example');
    expect(navigated).toEqual([]);
    expect(warnSpy).toHaveBeenCalled();
  });

  it('rejects "null" origins (sandboxed iframes)', () => {
    entryPoints = [ep('docs', 'iframe', `${ORIGIN}/docs/index.html`)];
    postMessage({ source: 'iframe:docs', type: 'portal:navigate', path: '/x' }, 'null');
    expect(navigated).toEqual([]);
    expect(warnSpy).toHaveBeenCalled();
  });

  it('rejects a module impersonating another module (origin/source mismatch)', () => {
    entryPoints = [
      ep('docs', 'iframe', `${ORIGIN}/docs/index.html`),
      ep('wiki', 'iframe', 'https://wiki.example.com/home'),
    ];
    postMessage({ source: 'iframe:wiki', type: 'portal:navigate', path: '/spoof' }, ORIGIN);
    expect(navigated).toEqual([]);
    expect(warnSpy).toHaveBeenCalled();
  });

  it('rejects messages without a parseable source', () => {
    entryPoints = [ep('docs', 'iframe', `${ORIGIN}/docs/index.html`)];
    postMessage({ type: 'portal:navigate', path: '/x' }, ORIGIN);
    postMessage({ source: 'garbage', type: 'portal:navigate', path: '/y' }, ORIGIN);
    expect(navigated).toEqual([]);
    expect(warnSpy).toHaveBeenCalledTimes(2);
  });

  it('rejects sources for modules that are not registered', () => {
    entryPoints = [ep('docs', 'iframe', `${ORIGIN}/docs/index.html`)];
    postMessage({ source: 'iframe:nope', type: 'portal:navigate', path: '/x' }, ORIGIN);
    expect(navigated).toEqual([]);
    expect(warnSpy).toHaveBeenCalled();
  });

  it('fires ready callbacks only for validated messages', () => {
    entryPoints = [ep('docs', 'iframe', `${ORIGIN}/docs/index.html`)];
    const cb = vi.fn();
    bridge.ready.add(cb);
    postMessage({ source: 'iframe:docs', type: 'portal:ready' }, ORIGIN);
    postMessage({ source: 'iframe:docs', type: 'portal:ready' }, 'https://evil.example');
    expect(cb).toHaveBeenCalledTimes(1);
  });

  it('truncates oversized navigate paths', () => {
    entryPoints = [ep('docs', 'iframe', `${ORIGIN}/docs/index.html`)];
    postMessage({ source: 'iframe:docs', type: 'portal:navigate', path: '/' + 'a'.repeat(5000) }, ORIGIN);
    expect(navigated.length).toBe(1);
    expect(navigated[0].path.length).toBeLessThanOrEqual(2048);
  });

  it('ignores non-portal messages silently', () => {
    postMessage({ foo: 1 }, 'https://evil.example');
    postMessage('some string', ORIGIN);
    expect(navigated).toEqual([]);
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('sendTo posts to the exact registered origin', () => {
    entryPoints = [ep('wiki', 'iframe', 'https://wiki.example.com/home')];
    const iframe = document.createElement('iframe');
    document.body.appendChild(iframe);
    const postSpy = vi.fn();
    Object.defineProperty(iframe, 'contentWindow', { value: { postMessage: postSpy } });
    bridge.registerIframe('wiki', iframe, 'https://wiki.example.com/home');
    const ok = bridge.sendTo('wiki', { type: 'portal:restore', path: '/x' });
    expect(ok).toBe(true);
    expect(postSpy).toHaveBeenCalledWith({ type: 'portal:restore', path: '/x' }, 'https://wiki.example.com');
    iframe.remove();
  });

  it('sendTo fails for unregistered modules instead of posting anywhere', () => {
    expect(bridge.sendTo('ghost', { type: 'portal:restore', path: '/x' })).toBe(false);
  });

  it('restoreModule dispatches the CustomEvent contract to MFEs', () => {
    const el = document.createElement('div');
    const events: CustomEvent[] = [];
    el.addEventListener('portal:restore', (e) => events.push(e as CustomEvent));
    bridge.registerMfe('louie', el);
    expect(bridge.restoreModule('louie', '/deep/path')).toBe(true);
    expect(events.length).toBe(1);
    expect(events[0].detail).toEqual({ path: '/deep/path' });
  });

  it('restoreModule reports false when the module is unknown', () => {
    expect(bridge.restoreModule('ghost', '/x')).toBe(false);
  });

  it('restoreModule uses postMessage for same-origin iframes without touching src', () => {
    entryPoints = [ep('docs', 'iframe', `${ORIGIN}/docs/index.html`)];
    const iframe = document.createElement('iframe');
    document.body.appendChild(iframe);
    const postSpy = vi.fn();
    Object.defineProperty(iframe, 'contentWindow', { value: { postMessage: postSpy } });
    bridge.registerIframe('docs', iframe, `${ORIGIN}/docs/index.html`);
    const before = iframe.src;
    expect(bridge.restoreModule('docs', '/intro')).toBe(true);
    expect(postSpy).toHaveBeenCalledWith({ source: 'portal', type: 'portal:restore', path: '/intro' }, ORIGIN);
    expect(iframe.src).toBe(before);
    iframe.remove();
  });

  it('restoreModule falls back to a src reload for cross-origin iframes', () => {
    entryPoints = [ep('wiki', 'iframe', 'https://wiki.example.com/home')];
    const iframe = document.createElement('iframe');
    document.body.appendChild(iframe);
    bridge.registerIframe('wiki', iframe, 'https://wiki.example.com/home');
    expect(bridge.restoreModule('wiki', '/page1')).toBe(true);
    expect(iframe.src).toBe('https://wiki.example.com/page1');
    iframe.remove();
  });

  it('handles portal:bye by unregistering the MFE element', () => {
    entryPoints = [ep('louie', 'mfe', `${ORIGIN}/mfe/louie.js`)];
    const el = document.createElement('div');
    bridge.registerMfe('louie', el);
    postMessage({ source: 'mfe:louie', type: 'portal:bye' }, ORIGIN);
    expect(bridge.restoreModule('louie', '/x')).toBe(false);
  });
});
