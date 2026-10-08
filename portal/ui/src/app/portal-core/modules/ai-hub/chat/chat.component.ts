import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
  computed,
  OnInit,
  AfterViewChecked,
  ElementRef,
  effect,
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
import {
  ChatCoreService,
  sortConversations,
  type ChatMessage,
  type Conversation,
} from '../shared/chat-core.service';
import { AiHubHandoffService } from '../shared/ai-hub-handoff.service';
import { ChatToolFlow, waitUntilIdle } from '../shared/chat-tool-flow';
import { renderChatMarkdown } from '../shared/chat-markdown';
import { ChatActivityLine } from '../shared/activity-line/chat-activity-line.component';
import { buildClientContext } from '../shared/session-context';
import { ConfirmDialog } from '../../../../shared/components/confirm-dialog/confirm-dialog.component';

@Component({
  selector: 'app-ai-hub-chat',
  imports: [FormsModule, ConfirmDialog, ChatActivityLine],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.css',
})
export class AiHubChat implements OnInit, AfterViewChecked {
  private readonly aiHubSettings = inject(AiHubSettingsService);
  private readonly aiHub = inject(AiHubService);
  private readonly chatCore = inject(ChatCoreService);
  private readonly workbench = inject(WorkbenchService);
  private readonly handoff = inject(AiHubHandoffService);
  protected readonly i18n = inject(I18nService);

  @ViewChild('messagesContainer') messagesContainer?: ElementRef<HTMLDivElement>;

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
  protected readonly conversations = signal<Conversation[]>([]);
  protected readonly currentConversationId = signal<string | null>(null);
  /** Session panel visible by default (phase 4); collapsible from the header. */
  protected readonly showSessions = signal(true);
  /** Conversation currently being renamed inline (its id), plus the edit buffer. */
  protected readonly renamingId = signal<string | null>(null);
  protected readonly renameValue = signal('');
  /** Tool-activity rows + pending confirmation for the agent loop (AI plan C3). */
  protected readonly toolFlow = new ChatToolFlow();

  private shouldScroll = false;

  /** Panel ordering: pinned first, then most recently updated. */
  protected readonly sortedConversations = computed(() => sortConversations(this.conversations()));

  constructor() {
    // Quick-chat expand handoff: load the parked conversation whenever one arrives
    // (also while the app is already open — effects re-run on the signal).
    effect(() => {
      const pending = this.handoff.pending();
      if (pending !== null) void this.openHandoffConversation(pending);
    });
  }

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
    await this.loadConversations();
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

  private scrollToBottom(): void {
    try {
      const el = this.messagesContainer?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    } catch {
      /* ignore */
    }
  }

  // ── Conversations ──────────────────────────────────────────────────

  private async loadConversations(): Promise<void> {
    this.conversations.set(await this.chatCore.listConversations());
  }

  protected newChat(): void {
    this.messages.set([]);
    this.currentConversationId.set(null);
    this.errorMessage.set('');
    this.errorDetails.set('');
    this.errorExpanded.set(false);
    this.status.set('');
    this.toolFlow.reset();
  }

  protected async loadConversation(conv: Conversation): Promise<void> {
    if (this.renamingId() === conv.id) return;
    const msgs = await this.chatCore.getMessages(conv.id);
    this.messages.set(msgs);
    this.currentConversationId.set(conv.id);
    this.errorMessage.set('');
    this.toolFlow.reset();
    this.shouldScroll = true;
  }

  /** Quick-chat expand handoff: load the parked conversation and clear the signal. */
  private async openHandoffConversation(id: string): Promise<void> {
    this.handoff.consume();
    await this.loadConversation({ id, title: '', created_at: '', updated_at: '', pinned: false });
  }

  protected toggleSessions(): void {
    this.showSessions.update((v) => !v);
  }

  protected startRename(e: Event, conv: Conversation): void {
    e.stopPropagation();
    this.renamingId.set(conv.id);
    this.renameValue.set(conv.title);
  }

  protected cancelRename(): void {
    this.renamingId.set(null);
    this.renameValue.set('');
  }

  protected async saveRename(conv: Conversation): Promise<void> {
    if (this.renamingId() !== conv.id) return;
    const title = this.renameValue().trim();
    this.cancelRename();
    if (!title || title === conv.title) return;
    const updated = await this.chatCore.updateConversation(conv.id, { title });
    if (updated) await this.loadConversations();
  }

  protected onRenameKeydown(e: KeyboardEvent, conv: Conversation): void {
    if (e.key === 'Enter') {
      e.preventDefault();
      void this.saveRename(conv);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      this.cancelRename();
    }
  }

  protected async togglePin(e: Event, conv: Conversation): Promise<void> {
    e.stopPropagation();
    const updated = await this.chatCore.updateConversation(conv.id, { pinned: !conv.pinned });
    if (updated) await this.loadConversations();
  }

  protected formatConvDate(iso: string): string {
    try {
      return this.i18n.formatDate(iso, {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch {
      return '';
    }
  }

  protected async deleteConversation(e: Event, conv: Conversation): Promise<void> {
    e.stopPropagation();
    this.cancelRename();
    await this.chatCore.deleteConversation(conv.id);
    if (this.currentConversationId() === conv.id) {
      this.messages.set([]);
      this.currentConversationId.set(null);
    }
    await this.loadConversations();
  }

  // ── Send ───────────────────────────────────────────────────────────

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
        conversationId: this.currentConversationId(),
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
      if (result.conversationId && result.conversationId !== this.currentConversationId()) {
        this.currentConversationId.set(result.conversationId);
        await this.loadConversations();
      }
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
