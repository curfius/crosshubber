import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { AiHubService, type AgentCatalogEntry } from '../../../core/ai-hub/ai-hub.service';
import { AiHubSettingsService } from '../../../core/ai-hub/ai-hub-settings.service';
import {
  deriveChatModelOptions,
  matchChatModelOption,
  modelKey,
  toDefaultModel,
  type ChatModelOption,
} from '../../../core/ai-hub/chat-models';
import { UserSettingsService } from '../../../core/settings/user-settings.service';
import { Switch } from '../../../shared/components/switch/switch.component';
import { I18nService } from '../../../core/i18n/i18n.service';
import { groupAgentCatalog, toggleDisabled } from './user-settings-ai.helpers';

/**
 * Per-user AI settings (phase 5): default model choice, the "about you" context
 * the agent uses for personalized answers, and the tools/agents catalogue with
 * per-user enable/disable toggles. Everything instant-applies to the
 * `user_settings` scope `ai` — no Save button (same pattern as the General page).
 */
@Component({
  selector: 'app-user-settings-ai',
  imports: [Switch],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './user-settings-ai.component.html',
  styleUrl: './user-settings-ai.component.css',
})
export class UserSettingsAi implements OnInit {
  private readonly aiHub = inject(AiHubService);
  private readonly aiHubSettings = inject(AiHubSettingsService);
  private readonly userSettings = inject(UserSettingsService);
  protected readonly i18n = inject(I18nService);

  protected readonly models = signal<ChatModelOption[]>([]);
  protected readonly defaultModelKey = signal('');
  protected readonly about = signal('');
  protected readonly entries = signal<AgentCatalogEntry[]>([]);
  protected readonly disabled = signal<Set<string>>(new Set());
  protected readonly saved = signal(false);

  private initialAbout = '';

  protected readonly groups = computed(() => groupAgentCatalog(this.entries()));

  /** The selector renders only when more than one model is available. */
  protected readonly hasModelChoice = computed(() => this.models().length > 1);

  async ngOnInit(): Promise<void> {
    const [, moduleSettings, userAi] = await Promise.all([
      this.aiHub.load(),
      this.aiHubSettings.load(),
      this.userSettings.get('ai'),
    ]);
    this.entries.set(await this.aiHub.listAgentCatalog());

    const selectedTokens = new Set(
      Array.isArray(moduleSettings['selectedTokens'])
        ? (moduleSettings['selectedTokens'] as string[])
        : [],
    );
    this.models.set(deriveChatModelOptions(this.aiHub.providerList(), selectedTokens));
    const selected = matchChatModelOption(userAi, this.models());
    if (selected) this.defaultModelKey.set(modelKey(selected));

    if (typeof userAi['about'] === 'string') {
      this.initialAbout = userAi['about'];
      this.about.set(userAi['about']);
    }
    if (Array.isArray(userAi['disabledTools'])) {
      this.disabled.set(new Set(userAi['disabledTools'] as string[]));
    }
  }

  protected modelKeyOf(m: ChatModelOption): string {
    return modelKey(m);
  }

  protected async onModelSelect(e: Event): Promise<void> {
    const value = (e.target as HTMLSelectElement).value;
    const model = this.models().find((m) => modelKey(m) === value);
    if (!model) return;
    this.defaultModelKey.set(modelKey(model));
    await this.userSettings.update('ai', { defaultModel: toDefaultModel(model) });
    this.flashSaved();
  }

  protected onAboutInput(value: string): void {
    this.about.set(value);
  }

  /** Saves on blur when the text actually changed (instant-apply pattern). */
  protected async onAboutBlur(): Promise<void> {
    const value = this.about().trim();
    if (value === this.initialAbout.trim()) return;
    const merged = await this.userSettings.update('ai', { about: value });
    if (merged) {
      this.initialAbout = value;
      this.flashSaved();
    }
  }

  protected isEnabled(entry: AgentCatalogEntry): boolean {
    return !this.disabled().has(entry.modelName);
  }

  protected async toggle(entry: AgentCatalogEntry, enabled: boolean): Promise<void> {
    this.disabled.update((set) => toggleDisabled(set, entry.modelName, enabled));
    const merged = await this.userSettings.update('ai', {
      disabledTools: Array.from(this.disabled()).sort(),
    });
    if (merged) this.flashSaved();
  }

  protected kindLabel(kind: string): string {
    return this.i18n.t(
      kind === 'agent' ? 'userSettings.ai.kind-agent' : 'userSettings.ai.kind-tool',
    );
  }

  private flashSaved(): void {
    this.saved.set(true);
    setTimeout(() => this.saved.set(false), 2000);
  }
}
