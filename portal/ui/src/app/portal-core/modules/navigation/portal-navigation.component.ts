import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import { WorkbenchService } from '../../../core/workspaces/workspaces.store';
import { NavigationStore } from '../../../core/navigation/navigation.store';
import { I18nService } from '../../../core/i18n/i18n.service';
import type { PortalModuleContent, NavigationGroup, WorkspaceMeta } from '../../../core/models';
import { moduleContentId } from '../../../core/models';
import { buildAppTree, type TreeNode } from '../../layout/sidebar/tree.model';
import { avatarColor } from '../../../core/navigation/navigation.models';

/** Flattened view node for the section template (recursive, mirrors the shell tree). */
interface NavSectionNode {
  id: string;
  kind: 'item' | 'group';
  name: string;
  color?: string;
  ep: PortalModuleContent | null;
  children: NavSectionNode[];
}

/**
 * Portal Navigation application (the default home app): renders the user's
 * pinned apps (root order) and the applications shell tree — sections collapse
 * locally at every level. The "My Workspaces" block can be toggled by the
 * admin (General navigation page) and reads the live workspaces signal.
 */
@Component({
  selector: 'app-portal-navigation',
  imports: [FormsModule, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './portal-navigation.component.html',
  styleUrl: './portal-navigation.component.css',
})
export class PortalNavigation implements OnInit {
  private readonly wb = inject(WorkbenchService);
  protected readonly nav = inject(NavigationStore);
  protected readonly i18n = inject(I18nService);

  protected readonly search = signal('');
  protected readonly collapsed = signal<Set<string>>(new Set());

  protected readonly moduleContents = signal<PortalModuleContent[]>([]);
  protected readonly groups = signal<NavigationGroup[]>([]);

  /** Live, search-filtered workspaces list (kept fresh by the workbench). */
  protected readonly workspacesList = computed<WorkspaceMeta[]>(() => {
    const q = this.search().toLowerCase();
    return this.wb
      .workspaces()
      .filter((ws) => !q || ws.name.toLowerCase().includes(q))
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  protected readonly workspacesVisible = computed(() => this.nav.features().workspacesEnabled);

  /** refs visible to the current user (role-filtered /api/config data). */
  private readonly refToEp = computed(() => {
    const map = new Map<string, PortalModuleContent>();
    for (const ep of this.moduleContents()) {
      if (ep.category === 'applications') map.set(moduleContentId(ep), ep);
    }
    return map;
  });

  protected readonly pinnedApps = computed<PortalModuleContent[]>(() => {
    if (!this.nav.features().pinnedAppsEnabled) return [];
    const q = this.search().toLowerCase();
    return this.nav
      .pinnedRefs()
      .map((ref) => this.refToEp().get(ref))
      .filter((ep): ep is PortalModuleContent => !!ep && this.matches(ep, q));
  });

  protected readonly sections = computed<NavSectionNode[]>(() => {
    const q = this.search().toLowerCase();
    const mapNodes = (
      nodes: TreeNode<PortalModuleContent | NavigationGroup>[],
    ): NavSectionNode[] => {
      const out: NavSectionNode[] = [];
      for (const node of nodes) {
        if ('contentKey' in node.data) {
          const ep = node.data;
          if (this.matches(ep, q)) {
            out.push({ id: moduleContentId(ep), kind: 'item', name: ep.name, ep, children: [] });
          }
        } else {
          const group = node.data;
          const children = mapNodes(node.children);
          if (children.length > 0) {
            out.push({
              id: `group:${group.groupKey}`,
              kind: 'group',
              name: group.name,
              color: group.color,
              ep: null,
              children,
            });
          }
        }
      }
      return out;
    };
    return mapNodes(buildAppTree(this.groups(), this.moduleContents()));
  });

  protected readonly empty = computed(
    () => this.pinnedApps().length === 0 && this.sections().length === 0,
  );

  async ngOnInit(): Promise<void> {
    this.moduleContents.set(this.wb.getModuleContents());
    this.groups.set(this.wb.getNavigationGroups());
    if (!this.nav.loaded()) await this.nav.load();
  }

  /** Avatar background: explicit content color, else the deterministic hash fallback. */
  protected avatarBg(ep: PortalModuleContent): string {
    return ep.color || avatarColor(moduleContentId(ep));
  }

  protected isPinned(ep: PortalModuleContent): boolean {
    return this.nav.isPinned(moduleContentId(ep));
  }

  protected async togglePin(ep: PortalModuleContent, event: Event): Promise<void> {
    event.stopPropagation();
    await this.nav.togglePin(moduleContentId(ep));
  }

  protected isCollapsed(id: string): boolean {
    return this.collapsed().has(id);
  }

  protected toggleCollapsed(id: string): void {
    this.collapsed.update((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  protected openApp(ep: PortalModuleContent): void {
    this.wb.openApp(ep);
  }

  protected openInNewTab(ep: PortalModuleContent, event: Event): void {
    event.stopPropagation();
    this.wb.openApp(ep, false);
  }

  protected openWorkspace(name: string): void {
    void this.wb.loadWorkspace(name, false);
  }

  protected async deleteWorkspace(name: string, event: Event): Promise<void> {
    event.stopPropagation();
    await this.wb.deleteWorkspace(name);
  }

  protected typeBadge(type: string): string {
    switch (type) {
      case 'iframe':
        return 'iframe';
      case 'embedded':
        return 'SPA';
      case 'mfe':
        return 'MFE';
      default:
        return type;
    }
  }

  /** Relative saved-at label for workspace cards. */
  protected timeAgo(savedAt: number): string {
    const diff = Date.now() - savedAt;
    const minutes = Math.max(1, Math.floor(diff / 60_000));
    if (minutes < 1) return this.i18n.t('navigation.app.time.justNow');
    if (minutes < 60) return this.i18n.t('navigation.app.time.minutesAgo', { count: minutes });
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return this.i18n.t('navigation.app.time.hoursAgo', { count: hours });
    return this.i18n.t('navigation.app.time.daysAgo', { count: Math.floor(hours / 24) });
  }

  private matches(ep: PortalModuleContent, q: string): boolean {
    if (!q) return true;
    return ep.name.toLowerCase().includes(q) || ep.moduleKey.toLowerCase().includes(q);
  }
}
