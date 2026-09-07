import { Component, inject, signal, computed, OnInit, DestroyRef } from '@angular/core';
import { NavigationCoordinator } from '../../features/navigation-coordinator.service';
import { AiHubService } from '../../../core/ai-hub/ai-hub.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import { AiHubChat } from './chat/chat.component';
import { AiHubProviders } from './providers/providers.component';

type TabKey = 'chat' | 'providers';

const MODULE_KEY = 'ai-hub';

@Component({
  selector: 'app-ai-hub',
  imports: [AiHubChat, AiHubProviders],
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
    return tabs;
  });

  constructor() {
    const off = this.coordinator.onRestore(MODULE_KEY, (path) => this.applyModulePath(path));
    inject(DestroyRef).onDestroy(off);
  }

  async ngOnInit(): Promise<void> {
    await this.aiHub.load();
    const pending = this.coordinator.consumePendingPath(MODULE_KEY);
    if (pending) this.applyModulePath(pending);
  }

  protected selectTab(key: TabKey): void {
    this.activeTab.set(key);
    this.coordinator.navigateFromModule(MODULE_KEY, '/' + key);
  }

  private applyModulePath(path: string): void {
    const key = path.replace(/^\//, '').split('/')[0];
    if (key === 'chat' || key === 'providers') {
      this.activeTab.set(key);
    }
  }
}
