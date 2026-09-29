import { Injectable, computed, signal } from '@angular/core';
import { apiFetch } from '../http/api-fetch';

export interface Branding {
  name: string;
  title: string;
  logoUrl?: string | null;
}

const FALLBACK: Branding = { name: 'Crosshubber', title: 'Crosshubber Portal' };

/**
 * Tenant branding (config-owned, served by the public GET /api/branding).
 * Loaded once at app start (login screen included, since the endpoint is permitAll)
 * and drives the document title, the sidebar wordmark, and the logo surfaces.
 * Falls back to the repo-baseline branding when the tenant has no branding block
 * or the request fails — branding must never block the shell.
 */
@Injectable({ providedIn: 'root' })
export class BrandingService {
  readonly branding = signal<Branding>(FALLBACK);

  readonly name = computed(() => this.branding().name);
  readonly title = computed(() => this.branding().title);
  readonly logoUrl = computed(() => this.branding().logoUrl ?? null);

  async load(): Promise<void> {
    try {
      const res = await apiFetch('/api/branding');
      const data = (await res.json()) as Partial<Branding>;
      this.branding.set({
        name: data.name?.trim() || FALLBACK.name,
        title: data.title?.trim() || `${data.name?.trim() || FALLBACK.name} Portal`,
        logoUrl: data.logoUrl?.trim() || null,
      });
    } catch {
      this.branding.set(FALLBACK);
    }
    document.title = this.branding().title;
  }
}
