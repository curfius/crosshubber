import { Component, inject, signal, computed, OnInit } from '@angular/core';
import { DsTree, DsTreeNode } from '../../../../shared/components/ds-tree/ds-tree.component';
import { FormsModule } from '@angular/forms';
import { Switch } from '../../../../shared/components/switch/switch.component';
import { ConfirmDialog } from '../../../../shared/components/confirm-dialog/confirm-dialog.component';
import { AiHubService, type LlmProviderConfig, type LlmTokenResponse, type LlmModelConfig, type FetchedModel } from '../../../../core/ai-hub/ai-hub.service';
import { I18nService } from '../../../../core/i18n/i18n.service';

type DotColor = 'green' | 'gray' | 'red' | 'amber';

@Component({
  selector: 'app-ai-hub-providers',
  imports: [FormsModule, Switch, ConfirmDialog, DsTree],
  templateUrl: './providers.component.html',
  styleUrl: './providers.component.css',
})
export class AiHubProviders implements OnInit {
  private readonly aiHub = inject(AiHubService);
  protected readonly i18n = inject(I18nService);

  protected readonly providers = this.aiHub.providerList;
  protected readonly selectedId = signal<string | null>(null);

  protected readonly providerNodes = computed<DsTreeNode[]>(() =>
    this.providers().map((p) => ({
      id: p.id,
      label: p.name,
      dotColor: this.dotColor(this.providerDot(p)),
      actionable: !this.isBuiltin(p.id),
      data: p,
    })),
  );

  protected readonly selectedProvider = computed(() => {
    const id = this.selectedId();
    return id ? this.providers().find((p) => p.id === id) ?? null : null;
  });

  protected readonly showAddCustom = signal(false);
  protected readonly customId = signal('');
  protected readonly customName = signal('');
  protected readonly customBaseURL = signal('');

  protected readonly showAddToken = signal(false);
  protected readonly newTokenName = signal('');
  protected readonly newTokenApiKey = signal('');

  protected readonly fetchingProviderId = signal<string | null>(null);
  private readonly fetchedByProvider = signal<Record<string, FetchedModel[]>>({});

  protected readonly modelSearch = signal('');
  protected readonly expandedTokens = signal<Set<string>>(new Set());

  protected readonly pendingDeleteToken = signal<{ providerId: string; tokenId: string; name: string } | null>(null);
  protected readonly pendingRemoveProvider = signal<{ id: string; name: string } | null>(null);

  async ngOnInit(): Promise<void> {
    await this.aiHub.load();
    const first = this.providers()[0];
    if (first) this.selectProvider(first.id);
  }

  protected selectProvider(id: string): void {
    this.selectedId.set(id);
    this.showAddToken.set(false);
    this.modelSearch.set('');
  }

  protected dotColor(dot: DotColor): string {
    switch (dot) {
      case 'green': return 'var(--portal-status-success)';
      case 'red': return 'var(--portal-status-danger)';
      case 'amber': return 'var(--portal-status-warning)';
      default: return 'var(--portal-text-disabled)';
    }
  }

  protected onProviderAction(node: DsTreeNode): void {
    const p = node.data as LlmProviderConfig;
    if (p) this.requestRemoveProvider(p);
  }

  protected providerDot(p: LlmProviderConfig): DotColor {
    if (!p.enabled) return 'gray';
    if (p.tokens.length === 0) return 'red';
    return p.tokens.some((t) => t.enabled) ? 'green' : 'amber';
  }

  protected async toggleProvider(provider: LlmProviderConfig): Promise<void> {
    await this.aiHub.updateProvider(provider.id, { enabled: !provider.enabled });
  }

  protected toggleTokenExpanded(tokenId: string): void {
    this.expandedTokens.update((set) => {
      const next = new Set(set);
      if (next.has(tokenId)) next.delete(tokenId); else next.add(tokenId);
      return next;
    });
  }

  protected async toggleTokenEnabled(provider: LlmProviderConfig, token: LlmTokenResponse): Promise<void> {
    await this.aiHub.updateToken(provider.id, token.id, { enabled: !token.enabled });
  }

  protected selectedModels(token: LlmTokenResponse): LlmModelConfig[] {
    return token.models.filter((m) => m.enabled);
  }

  protected isModelSelected(token: LlmTokenResponse, modelId: string): boolean {
    return token.models.some((m) => m.id === modelId && m.enabled);
  }

  protected async toggleModel(provider: LlmProviderConfig, token: LlmTokenResponse, model: FetchedModel): Promise<void> {
    const wasSelected = this.isModelSelected(token, model.id);
    const base = token.models.filter((m) => m.enabled && m.id !== model.id);
    const models: LlmModelConfig[] = wasSelected
      ? base
      : [...base, { id: model.id, name: model.name, enabled: true }];
    await this.aiHub.updateToken(provider.id, token.id, { models });
  }

  protected async setVariant(provider: LlmProviderConfig, token: LlmTokenResponse, model: LlmModelConfig, variant: string): Promise<void> {
    const models = token.models
      .filter((m) => m.enabled)
      .map((m) => (m.id === model.id ? { ...m, variant: variant || undefined } : m));
    await this.aiHub.updateToken(provider.id, token.id, { models });
  }

  protected async refreshModels(provider: LlmProviderConfig): Promise<void> {
    this.fetchingProviderId.set(provider.id);
    const models = await this.aiHub.fetchModels(provider.id);
    this.fetchedByProvider.update((cache) => ({ ...cache, [provider.id]: models }));
    this.fetchingProviderId.set(null);
  }

  protected hasFetched(provider: LlmProviderConfig): boolean {
    return this.fetchedByProvider()[provider.id] !== undefined;
  }

  protected availableModels(provider: LlmProviderConfig, token: LlmTokenResponse): FetchedModel[] {
    const q = this.modelSearch().toLowerCase();
    const fetched = this.fetchedByProvider()[provider.id] ?? [];
    return fetched.filter((m) => !q || m.name.toLowerCase().includes(q) || m.id.toLowerCase().includes(q));
  }

  protected async addToken(provider: LlmProviderConfig): Promise<void> {
    const name = this.newTokenName().trim();
    const apiKey = this.newTokenApiKey().trim();
    if (!name) return;
    await this.aiHub.addToken(provider.id, { id: '', name, apiKey: apiKey || undefined, enabled: true, models: [] });
    this.showAddToken.set(false);
    this.newTokenName.set('');
    this.newTokenApiKey.set('');
  }

  protected requestRemoveToken(provider: LlmProviderConfig, token: LlmTokenResponse): void {
    this.pendingDeleteToken.set({ providerId: provider.id, tokenId: token.id, name: token.name });
  }

  protected cancelRemoveToken(): void {
    this.pendingDeleteToken.set(null);
  }

  protected async confirmRemoveToken(): Promise<void> {
    const pending = this.pendingDeleteToken();
    if (!pending) return;
    this.pendingDeleteToken.set(null);
    await this.aiHub.removeToken(pending.providerId, pending.tokenId);
    this.expandedTokens.update((set) => {
      const next = new Set(set);
      next.delete(pending.tokenId);
      return next;
    });
  }

  protected async addCustomProvider(): Promise<void> {
    const id = this.customId().trim();
    const name = this.customName().trim();
    const baseURL = this.customBaseURL().trim();
    if (!id || !name || !baseURL) return;
    const ok = await this.aiHub.addCustomProvider(id, name, baseURL);
    if (ok) {
      this.showAddCustom.set(false);
      this.customId.set(''); this.customName.set(''); this.customBaseURL.set('');
      this.selectedId.set(id);
    }
  }

  protected requestRemoveProvider(p: LlmProviderConfig): void {
    this.pendingRemoveProvider.set({ id: p.id, name: p.name });
  }

  protected cancelRemoveProvider(): void {
    this.pendingRemoveProvider.set(null);
  }

  protected async confirmRemoveProvider(): Promise<void> {
    const pending = this.pendingRemoveProvider();
    if (!pending) return;
    this.pendingRemoveProvider.set(null);
    const ok = await this.aiHub.removeCustomProvider(pending.id);
    if (ok && this.selectedId() === pending.id) {
      const first = this.providers()[0];
      this.selectedId.set(first?.id ?? null);
    }
  }

  protected isBuiltin(id: string): boolean {
    return ['anthropic', 'openai', 'google', 'deepseek', 'ollama', 'openrouter', 'xai'].includes(id);
  }
}
