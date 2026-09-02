import {
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  input,
  Renderer2,
  signal,
  viewChild,
  ViewContainerRef,
  type AfterViewInit,
  type OnDestroy,
} from '@angular/core';
import { Bridge } from '../../core/bridge/bridge.service';
import { buildIframeAgentScript } from '../../core/bridge/iframe-agent';
import { I18nService } from '../../core/i18n/i18n.service';
import { NavigationCoordinator } from '../features/navigation-coordinator.service';
import { embeddedModules } from './embedded-modules';
import type { PortalEntryPoint, PortalUser } from '../../core/models';
import { entryPointId } from '../../core/models';

const loadedScripts = new Map<string, Promise<void>>();

function mfeScriptUrl(ep: PortalEntryPoint): string {
  const entry = new URL(ep.entryUrl!, window.location.origin);
  if (entry.origin === window.location.origin) return ep.entryUrl!;
  return `/api/mfe/${ep.moduleKey}${entry.pathname}`;
}

/**
 * True when the URL would frame the portal's own app (recursive shell, auth
 * pages, API) — always blocked. Other same-origin URLs are legitimate module
 * hosts and are allowed (agent-based tracking, reload-free restores).
 */
function isSelfFraming(rawUrl: string): boolean {
  try {
    const u = new URL(rawUrl, window.location.origin);
    if (u.origin !== window.location.origin) return false;
    return (
      u.pathname === '/' ||
      u.pathname === '/index.html' ||
      u.pathname === '/login' ||
      u.pathname === '/logout' ||
      /^\/w(\/|$)/.test(u.pathname) ||
      /^\/api(\/|$)/.test(u.pathname)
    );
  } catch {
    return false;
  }
}

@Component({
  selector: 'app-outlet',
  imports: [],
  templateUrl: './module-outlet.component.html',
  styleUrl: './module-outlet.component.css',
})
export class AppOutlet implements AfterViewInit, OnDestroy {
  private readonly hostVcr = viewChild('host', { read: ViewContainerRef });
  private readonly hostEl = viewChild.required<ElementRef<HTMLElement>>('host');
  private readonly iframeHost = viewChild<ElementRef<HTMLElement>>('iframeHost');
  private readonly bridge = inject(Bridge);
  private readonly coordinator = inject(NavigationCoordinator);
  protected readonly i18n = inject(I18nService);
  private readonly renderer = inject(Renderer2);
  readonly entryPoint = input.required<PortalEntryPoint>();
  readonly user = input<PortalUser | null>(null);

  protected readonly loading = signal(true);
  protected readonly isIframe = computed(() => this.entryPoint().type === 'iframe');
  protected readonly iframeBlocked = computed(() => {
    const ep = this.entryPoint();
    return ep.type === 'iframe' && !!ep.url && isSelfFraming(ep.url);
  });

  private mounted = false;
  private element: (HTMLElement & { mount?: unknown; unmount?: () => void }) | null = null;
  private previousEpId = '';
  private viewReady = false;

  constructor() {
    effect(() => {
      const ep = this.entryPoint();
      const epId = entryPointId(ep);
      if (this.viewReady && epId !== this.previousEpId) {
        this.previousEpId = epId;
        this.cleanup();
        this.loading.set(true);
        this.render();
      }
    });
  }

  ngAfterViewInit(): void {
    this.previousEpId = entryPointId(this.entryPoint());
    this.viewReady = true;
    this.render();
  }

  ngOnDestroy(): void {
    this.cleanup();
    this.hostVcr()?.clear();
  }

  private cleanup(): void {
    const ep = this.entryPoint();
    if (ep.type === 'mfe') {
      this.bridge.unregisterMfe(ep.moduleKey);
    }
    if (ep.type === 'iframe') {
      this.bridge.unregisterIframe(ep.moduleKey);
    }
    // Remove dynamically created iframe
    const iframeHost = this.iframeHost()?.nativeElement;
    if (iframeHost) {
      const iframe = iframeHost.querySelector('iframe');
      if (iframe) iframe.remove();
    }
    if (this.mounted) {
      this.element?.unmount?.();
      this.element?.remove();
      this.mounted = false;
      this.element = null;
    }
  }

  private render(): void {
    const ep = this.entryPoint();
    if (ep.type === 'iframe') {
      this.renderIframe();
    } else if (ep.type === 'embedded') {
      this.renderEmbedded(ep.loadPath!);
    } else if (ep.type === 'mfe') {
      void this.renderMfe(ep);
    }
  }

  private renderIframe(): void {
    const ep = this.entryPoint();
    if (!ep.url || isSelfFraming(ep.url)) {
      console.error(`[portal] blocked iframe "${ep.moduleKey}/${ep.entryKey}" — url frames the portal app: ${ep.url}`);
      this.loading.set(false);
      return;
    }
    const host = this.iframeHost()?.nativeElement;
    if (!host) return;

    const iframe = this.renderer.createElement('iframe') as HTMLIFrameElement;
    // Use native DOM property directly — ep.url is already validated by backend (z.url() must be absolute http/https)
    // and isSelfFraming blocks portal self-framing. Using setAttribute with SafeResourceUrl would produce the
    // "SafeValue must use [property]=binding" warning string instead of the real URL.
    // The active language is appended as ?lang= so the module renders in the
    // right locale from first paint; subsequent changes arrive via bridge.
    // A pending restore path (deep link that arrived before this iframe
    // mounted) is baked into the initial src (D11).
    const pending = this.coordinator.consumePendingPath(ep.moduleKey);
    const target = pending ? this.resolveModuleUrl(ep.url, pending) : ep.url;
    iframe.src = this.withLanguageParam(target);
    this.renderer.setAttribute(iframe, 'title', ep.name);
    this.renderer.addClass(iframe, 'h-full');
    this.renderer.addClass(iframe, 'w-full');
    this.renderer.addClass(iframe, 'border-0');
    this.renderer.addClass(iframe, 'bg-white');

    const sandbox = ep.sandbox;
    if (sandbox && sandbox.length > 0) {
      this.renderer.setAttribute(iframe, 'sandbox', sandbox.join(' '));
    }
    this.renderer.setAttribute(iframe, 'allow', ep.allow ?? 'clipboard-write; fullscreen');

    this.renderer.listen(iframe, 'load', () => this.onIframeLoad());
    this.renderer.appendChild(host, iframe);
  }

  private renderEmbedded(loadPath: string): void {
    const loader = embeddedModules[loadPath];
    if (!loader) {
      console.error(`[portal] no embedded module registered for loadPath "${loadPath}"`);
      this.loading.set(false);
      return;
    }
    loader()
      .then((type) => {
        this.hostVcr()?.clear();
        this.hostVcr()?.createComponent(type);
        this.loading.set(false);
      })
      .catch((err) => {
        console.error(`[portal] failed to load embedded module "${loadPath}":`, err);
        this.loading.set(false);
      });
  }

  private async renderMfe(ep: PortalEntryPoint): Promise<void> {
    try {
      await this.ensureScript(mfeScriptUrl(ep));
      await Promise.race([
        customElements.whenDefined(ep.element!),
        new Promise((r) => setTimeout(r, 4000)),
      ]);
      if (this.mounted) return;
      const el = document.createElement(ep.element!) as HTMLElement & {
        mount?: (ctx: { user: PortalUser | null; language: string }) => void;
        unmount?: () => void;
      };
      this.element = el;
      el.addEventListener('mfe:ready', () => this.loading.set(false), { once: true });
      el.mount?.({ user: this.user(), language: this.i18n.locale() });
      this.hostEl().nativeElement.appendChild(el);
      this.bridge.registerMfe(ep.moduleKey, el);
      // Deliver a restore that arrived while the MFE was still loading (D11).
      const pending = this.coordinator.consumePendingPath(ep.moduleKey);
      if (pending) {
        el.dispatchEvent(new CustomEvent('portal:restore', { detail: { path: pending }, bubbles: true, composed: true }));
      }
      setTimeout(() => this.loading.set(false), 4000);
      this.mounted = true;
    } catch (err) {
      console.error(`[portal] failed to mount mfe "${ep.element}":`, err);
      this.loading.set(false);
    }
  }

  private withLanguageParam(rawUrl: string): string {
    try {
      const url = new URL(rawUrl);
      url.searchParams.set('lang', this.i18n.locale());
      return url.toString();
    } catch {
      return rawUrl;
    }
  }

  /** Resolves a module-internal path against the module's base URL (same contract as Bridge.restoreModule). */
  private resolveModuleUrl(base: string, path: string): string {
    try {
      return new URL(path, base).toString();
    } catch {
      return base.replace(/\/+$/, '') + '/' + path.replace(/^\//, '');
    }
  }

  private ensureScript(url: string): Promise<void> {    const existing = loadedScripts.get(url);
    if (existing) return existing;
    const promise = new Promise<void>((resolve, reject) => {
      const script = document.createElement('script');
      script.src = url;
      script.async = true;
      script.onload = () => resolve();
      script.onerror = () => {
        loadedScripts.delete(url);
        reject(new Error(`failed to load ${url}`));
      };
      document.head.appendChild(script);
    });
    loadedScripts.set(url, promise);
    return promise;
  }

  protected onIframeLoad(): void {
    this.loading.set(false);
    this.injectIframeMonitor();
  }

  private injectIframeMonitor(): void {
    const ep = this.entryPoint();
    if (ep.type !== 'iframe' || !ep.url || isSelfFraming(ep.url)) return;
    const iframe = this.iframeHost()?.nativeElement?.querySelector('iframe') as HTMLIFrameElement | null;
    if (!iframe) return;

    this.bridge.registerIframe(ep.moduleKey, iframe, ep.url);

    let iframeOrigin: string;
    try {
      iframeOrigin = new URL(ep.url, window.location.origin).origin;
    } catch {
      return;
    }
    if (iframeOrigin !== window.location.origin) return;

    // Same-origin module: inject the navigation-tracking agent (best-effort,
    // D7). On failure (CSP, unloaded document) the module falls back to
    // contract mode (reporting portal:navigate itself).
    try {
      const doc = iframe.contentDocument;
      if (!doc || !doc.body) return;
      const script = doc.createElement('script');
      script.textContent = buildIframeAgentScript(ep.moduleKey, window.location.origin);
      doc.body.appendChild(script);
    } catch (err) {
      console.warn(`[portal] iframe agent injection failed for "${ep.moduleKey}" (contract-only mode):`, err);
    }
  }
}
