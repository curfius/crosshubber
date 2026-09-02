import { Injectable, inject } from '@angular/core';
import { Subject } from 'rxjs';
import { WorkbenchService } from '../../portal-core/features/workspaces/workspaces.store';

export interface BridgeMessage {
  source?: string;
  type: string;
  [key: string]: unknown;
}

const SOURCE_RE = /^(?:mfe|iframe):(.+)$/;
const MAX_PATH_LENGTH = 2048;

/**
 * Contract between the portal shell and modules (iframes / micro-frontends).
 * Messages flow module -> portal over postMessage:
 *   { source: 'mfe:<moduleKey>', type: 'portal:ready' }
 *   { source: 'mfe:<moduleKey>', type: 'portal:navigate', path }
 *   { source: 'mfe:<moduleKey>', type: 'portal:bye' }
 *   { source: 'iframe:<moduleKey>', type: 'portal:navigate', path }
 *     (same-origin iframes get this tracking via the injected agent script —
 *     core/bridge/iframe-agent.ts; cross-origin iframes must post it themselves)
 *
 * Portal dispatches to modules:
 *   { source: 'portal', type: 'portal:restore', path }  — CustomEvent on the
 *   MFE element, or sendTo(moduleKey, msg) postMessage targeted at the module
 *   origin (same-origin iframes: no reload; cross-origin iframes: src reload)
 *   { source: 'portal', type: 'portal:language', language, locale }
 *   (broadcastLanguage — every registered iframe/MFE, on language change and
 *   on portal:ready)
 *
 * Module navigation reports are handed to the NavigationCoordinator
 * (portal-core/features/navigation-coordinator.service.ts), which owns all
 * URL/history policy — the Bridge never touches the History API.
 *
 * Modules receive their INITIAL language without messaging: the portal appends
 * `?lang=<code>` to iframe URLs at creation and passes `language` in the MFE
 * mount() context. Modules own their translations — the portal only tells them
 * which language to render in.
 *
 * Security model:
 *   - Messages whose event.origin is neither this portal's own origin nor the
 *     registered url/entryUrl origin of some entry point are rejected.
 *   - The `source` field must parse as '<kind>:<moduleKey>' and that moduleKey
 *     must be registered for the sending origin — one module cannot
 *     impersonate another.
 *   - Outgoing postMessage always targets the exact expected origin, never '*'.
 */
@Injectable({ providedIn: 'root' })
export class Bridge {
  readonly ready = new Set<() => void>();

  /** Validated module navigation reports (moduleKey, path) for the NavigationCoordinator. */
  readonly moduleNavigate = new Subject<{ moduleKey: string; path: string }>();

  private readonly wb = inject(WorkbenchService);

  private mfeElements = new Map<string, HTMLElement>();
  private iframes = new Map<string, HTMLIFrameElement>();
  private iframeUrls = new Map<string, string>();

  constructor() {
    window.addEventListener('message', (event) => this.handle(event));
  }

  registerMfe(moduleKey: string, el: HTMLElement): void {
    this.mfeElements.set(moduleKey, el);
  }

  unregisterMfe(moduleKey: string): void {
    this.mfeElements.delete(moduleKey);
  }

  registerIframe(moduleKey: string, iframe: HTMLIFrameElement, baseUrl: string): void {
    this.iframes.set(moduleKey, iframe);
    this.iframeUrls.set(moduleKey, baseUrl);
  }

  unregisterIframe(moduleKey: string): void {
    this.iframes.delete(moduleKey);
    this.iframeUrls.delete(moduleKey);
  }

  /**
   * Restores a module to the given internal path.
   *   - MFE: portal:restore CustomEvent on the element (in-document).
   *   - iframe, same-origin: postMessage portal:restore — no reload (D7).
   *   - iframe, cross-origin: iframe.src reload fallback (documented
   *     limitation: may require two Back presses in the joint session).
   *
   * Returns whether a known module target received the restore.
   */
  restoreModule(moduleKey: string, path: string): boolean {
    const el = this.mfeElements.get(moduleKey);
    if (el) {
      el.dispatchEvent(new CustomEvent('portal:restore', { detail: { path }, bubbles: true, composed: true }));
      return true;
    }
    const iframe = this.iframes.get(moduleKey);
    if (iframe) {
      const base = this.iframeUrls.get(moduleKey) ?? '';
      let origin: string;
      try {
        origin = new URL(base, window.location.origin).origin;
      } catch {
        return false;
      }
      if (origin === window.location.origin) {
        return this.sendTo(moduleKey, { source: 'portal', type: 'portal:restore', path });
      }
      let target: string;
      try {
        target = new URL(path, base).toString();
      } catch {
        target = base.replace(/\/+$/, '') + '/' + path.replace(/^\//, '');
      }
      try {
        const current = iframe.src;
        if (current && new URL(current).href === new URL(target).href) return true;
      } catch {
        if (iframe.src === target) return true;
      }
      iframe.src = target;
      return true;
    }
    return false;
  }

  /** Posts a message to a registered iframe using its expected origin as targetOrigin. */
  sendTo(moduleKey: string, payload: BridgeMessage): boolean {
    const iframe = this.iframes.get(moduleKey);
    if (!iframe?.contentWindow) return false;
    let origin: string;
    try {
      origin = new URL(this.iframeUrls.get(moduleKey) ?? '', window.location.origin).origin;
    } catch {
      return false;
    }
    iframe.contentWindow.postMessage(payload, origin);
    return true;
  }

  /**
   * Tells every registered module which language to render in. Called by
   * I18nService on explicit language change and when a module signals
   * portal:ready (late-loading modules get the current language).
   */
  broadcastLanguage(code: string): void {
    for (const moduleKey of this.iframes.keys()) {
      this.sendTo(moduleKey, { source: 'portal', type: 'portal:language', language: code, locale: code });
    }
    for (const el of this.mfeElements.values()) {
      el.dispatchEvent(new CustomEvent('portal:language', { detail: { language: code }, bubbles: true, composed: true }));
    }
  }

  private handle(event: MessageEvent): void {
    const data = event.data as BridgeMessage | undefined;
    if (!data || typeof data.type !== 'string' || !data.type.startsWith('portal:')) return;

    if (!this.isOriginAllowed(event.origin)) {
      console.warn(`[bridge] rejected message of type "${data.type}" from unauthorized origin "${event.origin}"`);
      return;
    }

    let moduleKey = '';
    if (typeof data.source === 'string') {
      const m = SOURCE_RE.exec(data.source);
      if (m) moduleKey = m[1];
    }
    if (!moduleKey) {
      console.warn(`[bridge] rejected message of type "${data.type}" without a valid "source" field`);
      return;
    }
    if (!this.moduleAllowsOrigin(moduleKey, event.origin)) {
      console.warn(`[bridge] rejected message: module "${moduleKey}" is not registered for origin "${event.origin}"`);
      return;
    }

    switch (data.type) {
      case 'portal:ready':
        this.ready.forEach((cb) => cb());
        break;
      case 'portal:bye':
        this.unregisterMfe(moduleKey);
        break;
      case 'portal:logout':
        window.location.href = '/logout';
        break;
      case 'portal:navigate':
        this.handleNavigate(data, moduleKey);
        break;
      default:
        break;
    }
  }

  private isOriginAllowed(origin: string): boolean {
    if (!origin || origin === 'null') return false;
    if (origin === window.location.origin) return true;
    for (const ep of this.wb.getEntryPoints()) {
      const raw = ep.type === 'iframe' ? ep.url : ep.entryUrl;
      if (!raw) continue;
      try {
        if (new URL(raw, window.location.origin).origin === origin) return true;
      } catch {
        // invalid registered URL — cannot grant any origin
      }
    }
    return false;
  }

  private moduleAllowsOrigin(moduleKey: string, origin: string): boolean {
    const portal = window.location.origin;
    let registered = false;
    for (const ep of this.wb.getEntryPoints()) {
      if (ep.moduleKey !== moduleKey) continue;
      if (ep.type !== 'iframe' && ep.type !== 'mfe') continue;
      registered = true;
      const raw = ep.type === 'iframe' ? ep.url : ep.entryUrl;
      let epOrigin: string | null = null;
      if (raw) {
        try {
          epOrigin = new URL(raw, portal).origin;
        } catch {
          // invalid registered URL
        }
      }
      if (epOrigin !== null && epOrigin === origin) return true;
      // Senders running inside this very document: MFE bundles execute
      // in-document (often via the /api/mfe proxy) and same-origin iframe
      // monitors post with the portal origin. A cross-origin iframe must
      // present its own origin instead.
      if (origin === portal && (ep.type === 'mfe' || epOrigin === portal)) return true;
    }
    return false;
  }

  /** Hands the validated navigation report to the coordinator (no history access here). */
  private handleNavigate(data: BridgeMessage, moduleKey: string): void {
    let path = typeof data['path'] === 'string' ? data['path'] : '';
    path = path.trim().slice(0, MAX_PATH_LENGTH);
    if (!path) return;
    this.moduleNavigate.next({ moduleKey, path });
  }
}
