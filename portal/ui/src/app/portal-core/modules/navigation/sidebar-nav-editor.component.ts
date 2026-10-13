import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NavigationStore } from '../../../core/navigation/navigation.store';
import { I18nService } from '../../../core/i18n/i18n.service';

/**
 * User-settings content: per-user sidebar navigation manager. Toggles and the
 * recently-used settings autosave on change (D10). The recently-used list
 * itself is usage-driven — only its visibility and size are managed here.
 */
@Component({
  selector: 'app-sidebar-nav-editor',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './sidebar-nav-editor.component.html',
  styleUrl: './sidebar-nav-editor.component.css',
})
export class SidebarNavEditor implements OnInit {
  private readonly nav = inject(NavigationStore);
  protected readonly i18n = inject(I18nService);

  protected readonly limitDraft = signal(10);
  protected readonly saving = signal(false);

  protected readonly sidebar = computed(() => this.nav.sidebar());

  /** Recent apps resolved against visible entry points (preview list). */
  protected readonly recentCount = computed(() => this.sidebar().recentApps.length);

  /** The Workspaces section toggle only exists when the feature is enabled (General page). */
  protected readonly workspacesEnabled = computed(() => this.nav.features().workspacesEnabled);

  async ngOnInit(): Promise<void> {
    if (!this.nav.loaded()) await this.nav.load();
    this.limitDraft.set(Math.max(1, Math.min(50, this.sidebar().recentLimit || 10)));
  }

  protected async toggle(key: 'showPinned' | 'showWorkspaces' | 'showRecent'): Promise<void> {
    this.saving.set(true);
    await this.nav.saveSidebar({ [key]: !this.sidebar()[key] });
    this.saving.set(false);
  }

  protected async applyLimit(): Promise<void> {
    const value = Math.max(1, Math.min(50, Math.floor(Number(this.limitDraft()) || 10)));
    this.limitDraft.set(value);
    this.saving.set(true);
    await this.nav.saveSidebar({ recentLimit: value });
    this.saving.set(false);
  }

  /** Clears the tracked usage history (the list refills on next app opens). */
  protected async clearRecent(): Promise<void> {
    this.saving.set(true);
    await this.nav.saveSidebar({ recentApps: [] });
    this.saving.set(false);
  }
}
