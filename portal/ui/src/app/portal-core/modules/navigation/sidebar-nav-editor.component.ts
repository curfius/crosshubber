import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DragDropModule, CdkDragDrop, moveItemInArray } from '@angular/cdk/drag-drop';
import { NavigationStore } from '../../../core/navigation/navigation.store';
import { WorkbenchService } from '../../features/workspaces/workspaces.store';
import type { PortalModuleContent } from '../../../core/models';
import { moduleContentId } from '../../../core/models';
import { I18nService } from '../../../core/i18n/i18n.service';

/**
 * User-settings content: per-user sidebar quick access manager.
 * Toggles and reorder autosave on change (D10).
 */
@Component({
  selector: 'app-sidebar-nav-editor',
  imports: [DragDropModule, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './sidebar-nav-editor.component.html',
  styleUrl: './sidebar-nav-editor.component.css',
})
export class SidebarNavEditor implements OnInit {
  private readonly nav = inject(NavigationStore);
  private readonly wb = inject(WorkbenchService);
  protected readonly i18n = inject(I18nService);

  protected readonly appFilter = signal('');
  protected readonly saving = signal(false);

  protected readonly sidebar = computed(() => this.nav.sidebar());

  /** The Workspaces section toggle only exists when the feature is enabled (General tab). */
  protected readonly workspacesEnabled = computed(() => this.nav.features().workspacesEnabled);

  private readonly appsByRef = computed(() => {
    const map = new Map<string, PortalModuleContent>();
    for (const ep of this.wb.getModuleContents()) {
      if (ep.category === 'applications' && ep.type !== 'link') map.set(moduleContentId(ep), ep);
    }
    return map;
  });

  /** Picked quick-access apps, resolved + ordered. */
  protected readonly picks = computed<PortalModuleContent[]>(() =>
    this.sidebar().apps
      .map((ref) => this.appsByRef().get(ref))
      .filter((ep): ep is PortalModuleContent => !!ep),
  );

  /** Apps not yet picked (palette). */
  protected readonly available = computed(() => {
    const picked = new Set(this.sidebar().apps);
    const q = this.appFilter().toLowerCase();
    return [...this.appsByRef().values()]
      .filter((ep) => !picked.has(moduleContentId(ep)))
      .filter((ep) => !q || ep.name.toLowerCase().includes(q))
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  async ngOnInit(): Promise<void> {
    if (!this.nav.loaded()) await this.nav.load();
  }

  protected async toggle(key: 'showPinned' | 'showWorkspaces'): Promise<void> {
    this.saving.set(true);
    await this.nav.saveSidebar({ [key]: !this.sidebar()[key] });
    this.saving.set(false);
  }

  protected async onReorder(event: CdkDragDrop<PortalModuleContent[]>): Promise<void> {
    const refs = [...this.sidebar().apps];
    // Reorder by moving the ref at previousIndex to currentIndex within the
    // RESOLVED list, then rebuild the full ref list (unknown refs keep slots).
    moveItemInArray(event.container.data, event.previousIndex, event.currentIndex);
    const resolvedRefs = event.container.data.map((ep) => moduleContentId(ep));
    // Merge: keep unknown refs (not resolvable) after the known ones.
    const unknownRefs = this.sidebar().apps.filter((ref) => !this.appsByRef().has(ref));
    const next = [...resolvedRefs, ...unknownRefs];
    await this.persistApps(next);
  }

  protected async removePick(ep: PortalModuleContent): Promise<void> {
    const ref = moduleContentId(ep);
    await this.persistApps(this.sidebar().apps.filter((r) => r !== ref));
  }

  protected async addPick(ep: PortalModuleContent): Promise<void> {
    await this.persistApps([...this.sidebar().apps, moduleContentId(ep)]);
  }

  private async persistApps(apps: string[]): Promise<void> {
    this.saving.set(true);
    await this.nav.saveSidebar({ apps });
    this.saving.set(false);
  }
}
