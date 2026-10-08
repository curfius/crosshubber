import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
  computed,
  OnInit,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  AiHubService,
  type LlmProviderConfig,
  type LlmTokenResponse,
} from '../../../../core/ai-hub/ai-hub.service';
import {
  AgentAuditService,
  type AgentToolCallRow,
} from '../../../../core/ai-hub/agent-audit.service';
import {
  deriveChatModelOptions,
  matchChatModelOption,
  modelKey,
  toDefaultModel,
  type ChatModelOption,
} from '../../../../core/ai-hub/chat-models';
import { DEFAULT_SYSTEM_PROMPT } from '../../../../core/ai-hub/ai-hub-defaults';
import { AiHubSettingsService } from '../../../../core/ai-hub/ai-hub-settings.service';
import { MarkdownEditorComponent } from '../../../../shared/components/markdown-editor/markdown-editor.component';
import { Switch } from '../../../../shared/components/switch/switch.component';
import { I18nService } from '../../../../core/i18n/i18n.service';
import { toolStatusTone } from '../shared/chat-tool-flow';

interface ActiveTokenRow {
  providerId: string;
  providerName: string;
  providerEnabled: boolean;
  token: LlmTokenResponse;
}

@Component({
  selector: 'app-ai-hub-settings',
  imports: [FormsModule, MarkdownEditorComponent, Switch],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.css',
})
export class AiHubSettings implements OnInit {
  protected readonly aiHub = inject(AiHubService);
  private readonly auditService = inject(AgentAuditService);
  private readonly aiHubSettings = inject(AiHubSettingsService);
  protected readonly i18n = inject(I18nService);

  protected readonly providers = signal<LlmProviderConfig[]>([]);
  protected readonly selectedTokenIds = signal<Set<string>>(new Set());
  protected readonly systemPrompt = signal(DEFAULT_SYSTEM_PROMPT);
  protected readonly temperature = signal(0.7);
  protected readonly maxTokens = signal(4096);
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly inputPlaceholder = signal('');

  // ── Agent tool audit (AI plan C4) ────────────────────────────────────
  protected readonly toolCalls = signal<AgentToolCallRow[]>([]);
  protected readonly auditLoading = signal(false);
  protected readonly auditError = signal(false);
  protected readonly auditQuery = signal('');
  protected readonly auditOutcome = signal('all');
  protected readonly auditOutcomes = [
    'all',
    'ok',
    'needs_confirmation',
    'confirmed',
    'denied',
    'error',
    'cap_reached',
  ];

  protected readonly filteredToolCalls = computed<AgentToolCallRow[]>(() => {
    const query = this.auditQuery().trim().toLowerCase();
    const outcome = this.auditOutcome();
    return this.toolCalls().filter((row) => {
      if (outcome !== 'all' && row.outcome !== outcome) return false;
      if (!query) return true;
      return [row.toolName, row.moduleKey ?? '', row.userId, row.argsSummary ?? ''].some((v) =>
        v.toLowerCase().includes(query),
      );
    });
  });

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
    const settings = await this.aiHubSettings.load();
    if (Array.isArray(settings['selectedTokens'])) {
      this.selectedTokenIds.set(new Set(settings['selectedTokens'] as string[]));
    }
    if (settings['systemPrompt']) this.systemPrompt.set(settings['systemPrompt'] as string);
    if (typeof settings['temperature'] === 'number') this.temperature.set(settings['temperature']);
    if (typeof settings['maxTokens'] === 'number') this.maxTokens.set(settings['maxTokens']);
    if (typeof settings['inputPlaceholder'] === 'string')
      this.inputPlaceholder.set(settings['inputPlaceholder']);
    const selected = matchChatModelOption(settings, this.models());
    if (selected) this.defaultModelKey.set(modelKey(selected));
    if (this.aiHub.canManage()) await this.loadAudit();
  }

  // ── Agent tool audit (AI plan C4) ────────────────────────────────────

  protected async loadAudit(): Promise<void> {
    this.auditLoading.set(true);
    this.auditError.set(false);
    try {
      this.toolCalls.set(await this.auditService.listRecent());
    } catch {
      this.auditError.set(true);
    } finally {
      this.auditLoading.set(false);
    }
  }

  protected outcomeLabel(outcome: string): string {
    return this.i18n.t('agent.outcome.' + outcome.replace(/_/g, '-'));
  }

  protected readonly toolStatusTone = toolStatusTone;

  protected formatAuditTime(iso: string): string {
    try {
      return this.i18n.formatDate(iso, {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
      });
    } catch {
      return '';
    }
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
    await this.aiHubSettings.save({
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
