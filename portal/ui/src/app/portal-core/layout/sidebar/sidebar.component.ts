import { ChangeDetectionStrategy, Component, computed, effect, ElementRef, inject, input, output, signal, viewChild } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import type { PortalModuleContent, WorkspaceMeta } from '../../../core/models';
import { moduleContentId } from '../../../core/models';
import { I18nService } from '../../../core/i18n/i18n.service';
import { BrandingService } from '../../../core/branding/branding.service';
import { MsgCenterStore } from '../../../core/msg-center/msg-center.store';
import type { PinnedNode } from '../../../core/navigation/navigation.models';
import { NavigationStore } from '../../../core/navigation/navigation.store';
import { WorkbenchService } from '../../features/workspaces/workspaces.store';

@Component({
  selector: 'app-sidebar',
  imports: [FormsModule, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './sidebar.component.html',
  styleUrl: './sidebar.component.css',
})
export class Sidebar {
  readonly moduleContents = input<PortalModuleContent[]>([]);
  readonly workspaces = input<WorkspaceMeta[]>([]);
  readonly activeWorkspace = input<string | null>(null);
  readonly homeClick = output<void>();
  readonly entryPointClick = output<PortalModuleContent>();
  readonly workspaceClick = output<string>();
  readonly workspaceRename = output<{ old: string; newName: string }>();
  readonly workspaceDelete = output<string>();

  readonly activeAppKey = input<string | null>(null);
  readonly homeTabActive = input(false);
  readonly workspacesEnabled = input(true);
  readonly chatClick = output<void>();
  /** Quick Chat dock button renders only when the `ai-hub:quick-chat` EP is served. */
  readonly quickChatAvailable = input(false);

  protected readonly i18n = inject(I18nService);
  protected readonly branding = inject(BrandingService);
  protected readonly nav = inject(NavigationStore);
  private readonly wb = inject(WorkbenchService);
  protected readonly msgCenter = inject(MsgCenterStore);

  protected readonly hovered = signal(false);
  protected readonly pinned = signal(false);
  protected readonly expanded = computed(() => this.hovered() || this.pinned());

  protected readonly features = computed(() => this.nav.features());
  protected readonly sidebarSettings = computed(() => this.nav.sidebar());

  protected readonly expandedFolders = signal<Set<string>>(new Set());

  private expandedInitialized = false;

  /** Restores persisted expanded-folder ids once user settings arrive. */
  constructor() {
    effect(() => {
      const settings = this.nav.userSettings();
      if (settings && !this.expandedInitialized) {
        this.expandedInitialized = true;
        this.expandedFolders.set(new Set(settings.sidebarExpanded));
      }
    });
    // Inline rename only exists in the expanded rail — collapse abandons it.
    effect(() => {
      if (!this.expanded() && this.renamingWs() !== null) {
        this.renamingWs.set(null);
      }
    });
  }

  /** Home row active: the Home tab is focused, or the plain no-workspace/no-app view. */
  protected readonly homeActive = computed(
    () =>
      this.homeTabActive() ||
      (this.activeWorkspace() === null && this.activeAppKey() === null),
  );

  /** Pinned-apps section renders only when enabled, shown, and non-empty. */
  protected readonly pinnedVisible = computed(
    () =>
      this.features().pinnedAppsEnabled &&
      this.sidebarSettings().showPinned &&
      this.nav.pinnedTree().length > 0,
  );

  /** Fresh-user hint: nothing personalized in the rail yet → point at Home. */
  protected readonly showEmptyHint = computed(
    () => !this.pinnedVisible() && this.pickedApps().length === 0,
  );

  protected readonly renamingWs = signal<string | null>(null);
  protected readonly renameValue = signal('');
  private readonly renameInput = viewChild<ElementRef<HTMLInputElement>>('renameInput');

  protected startRename(name: string): void {
    this.renamingWs.set(name);
    this.renameValue.set(name);
    setTimeout(() => this.renameInput()?.nativeElement.focus(), 0);
  }

  protected commitRename(): void {
    const oldName = this.renamingWs();
    const newName = this.renameValue().trim();
    this.renamingWs.set(null);
    if (oldName && newName && newName !== oldName) {
      this.workspaceRename.emit({ old: oldName, newName });
    }
  }

  protected cancelRename(): void {
    this.renamingWs.set(null);
  }

  /** Picked quick-access apps, refs resolved to visible entry points. */
  protected readonly pickedApps = computed<PortalModuleContent[]>(() => {
    const byRef = new Map<string, PortalModuleContent>();
    for (const ep of this.moduleContents()) {
      if (ep.category === 'applications') byRef.set(moduleContentId(ep), ep);
    }
    return this.sidebarSettings()
      .apps.map((ref) => byRef.get(ref))
      .filter((ep): ep is PortalModuleContent => !!ep);
  });

  /** Resolves a pinned-tree item ref to a visible entry point. */
  protected resolveRef(ref: string): PortalModuleContent | null {
    const idx = ref.indexOf(':');
    if (idx <= 0) return null;
    const ep = this.wb.findModuleContent(ref.slice(0, idx), ref.slice(idx + 1));
    return ep && ep.category === 'applications' ? ep : null;
  }

  protected isFolderExpanded(id: string): boolean {
    return this.expandedFolders().has(id);
  }

  protected toggleFolder(id: string, event?: Event): void {
    event?.stopPropagation();
    let next: Set<string>;
    this.expandedFolders.update((s) => {
      next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
    void this.nav.saveExpanded([...next!]);
  }

  togglePinned(): void {
    this.pinned.update((v) => !v);
  }

  epId(ep: PortalModuleContent): string {
    return moduleContentId(ep);
  }

  /** Unread count badge on the Message Center row (0 = hidden). */
  protected msgCenterUnread(epId: string): number {
    return epId === 'msgcenter:main' ? this.msgCenter.unread() : 0;
  }
}
