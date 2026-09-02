import { Component, inject, signal, computed, OnInit, DestroyRef } from '@angular/core';
import { NavigationCoordinator } from '../../features/navigation-coordinator.service';
import { AiHubService } from '../../../core/ai-hub/ai-hub.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import { AiHubChat } from './chat/chat.component';
import { AiHubProviders } from './providers/providers.component';
import { AiHubChannels } from './channels/channels.component';

type TabKey = 'chat' | 'providers' | 'channels';

const MODULE_KEY = 'ai-hub';

@Component({
  selector: 'app-ai-hub',
  imports: [AiHubChat, AiHubProviders, AiHubChannels],
  templateUrl: './ai-hub.component.html',
  styleUrl: './ai-hub.component.css',
})
export class AiHub implements OnInit {
  private readonly coordinator = inject(NavigationCoordinator);
  private readonly aiHub = inject(AiHubService);
  protected readonly i18n = inject(I18nService);

  protected readonly activeTab = signal<TabKey>('chat');

  protected readonly tabs = computed(() => {
    const tabs: Array<{ key: TabKey; labelKey: string }> = [
      { key: 'chat', labelKey: 'aihub.tab.chat' },
      { key: 'providers', labelKey: 'aihub.tab.providers' },
    ];
    // Channels administration is gated by the AI Hub edit role; the API
    // enforces the same role server-side.
    if (this.aiHub.canManage()) {
      tabs.push({ key: 'channels', labelKey: 'aihub.tab.channels' });
    }
    return tabs;
  });

  constructor() {
    // Restore requests arrive through the NavigationCoordinator (URL query
    // params, popstate, deep links) — a pending path that arrived before this
    // component mounted is replayed on registration.
    const off = this.coordinator.onRestore(MODULE_KEY, (path) => this.applyModulePath(path));
    inject(DestroyRef).onDestroy(off);
  }

  async ngOnInit(): Promise<void> {
    await this.aiHub.load();
    const pending = this.coordinator.consumePendingPath(MODULE_KEY);
    if (pending) this.applyModulePath(pending);
  }

  protected selectTab(key: TabKey): void {
    if (key === 'channels' && !this.aiHub.canManage()) return;
    this.activeTab.set(key);
    this.coordinator.navigateFromModule(MODULE_KEY, '/' + key);
  }

  private applyModulePath(path: string): void {
    const key = path.replace(/^\//, '').split('/')[0];
    if (key === 'chat' || key === 'providers' || key === 'channels') {
      this.activeTab.set(key);
    }
  }
}
