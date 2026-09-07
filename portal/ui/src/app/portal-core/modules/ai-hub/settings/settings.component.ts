import { Component, inject, signal, computed, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AiHubService, type LlmProviderConfig, type LlmTokenResponse } from '../../../../core/ai-hub/ai-hub.service';
import {
  deriveChatModelOptions,
  matchChatModelOption,
  modelKey,
  toDefaultModel,
  type ChatModelOption,
} from '../../../../core/ai-hub/chat-models';
import { DEFAULT_SYSTEM_PROMPT } from '../../../../core/ai-hub/ai-hub-defaults';
import { ModuleSettingsService } from '../../../../core/settings/module-settings.service';
import { MarkdownEditorComponent } from '../../../../shared/components/markdown-editor/markdown-editor.component';
import { Switch } from '../../../../shared/components/switch/switch.component';
import { I18nService } from '../../../../core/i18n/i18n.service';

const MODULE_KEY = 'ai-hub';

interface ActiveTokenRow {
  providerId: string;
  providerName: string;
  providerEnabled: boolean;
  token: LlmTokenResponse;
}

@Component({
  selector: 'app-ai-hub-settings',
  imports: [FormsModule, MarkdownEditorComponent, Switch],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.css',
})
export class AiHubSettings implements OnInit {
  private readonly aiHub = inject(AiHubService);
  private readonly moduleSettings = inject(ModuleSettingsService);
  protected readonly i18n = inject(I18nService);

  protected readonly providers = signal<LlmProviderConfig[]>([]);
  protected readonly selectedTokenIds = signal<Set<string>>(new Set());
  protected readonly systemPrompt = signal(DEFAULT_SYSTEM_PROMPT);
  protected readonly temperature = signal(0.7);
  protected readonly maxTokens = signal(4096);
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly inputPlaceholder = signal('');

  protected readonly activeTokens = computed<ActiveTokenRow[]>(() => {
    const rows: ActiveTokenRow[] = [];
    for (const p of this.providers()) {
      for (const t of p.tokens) {
        if (!this.isTokenActive(t)) continue;
        rows.push({ providerId: p.id, providerName: p.name, providerEnabled: p.enabled, token: t });
      }
    }
    return rows;
  });

  protected readonly models = computed<ChatModelOption[]>(() =>
    deriveChatModelOptions(this.providers(), this.selectedTokenIds()),
  );

  protected readonly defaultModelKey = signal('');

  async ngOnInit(): Promise<void> {
    await this.aiHub.load();
    this.providers.set(this.aiHub.providerList());
    const settings = await this.moduleSettings.get(MODULE_KEY);
    if (Array.isArray(settings['selectedTokens'])) {
      this.selectedTokenIds.set(new Set(settings['selectedTokens'] as string[]));
    }
    if (settings['systemPrompt']) this.systemPrompt.set(settings['systemPrompt'] as string);
    if (typeof settings['temperature'] === 'number') this.temperature.set(settings['temperature']);
    if (typeof settings['maxTokens'] === 'number') this.maxTokens.set(settings['maxTokens']);
    if (typeof settings['inputPlaceholder'] === 'string') this.inputPlaceholder.set(settings['inputPlaceholder']);
    const selected = matchChatModelOption(settings, this.models());
    if (selected) this.defaultModelKey.set(modelKey(selected));
  }

  protected isTokenSelected(tokenId: string): boolean {
    return this.selectedTokenIds().has(tokenId);
  }

  protected toggleToken(tokenId: string, selected: boolean): void {
    this.selectedTokenIds.update((set) => {
      const next = new Set(set);
      if (selected) next.add(tokenId);
      else next.delete(tokenId);
      return next;
    });
  }

  protected selectDefaultModel(m: ChatModelOption): void {
    this.defaultModelKey.set(modelKey(m));
  }

  protected modelOptionKey(m: ChatModelOption): string {
    return modelKey(m);
  }

  protected isTokenActive(token: LlmTokenResponse): boolean {
    return token.enabled && !!token.maskedKey && token.models.some((m) => m.enabled);
  }

  protected enabledModelCount(token: LlmTokenResponse): number {
    return token.models.filter((m) => m.enabled).length;
  }

  protected async save(): Promise<void> {
    this.saving.set(true);
    const selected = this.models().find((m) => modelKey(m) === this.defaultModelKey());
    await this.moduleSettings.update(MODULE_KEY, {
      selectedTokens: Array.from(this.selectedTokenIds()),
      systemPrompt: this.systemPrompt(),
      temperature: this.temperature(),
      maxTokens: this.maxTokens(),
      inputPlaceholder: this.inputPlaceholder(),
      defaultModel: selected ? toDefaultModel(selected) : null,
    });
    this.saving.set(false);
    this.saved.set(true);
    setTimeout(() => this.saved.set(false), 2000);
  }
}
