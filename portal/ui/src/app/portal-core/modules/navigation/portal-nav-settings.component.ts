import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { ShellNavEditor } from './shell-nav-editor.component';
import { NavigationStore } from '../../../core/navigation/navigation.store';
import { I18nService } from '../../../core/i18n/i18n.service';

/**
 * Settings content of the portal-nav screen (admin, role portal-navigation-edit).
 * Four tabs: General (feature switches), App Navigation (applications shell tree),
 * Settings Navigation and User Settings Navigation (embedded shell tree editors).
 * Tree editing is fully delegated to the self-saving shell-nav editors; this screen
 * only owns the tab state and the pinned-section feature switch.
 */
@Component({
  selector: 'app-portal-nav-settings',
  imports: [ShellNavEditor],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './portal-nav-settings.component.html',
  styleUrl: './portal-nav-settings.component.css',
})
export class PortalNavSettings implements OnInit {
  protected readonly nav = inject(NavigationStore);
  protected readonly i18n = inject(I18nService);

  protected readonly tab = signal<'general' | 'app' | 'settings' | 'user'>('general');
  protected readonly loading = signal(true);
  protected readonly saveError = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    this.loading.set(true);
    if (!this.nav.loaded()) await this.nav.load();
    this.loading.set(false);
  }

  protected selectTab(tab: 'general' | 'app' | 'settings' | 'user'): void {
    this.tab.set(tab);
    this.saveError.set(null);
  }

  protected async togglePinnedSection(): Promise<void> {
    const ok = await this.nav.saveFeatures({
      pinnedSectionEnabled: !this.nav.features().pinnedSectionEnabled,
    });
    if (!ok) {
      this.saveError.set(this.i18n.t('navigation.portal.saveFailed'));
    }
  }
}
