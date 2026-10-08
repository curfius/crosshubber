import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe, UpperCasePipe } from '@angular/common';

import { NavigationCoordinator } from '../../features/navigation-coordinator.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import { MsgCenterItem, MsgCenterStore, MsgCenterTab } from '../../../core/msg-center/msg-center.store';
import { MsgCenterTaskPanel } from './task-panel.component';

const MODULE_KEY = 'msg-center';

const SEVERITY_TONES: Record<string, string> = {
  info: 'ds-badge ds-badge-info',
  success: 'ds-badge ds-badge-success',
  warning: 'ds-badge ds-badge-warning',
  error: 'ds-badge ds-badge-danger',
};

@Component({
  selector: 'app-msg-center',
  imports: [DatePipe, UpperCasePipe, MsgCenterTaskPanel],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './msg-center.component.html',
  styleUrl: './msg-center.component.css',
})
export class MsgCenter implements OnInit {
  private readonly coordinator = inject(NavigationCoordinator);
  protected readonly i18n = inject(I18nService);
  protected readonly store = inject(MsgCenterStore);

  protected readonly expandedId = signal<number | null>(null);

  protected readonly tabs: Array<{ key: MsgCenterTab; labelKey: string }> = [
    { key: 'inbox', labelKey: 'msgcenter.tab.inbox' },
    { key: 'notifications', labelKey: 'msgcenter.tab.notifications' },
    { key: 'tasks', labelKey: 'msgcenter.tab.tasks' },
    { key: 'history', labelKey: 'msgcenter.tab.history' },
  ];

  protected readonly emptyKey = computed(() => 'msgcenter.empty.' + this.store.tab());

  constructor() {
    inject(DestroyRef).onDestroy(() => this.store.stop());
  }

  async ngOnInit(): Promise<void> {
    this.store.start();
    await this.store.load(true);
  }

  protected selectTab(tab: MsgCenterTab): void {
    this.expandedId.set(null);
    this.store.selectTab(tab);
    this.coordinator.navigateFromModule(MODULE_KEY, '/' + tab);
  }

  protected toggle(item: MsgCenterItem): void {
    const isOpen = this.expandedId() === item.id;
    this.expandedId.set(isOpen ? null : item.id);
    if (!item.read && !isOpen) {
      void this.store.markRead(item.id);
    }
  }

  protected openLink(item: MsgCenterItem): void {
    if (!item.link) return;
    void this.coordinator.navigateFromModule(item.link.moduleKey, item.link.path);
  }

  protected severityTone(severity: string | null): string {
    return SEVERITY_TONES[severity ?? 'info'] ?? SEVERITY_TONES['info'];
  }

  protected stateKey(item: MsgCenterItem): string {
    return item.status === 'done' ? 'msgcenter.state.done' : 'msgcenter.state.open';
  }
}
