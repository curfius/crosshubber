import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
  computed,
  Output,
  EventEmitter,
  OnInit,
  AfterViewChecked,
  ElementRef,
  ViewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AiHubSettingsService } from '../../../../core/ai-hub/ai-hub-settings.service';
import { AiHubService } from '../../../../core/ai-hub/ai-hub.service';
import {
  deriveChatModelOptions,
  matchChatModelOption,
  modelKey,
  toDefaultModel,
  type ChatModelOption,
} from '../../../../core/ai-hub/chat-models';
import { I18nService } from '../../../../core/i18n/i18n.service';
import { WorkbenchService } from '../../../features/workspaces/workspaces.store';
import { ChatCoreService, type ChatMessage } from '../shared/chat-core.service';
import { ChatToolFlow, waitUntilIdle } from '../shared/chat-tool-flow';
import { AiHubHandoffService } from '../shared/ai-hub-handoff.service';
import { renderChatMarkdown } from '../shared/chat-markdown';
import { ChatActivityLine } from '../shared/activity-line/chat-activity-line.component';
import { buildClientContext } from '../shared/session-context';
import { ConfirmDialog } from '../../../../shared/components/confirm-dialog/confirm-dialog.component';

@Component({
  selector: 'app-ai-hub-quick-chat',
  imports: [FormsModule, ConfirmDialog, ChatActivityLine],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './quick-chat.component.html',
  styleUrl: './quick-chat.component.css',
})
export class AiHubQuickChat implements OnInit, AfterViewChecked {
  private readonly aiHubSettings = inject(AiHubSettingsService);
  private readonly aiHub = inject(AiHubService);
  private readonly chatCore = inject(ChatCoreService);
  private readonly workbench = inject(WorkbenchService);
  private readonly handoff = inject(AiHubHandoffService);
  protected readonly i18n = inject(I18nService);

  @ViewChild('messagesContainer') messagesContainer?: ElementRef<HTMLDivElement>;

  @Output() readonly close = new EventEmitter<void>();

  protected readonly messages = signal<ChatMessage[]>([]);
  protected readonly input = signal('');
  protected readonly loading = signal(false);
  protected readonly models = signal<ChatModelOption[]>([]);
  protected readonly selectedModelKey = signal('');
  protected readonly errorMessage = signal('');
  protected readonly errorDetails = signal('');
  protected readonly errorExpanded = signal(false);
  protected readonly status = signal('');
  protected readonly inputPlaceholder = signal('');
  /** Tool-activity rows + pending confirmation for the agent loop (AI plan C3). */
  protected readonly toolFlow = new ChatToolFlow();

  private shouldScroll = false;
  /** Conversation reused for the whole quick-chat session (assigned by the server). */
  private conversationId: string | null = null;

  protected readonly selectedModel = computed(() => {
    const key = this.selectedModelKey();
    return this.models().find((m) => modelKey(m) === key) ?? null;
  });

  async ngOnInit(): Promise<void> {
    const [, settings] = await Promise.all([this.aiHub.load(), this.aiHubSettings.load()]);
    const selectedTokens = new Set(
      Array.isArray(settings['selectedTokens']) ? (settings['selectedTokens'] as string[]) : [],
    );
    this.models.set(deriveChatModelOptions(this.aiHub.providerList(), selectedTokens));
    if (typeof settings['inputPlaceholder'] === 'string')
      this.inputPlaceholder.set(settings['inputPlaceholder']);
    const selected = matchChatModelOption(settings, this.models());
    if (selected) this.selectedModelKey.set(modelKey(selected));
  }

  ngAfterViewChecked(): void {
    if (this.shouldScroll) {
      this.shouldScroll = false;
      setTimeout(() => this.scrollToBottom(), 0);
    }
  }

  protected modelOptionKey(m: ChatModelOption): string {
    return modelKey(m);
  }

  /** Dropdown change handler (the selector only renders when > 1 model is available). */
  protected async onModelSelect(e: Event): Promise<void> {
    const value = (e.target as HTMLSelectElement).value;
    const model = this.models().find((m) => this.modelOptionKey(m) === value);
    if (model) await this.selectModel(model);
  }

  protected async selectModel(m: ChatModelOption): Promise<void> {
    this.selectedModelKey.set(this.modelOptionKey(m));
    await this.aiHubSettings.save({
      defaultModel: toDefaultModel(m),
    });
  }

  protected renderMarkdown(content: string): string {
    return renderChatMarkdown(content);
  }

  /**
   * Expand handoff (phase 4): park the current conversation id and open the AI Hub
   * app — the main assistant page loads the conversation and continues it there.
   */
  protected expandToMain(): void {
    this.handoff.requestOpen(this.conversationId);
    const ep = this.workbench.findModuleContent('ai-hub', 'ai-hub');
    if (ep) this.workbench.openApp(ep);
    this.close.emit();
  }

  private scrollToBottom(): void {
    try {
      const el = this.messagesContainer?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    } catch {
      /* ignore */
    }
  }

  protected async send(opts?: { toolConfirmation?: { callId: string } }): Promise<void> {
    const text = this.input().trim();
    if (!text || this.loading()) return;

    this.errorMessage.set('');
    this.errorDetails.set('');
    this.errorExpanded.set(false);

    const userMsg: ChatMessage = { role: 'user', content: text, timestamp: Date.now() };
    this.messages.update((msgs) => [...msgs, userMsg]);
    this.input.set('');
    this.loading.set(true);
    this.shouldScroll = true;

    this.status.set(
      this.i18n.t('aihub.chat.status-connecting', {
        provider: this.selectedModel()?.providerName ?? '',
      }),
    );

    try {
      const startTime = Date.now();
      let assistantContent = '';
      this.messages.update((msgs) => [
        ...msgs,
        { role: 'assistant', content: '', timestamp: Date.now() },
      ]);
      const result = await this.chatCore.streamChat({
        conversationId: this.conversationId,
        message: text,
        context: buildClientContext(this.workbench),
        toolConfirmation: opts?.toolConfirmation,
        onContent: (chunk) => {
          assistantContent += chunk;
          this.messages.update((msgs) => {
            const updated = [...msgs];
            updated[updated.length - 1] = {
              role: 'assistant',
              content: assistantContent,
              timestamp: Date.now(),
            };
            return updated;
          });
          setTimeout(() => this.scrollToBottom(), 0);
        },
        onEvent: (e) => this.toolFlow.handleEvent(e),
      });
      if (result.conversationId) this.conversationId = result.conversationId;
      const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
      this.status.set(this.i18n.t('aihub.chat.status-done', { seconds: elapsed }));
      setTimeout(() => this.status.set(''), 3000);
    } catch (err) {
      this.errorMessage.set(this.i18n.t('aihub.chat.error-contacting-provider'));
      this.errorDetails.set((err as Error).message || this.i18n.t('aihub.chat.unknown-error'));
      this.status.set('');
    } finally {
      this.loading.set(false);
    }
  }

  protected toggleError(): void {
    this.errorExpanded.update((v) => !v);
  }

  // ── Tool confirmation (AI plan C3/B6) ──────────────────────────────

  /**
   * Confirms the pending mutating tool call: waits for the current stream to
   * finish, then sends a localized confirmation message carrying the call id
   * (the server rejects empty messages with 400).
   */
  protected async confirmToolCall(): Promise<void> {
    const pending = this.toolFlow.pending();
    if (!pending) return;
    this.toolFlow.clearPending();
    await waitUntilIdle(() => this.loading());
    this.input.set(this.i18n.t('agent.chat.confirmed-message'));
    await this.send({ toolConfirmation: { callId: pending.callId } });
  }

  protected declineToolCall(): void {
    this.toolFlow.decline();
  }

  protected clearError(): void {
    this.errorMessage.set('');
    this.errorDetails.set('');
    this.errorExpanded.set(false);
  }

  protected onKeydown(e: KeyboardEvent): void {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      this.send();
    }
  }
}
