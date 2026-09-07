import { Component, inject, signal, computed, OnInit, AfterViewChecked, ElementRef, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ModuleSettingsService } from '../../../../core/settings/module-settings.service';
import { AiHubService } from '../../../../core/ai-hub/ai-hub.service';
import {
  deriveChatModelOptions,
  matchChatModelOption,
  modelKey,
  toDefaultModel,
  type ChatModelOption,
} from '../../../../core/ai-hub/chat-models';
import { I18nService } from '../../../../core/i18n/i18n.service';
import { ChatCoreService, type ChatMessage, type Conversation } from '../shared/chat-core.service';

const MODULE_KEY = 'ai-hub';

@Component({
  selector: 'app-ai-hub-chat',
  imports: [FormsModule],
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.css',
})
export class AiHubChat implements OnInit, AfterViewChecked {
  private readonly moduleSettings = inject(ModuleSettingsService);
  private readonly aiHub = inject(AiHubService);
  private readonly chatCore = inject(ChatCoreService);
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
  protected readonly showModelPicker = signal(false);
  protected readonly conversations = signal<Conversation[]>([]);
  protected readonly currentConversationId = signal<string | null>(null);
  protected readonly showConversations = signal(false);

  private shouldScroll = false;

  protected readonly selectedModel = computed(() => {
    const key = this.selectedModelKey();
    return this.models().find((m) => modelKey(m) === key) ?? null;
  });

  async ngOnInit(): Promise<void> {
    const [, settings] = await Promise.all([this.aiHub.load(), this.moduleSettings.get(MODULE_KEY)]);
    const selectedTokens = new Set(
      Array.isArray(settings['selectedTokens']) ? (settings['selectedTokens'] as string[]) : [],
    );
    this.models.set(deriveChatModelOptions(this.aiHub.providerList(), selectedTokens));
    if (typeof settings['inputPlaceholder'] === 'string') this.inputPlaceholder.set(settings['inputPlaceholder']);
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

  protected async selectModel(m: ChatModelOption): Promise<void> {
    this.selectedModelKey.set(this.modelOptionKey(m));
    this.showModelPicker.set(false);
    await this.moduleSettings.update(MODULE_KEY, {
      defaultModel: toDefaultModel(m),
    });
  }

  protected toggleModelPicker(): void {
    this.showModelPicker.update((v) => !v);
  }

  protected onPickerBackdrop(): void {
    this.showModelPicker.set(false);
  }

  private scrollToBottom(): void {
    try {
      const el = this.messagesContainer?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    } catch { /* ignore */ }
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
    this.showConversations.set(false);
  }

  protected async loadConversation(conv: Conversation): Promise<void> {
    this.showConversations.set(false);
    const msgs = await this.chatCore.getMessages(conv.id);
    this.messages.set(msgs);
    this.currentConversationId.set(conv.id);
    this.errorMessage.set('');
    this.shouldScroll = true;
  }

  protected formatConvDate(iso: string): string {
    try {
      return this.i18n.formatDate(iso, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
    } catch { return ''; }
  }

  protected async deleteConversation(e: Event, conv: Conversation): Promise<void> {
    e.stopPropagation();
    await this.chatCore.deleteConversation(conv.id);
    if (this.currentConversationId() === conv.id) {
      this.messages.set([]);
      this.currentConversationId.set(null);
    }
    await this.loadConversations();
  }

  // ── Send ───────────────────────────────────────────────────────────

  protected async send(): Promise<void> {
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
      this.i18n.t('aihub.chat.status-connecting', { provider: this.selectedModel()?.providerName ?? '' }),
    );

    try {
      const startTime = Date.now();
      let assistantContent = '';
      this.messages.update((msgs) => [...msgs, { role: 'assistant', content: '', timestamp: Date.now() }]);
      const result = await this.chatCore.streamChat({
        conversationId: this.currentConversationId(),
        message: text,
        onContent: (chunk) => {
          assistantContent += chunk;
          this.messages.update((msgs) => {
            const updated = [...msgs];
            updated[updated.length - 1] = { role: 'assistant', content: assistantContent, timestamp: Date.now() };
            return updated;
          });
          setTimeout(() => this.scrollToBottom(), 0);
        },
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
