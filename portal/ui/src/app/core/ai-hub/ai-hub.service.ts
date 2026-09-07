import { Injectable, signal, computed } from '@angular/core';
import type { PortalUser } from '../models';

export const AI_HUB_EDIT_ROLE = 'portal-ai-hub-edit';

export interface LlmModelConfig {
  id: string;
  name: string;
  enabled: boolean;
  variant?: string;
}

export interface LlmTokenResponse {
  id: string;
  name: string;
  maskedKey: string | null;
  enabled: boolean;
  models: LlmModelConfig[];
}

export interface LlmTokenConfig {
  id: string;
  name: string;
  apiKey?: string;
  enabled: boolean;
  models: LlmModelConfig[];
}

export interface LlmProviderConfig {
  id: string;
  name: string;
  enabled: boolean;
  baseURL?: string;
  tokens: LlmTokenResponse[];
}

export interface LlmProvidersResponse {
  providers: LlmProviderConfig[];
}

export interface FetchedModel {
  id: string;
  name: string;
}

@Injectable({ providedIn: 'root' })
export class AiHubService {
  readonly providers = signal<LlmProviderConfig[]>([]);
  readonly loaded = signal(false);
  /** True when the user holds the AI Hub administration role. */
  readonly canManage = signal(false);

  readonly providerList = computed(() => this.providers());

  init(user: PortalUser): void {
    this.canManage.set(user.roles.includes(AI_HUB_EDIT_ROLE));
  }

  async load(): Promise<void> {
    try {
      const res = await fetch('/api/ai-hub/providers');
      if (!res.ok) return;
      const data = await res.json() as LlmProvidersResponse;
      this.providers.set(data.providers ?? []);
      this.loaded.set(true);
    } catch (err) {
      console.error('[ai-hub] failed to load:', err);
    }
  }

  async updateProvider(id: string, patch: Partial<LlmProviderConfig>): Promise<void> {
    try {
      const res = await fetch(`/api/ai-hub/providers/${encodeURIComponent(id)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(patch),
      });
      if (!res.ok) { console.error('[ai-hub] updateProvider failed:', res.status, await res.text()); return; }
      const updated = await res.json() as LlmProviderConfig;
      this.providers.update((ps) => ps.map((p) => p.id === id ? updated : p));
    } catch (err) {
      console.error('[ai-hub] failed to update provider:', err);
    }
  }

  async updateToken(providerId: string, tokenId: string, patch: Partial<LlmTokenConfig>): Promise<void> {
    try {
      const res = await fetch(`/api/ai-hub/providers/${encodeURIComponent(providerId)}/tokens/${encodeURIComponent(tokenId)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(patch),
      });
      if (!res.ok) return;
      const updated = await res.json() as LlmTokenResponse;
      this.providers.update((ps) => ps.map((p) => {
        if (p.id !== providerId) return p;
        return { ...p, tokens: p.tokens.map((t) => t.id === tokenId ? updated : t) };
      }));
    } catch (err) {
      console.error('[ai-hub] failed to update token:', err);
    }
  }

  async addToken(providerId: string, token: LlmTokenConfig): Promise<void> {
    try {
      const res = await fetch(`/api/ai-hub/providers/${encodeURIComponent(providerId)}/tokens`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: token.name, apiKey: token.apiKey }),
      });
      if (!res.ok) { console.error('[ai-hub] addToken failed:', res.status, await res.text()); return; }
      const created = await res.json() as LlmTokenResponse;
      this.providers.update((ps) => ps.map((p) => {
        if (p.id !== providerId) return p;
        return { ...p, tokens: [...p.tokens, created] };
      }));
    } catch (err) {
      console.error('[ai-hub] failed to add token:', err);
    }
  }

  async removeToken(providerId: string, tokenId: string): Promise<void> {
    try {
      const res = await fetch(`/api/ai-hub/providers/${encodeURIComponent(providerId)}/tokens/${encodeURIComponent(tokenId)}`, {
        method: 'DELETE',
      });
      if (!res.ok) { console.error('[ai-hub] removeToken failed:', res.status, await res.text()); return; }
      this.providers.update((ps) => ps.map((p) => {
        if (p.id !== providerId) return p;
        return { ...p, tokens: p.tokens.filter((t) => t.id !== tokenId) };
      }));
    } catch (err) {
      console.error('[ai-hub] failed to remove token:', err);
    }
  }

  async fetchModels(providerId: string): Promise<FetchedModel[]> {
    try {
      const res = await fetch(`/api/ai-hub/providers/${encodeURIComponent(providerId)}/models`);
      if (!res.ok) return [];
      const data = (await res.json()) as { models: FetchedModel[] };
      return data.models ?? [];
    } catch (err) {
      console.error('[ai-hub] failed to fetch models:', err);
      return [];
    }
  }

  async addCustomProvider(id: string, name: string, baseURL: string): Promise<boolean> {
    try {
      const res = await fetch('/api/ai-hub/providers', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id, name, baseURL }),
      });
      if (!res.ok) { console.error('[ai-hub] addCustomProvider failed:', res.status, await res.text()); return false; }
      const provider = await res.json() as LlmProviderConfig;
      this.providers.update((ps) => [...ps, provider]);
      return true;
    } catch (err) {
      console.error('[ai-hub] failed to add provider:', err);
      return false;
    }
  }

  async removeCustomProvider(id: string): Promise<boolean> {
    try {
      const res = await fetch(`/api/ai-hub/providers/${encodeURIComponent(id)}`, { method: 'DELETE' });
      if (!res.ok) { console.error('[ai-hub] removeCustomProvider failed:', res.status, await res.text()); return false; }
      this.providers.update((ps) => ps.filter((p) => p.id !== id));
      return true;
    } catch (err) {
      console.error('[ai-hub] failed to remove provider:', err);
      return false;
    }
  }
}
