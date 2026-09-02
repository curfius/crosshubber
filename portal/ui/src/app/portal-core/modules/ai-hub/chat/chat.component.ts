import { Component, inject, signal, computed, OnInit, AfterViewChecked, ElementRef, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ModuleSettingsService } from '../../../../core/settings/module-settings.service';
import { AiHubService } from '../../../../core/ai-hub/ai-hub.service';
import { deriveChatModelOptions, type ChatModelOption } from '../../../../core/ai-hub/chat-models';
import { I18nService } from '../../../../core/i18n/i18n.service';
import { ChatCoreService, type ChatMessage, type Conversation } from '../shared/chat-core.service';
import { DEFAULT_SYSTEM_PROMPT } from '../settings/settings.component';

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
  protected readonly systemPrompt = signal(DEFAULT_SYSTEM_PROMPT);
  protected readonly temperature = signal(0.7);
  protected readonly maxTokens = signal(4096);
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
    return this.models().find((m) => `${m.providerId}:${m.modelId}:${m.tokenId}` === key) ?? null;
  });

  async ngOnInit(): Promise<void> {
    const [, settings] = await Promise.all([this.aiHub.load(), this.moduleSettings.get(MODULE_KEY)]);
    const selectedTokens = new Set(
      Array.isArray(settings['selectedTokens']) ? (settings['selectedTokens'] as string[]) : [],
    );
    this.models.set(deriveChatModelOptions(this.aiHub.providerList(), selectedTokens));
    if (settings['systemPrompt']) this.systemPrompt.set(settings['systemPrompt'] as string);
    if (typeof settings['temperature'] === 'number') this.temperature.set(settings['temperature']);
    if (typeof settings['maxTokens'] === 'number') this.maxTokens.set(settings['maxTokens']);
    if (typeof settings['inputPlaceholder'] === 'string') this.inputPlaceholder.set(settings['inputPlaceholder']);
    const savedKey = typeof settings['selectedModelKey'] === 'string' ? (settings['selectedModelKey'] as string) : '';
    if (savedKey && this.models().some((m) => `${m.providerId}:${m.modelId}:${m.tokenId}` === savedKey)) {
      this.selectedModelKey.set(savedKey);
    } else {
      const m = this.models()[0];
      if (m) this.selectedModelKey.set(`${m.providerId}:${m.modelId}:${m.tokenId}`);
    }
    await this.loadConversations();
  }

  ngAfterViewChecked(): void {
    if (this.shouldScroll) {
      this.shouldScroll = false;
      setTimeout(() => this.scrollToBottom(), 0);
    }
  }

  protected modelOptionKey(m: ChatModelOption): string {
    return `${m.providerId}:${m.modelId}:${m.tokenId}`;
  }

  protected async selectModel(m: ChatModelOption): Promise<void> {
    this.selectedModelKey.set(this.modelOptionKey(m));
    this.showModelPicker.set(false);
    await this.moduleSettings.update(MODULE_KEY, {
      selectedModelKey: this.selectedModelKey(),
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

  private async ensureConversation(): Promise<string | null> {
    if (this.currentConversationId()) return this.currentConversationId();
    const firstUserMsg = this.messages().find((m) => m.role === 'user');
    const title = firstUserMsg ? firstUserMsg.content.slice(0, 60) : this.i18n.t('aihub.chat.new-chat');
    const conv = await this.chatCore.createConversation(title);
    if (conv) {
      this.currentConversationId.set(conv.id);
      await this.loadConversations();
      return conv.id;
    }
    return null;
  }

  // ── Send ───────────────────────────────────────────────────────────

  protected async send(): Promise<void> {
    const text = this.input().trim();
    if (!text || this.loading()) return;
    const mdl = this.selectedModel();
    if (!mdl) return;

    this.errorMessage.set('');
    this.errorDetails.set('');
    this.errorExpanded.set(false);

    const userMsg: ChatMessage = { role: 'user', content: text, timestamp: Date.now() };
    this.messages.update((msgs) => [...msgs, userMsg]);
    this.input.set('');
    this.loading.set(true);
    this.shouldScroll = true;

    const convId = await this.ensureConversation();
    if (convId) void this.chatCore.saveMessage(convId, 'user', text);

    this.status.set(this.i18n.t('aihub.chat.status-connecting', { provider: mdl.providerName }));

    try {
      const startTime = Date.now();
      let assistantContent = '';
      this.messages.update((msgs) => [...msgs, { role: 'assistant', content: '', timestamp: Date.now() }]);
      await this.chatCore.streamChat({
        providerId: mdl.providerId,
        model: mdl.modelId,
        tokenId: mdl.tokenId,
        messages: this.messages().filter((m) => m.content).map((m) => ({ role: m.role, content: m.content })),
        systemPrompt: this.systemPrompt() || undefined,
        temperature: this.temperature(),
        maxTokens: this.maxTokens(),
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
      const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
      this.status.set(this.i18n.t('aihub.chat.status-done', { seconds: elapsed }));
      setTimeout(() => this.status.set(''), 3000);
      if (assistantContent && convId) void this.chatCore.saveMessage(convId, 'assistant', assistantContent);
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
