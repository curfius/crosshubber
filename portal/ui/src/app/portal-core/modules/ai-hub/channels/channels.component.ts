import { Component, inject, signal, computed, type WritableSignal, OnInit } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Switch } from '../../../../shared/components/switch/switch.component';
import { ConfirmDialog } from '../../../../shared/components/confirm-dialog/confirm-dialog.component';
import { AiHubService, ChannelsService, type ChannelPublic } from '../../../../core/ai-hub/ai-hub.service';
import { I18nService } from '../../../../core/i18n/i18n.service';

type DotColor = 'green' | 'gray' | 'red';

@Component({
  selector: 'app-ai-hub-channels',
  imports: [FormsModule, DatePipe, Switch, ConfirmDialog],
  templateUrl: './channels.component.html',
  styleUrl: './channels.component.css',
})
export class AiHubChannels implements OnInit {
  private readonly aiHub = inject(AiHubService);
  private readonly channelsService = inject(ChannelsService);
  protected readonly i18n = inject(I18nService);

  protected readonly channels = this.channelsService.channels;
  protected readonly selectedId = signal<string | null>(null);
  protected readonly typeFilter = signal<'all' | 'telegram' | 'whatsapp'>('all');

  protected readonly selected = computed(() => {
    const id = this.selectedId();
    return id ? this.channels().find((c) => c.id === id) ?? null : null;
  });

  protected readonly filteredChannels = computed(() => {
    const f = this.typeFilter();
    return f === 'all' ? this.channels() : this.channels().filter((c) => c.type === f);
  });

  // â”€â”€ Add channel form â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  protected readonly showAdd = signal(false);
  protected readonly newType = signal<'telegram' | 'whatsapp'>('telegram');
  protected readonly newName = signal('');
  protected readonly newBotToken = signal('');
  protected readonly newBotUsername = signal('');
  protected readonly newPhoneNumberId = signal('');
  protected readonly newAccessToken = signal('');
  protected readonly newAppSecret = signal('');
  protected readonly addError = signal('');
  protected readonly createdVerifyToken = signal('');
  protected readonly createdWebhookUrl = signal('');

  // â”€â”€ Detail editing â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  protected readonly editName = signal('');
  protected readonly editSystemPrompt = signal('');
  protected readonly editWelcomeMessage = signal('');
  protected readonly editErrorReply = signal(false);
  protected readonly editProviderId = signal('');
  protected readonly editTokenId = signal('');
  protected readonly editModel = signal('');
  protected readonly saving = signal(false);
  protected readonly savedFlash = signal('');

  protected readonly editBotToken = signal('');
  protected readonly editPhoneNumberId = signal('');
  protected readonly editAccessToken = signal('');
  protected readonly editAppSecret = signal('');
  protected readonly credentialsFlash = signal('');
  protected readonly deliveryBusy = signal(false);

  // â”€â”€ Send test â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  protected readonly testChatId = signal('');
  protected readonly testText = signal('');
  protected readonly testBusy = signal(false);
  protected readonly testResult = signal('');

  protected readonly copied = signal(false);
  protected readonly pendingDelete = signal<ChannelPublic | null>(null);

  async ngOnInit(): Promise<void> {
    await this.aiHub.load();
    await this.channelsService.load();
    const first = this.channels()[0];
    if (first) this.selectChannel(first.id);
  }

  protected selectChannel(id: string): void {
    this.selectedId.set(id);
    this.createdVerifyToken.set('');
    this.createdWebhookUrl.set('');
    this.testResult.set('');
    const c = this.channels().find((x) => x.id === id);
    if (!c) return;
    this.editName.set(c.name);
    this.editSystemPrompt.set(c.config.systemPrompt ?? '');
    this.editWelcomeMessage.set(c.config.welcomeMessage ?? '');
    this.editErrorReply.set(c.config.errorReplyEnabled ?? false);
    this.editProviderId.set(c.config.providerId ?? '');
    this.editTokenId.set(c.config.tokenId ?? '');
    this.editModel.set(c.config.model ?? '');
    this.editBotToken.set('');
    this.editPhoneNumberId.set('');
    this.editAccessToken.set('');
    this.editAppSecret.set('');
  }

  protected dotColor(c: ChannelPublic): string {
    const dot: DotColor = !c.enabled ? 'gray' : c.status.lastError ? 'red' : 'green';
    switch (dot) {
      case 'green': return 'var(--portal-status-success)';
      case 'red': return 'var(--portal-status-danger)';
      default: return 'var(--portal-text-disabled)';
    }
  }

  protected setNewType(t: 'telegram' | 'whatsapp'): void {
    this.newType.set(t);
    this.addError.set('');
  }

  protected async addChannel(): Promise<void> {
    const name = this.newName().trim();
    if (!name) return;
    this.addError.set('');
    const credentials = this.newType() === 'telegram'
      ? { botToken: this.newBotToken().trim(), botUsername: this.newBotUsername().trim() || undefined }
      : {
          phoneNumberId: this.newPhoneNumberId().trim(),
          accessToken: this.newAccessToken().trim(),
          appSecret: this.newAppSecret().trim(),
        };
    const created = await this.channelsService.create(this.newType(), name, credentials as never);
    if (!created) {
      this.addError.set(this.i18n.t('aihub.channels.addFailed'));
      return;
    }
    this.showAdd.set(false);
    this.newName.set('');
    this.newBotToken.set('');
    this.newBotUsername.set('');
    this.newPhoneNumberId.set('');
    this.newAccessToken.set('');
    this.newAppSecret.set('');
    if (created.verifyToken) this.createdVerifyToken.set(created.verifyToken);
    if (created.webhookUrl) this.createdWebhookUrl.set(created.webhookUrl);
    this.selectChannel(created.id);
  }

  // â”€â”€ Behaviour + name save â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  protected async saveBehaviour(): Promise<void> {
    const c = this.selected();
    if (!c) return;
    this.saving.set(true);
    const updated = await this.channelsService.update(c.id, {
      name: this.editName().trim() || undefined,
      config: {
        systemPrompt: this.editSystemPrompt() || undefined,
        welcomeMessage: this.editWelcomeMessage() || undefined,
        errorReplyEnabled: this.editErrorReply(),
        providerId: this.editProviderId() || undefined,
        tokenId: this.editTokenId() || undefined,
        model: this.editModel() || undefined,
      },
    });
    this.saving.set(false);
    if (updated) this.flash(this.savedFlash);
  }

  protected async toggleEnabled(c: ChannelPublic): Promise<void> {
    await this.channelsService.update(c.id, { enabled: !c.enabled });
  }

  /** Switches between webhook and long-polling delivery (telegram only). */
  protected async setDeliveryMode(c: ChannelPublic, mode: 'webhook' | 'polling'): Promise<void> {
    if (c.deliveryMode === mode || this.deliveryBusy()) return;
    this.deliveryBusy.set(true);
    await this.channelsService.update(c.id, { deliveryMode: mode });
    this.deliveryBusy.set(false);
    // Switching to webhook requires an explicit "Register webhook" action;
    // switching to polling starts the loop server-side immediately.
  }

  protected async updateCredentials(): Promise<void> {
    const c = this.selected();
    if (!c) return;
    const credentials = c.type === 'telegram'
      ? { botToken: this.editBotToken().trim() }
      : {
          phoneNumberId: this.editPhoneNumberId().trim(),
          accessToken: this.editAccessToken().trim(),
          appSecret: this.editAppSecret().trim(),
        };
    const updated = await this.channelsService.update(c.id, { credentials: credentials as never });
    if (updated) {
      this.editBotToken.set('');
      this.editPhoneNumberId.set('');
      this.editAccessToken.set('');
      this.editAppSecret.set('');
      this.flash(this.credentialsFlash);
    }
  }

  // â”€â”€ Webhook helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  protected webhookUrl(c: ChannelPublic): string {
    return c.webhookUrl ?? c.status.webhookUrl ?? '';
  }

  protected async copyWebhook(): Promise<void> {
    const c = this.selected();
    if (!c) return;
    try {
      await navigator.clipboard.writeText(this.webhookUrl(c));
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 1500);
    } catch { /* clipboard unavailable */ }
  }

  protected async registerWebhook(): Promise<void> {
    const c = this.selected();
    if (!c) return;
    this.testResult.set('');
    const res = await this.channelsService.registerWebhook(c.id);
    if (!res.ok) this.testResult.set(res.error ?? 'failed');
  }

  protected async sendTest(): Promise<void> {
    const c = this.selected();
    const chatId = this.testChatId().trim();
    const text = this.testText().trim();
    if (!c || !chatId || !text) return;
    this.testBusy.set(true);
    const res = await this.channelsService.send(c.id, chatId, text);
    this.testBusy.set(false);
    this.testResult.set(res.ok ? this.i18n.t('aihub.channels.sent') : `${this.i18n.t('aihub.channels.sendFailed')}: ${res.error ?? ''}`);
    if (res.ok) this.testText.set('');
  }

  // â”€â”€ Delete â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  protected requestDelete(c: ChannelPublic): void {
    this.pendingDelete.set(c);
  }

  protected cancelDelete(): void {
    this.pendingDelete.set(null);
  }

  protected async confirmDelete(): Promise<void> {
    const c = this.pendingDelete();
    if (!c) return;
    this.pendingDelete.set(null);
    const ok = await this.channelsService.remove(c.id);
    if (ok) {
      this.selectedId.set(null);
      const first = this.channels()[0];
      if (first) this.selectChannel(first.id);
    }
  }

  // â”€â”€ Model binding options â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  protected readonly enabledProviders = computed(() =>
    this.aiHub.providerList().filter((p) => p.enabled),
  );

  protected readonly enabledTokens = computed(() =>
    this.enabledProviders().find((p) => p.id === this.editProviderId())?.tokens.filter((t) => t.enabled) ?? [],
  );

  protected readonly enabledModels = computed(() =>
    this.enabledTokens().find((t) => t.id === this.editTokenId())?.models.filter((m) => m.enabled) ?? [],
  );

  protected onProviderChange(id: string): void {
    this.editProviderId.set(id);
    this.editTokenId.set('');
    this.editModel.set('');
  }

  protected onTokenChange(id: string): void {
    this.editTokenId.set(id);
    this.editModel.set('');
  }

  private flash(sig: WritableSignal<string>): void {
    sig.set('ok');
    setTimeout(() => sig.set(''), 2000);
  }
}
