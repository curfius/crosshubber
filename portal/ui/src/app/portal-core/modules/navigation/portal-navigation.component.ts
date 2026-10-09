import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import { WorkbenchService } from '../../../core/workspaces/workspaces.store';
import { NavigationStore } from '../../../core/navigation/navigation.store';
import { I18nService } from '../../../core/i18n/i18n.service';
import type { PortalModuleContent, NavigationGroup } from '../../../core/models';
import { moduleContentId } from '../../../core/models';
import { buildAppTree, type TreeNode } from '../../layout/sidebar/tree.model';
import { avatarColor } from '../../../core/navigation/navigation.models';

/** Flattened view node for the section template (mirrors the retired layout shape). */
interface NavSectionNode {
  id: string;
  kind: 'item' | 'group';
  name: string;
  ep: PortalModuleContent | null;
  children: NavSectionNode[];
}

/**
 * Portal Navigation application: dashboard-style page rendering apps grouped per the
 * shell-tree model (same /api/config source as the sidebar), plus the user's pinned
 * apps section.
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

  /** refs visible to the current user (role-filtered /api/config data). */
  private readonly refToEp = computed(() => {
    const map = new Map<string, PortalModuleContent>();
    for (const ep of this.moduleContents()) {
      if (ep.category === 'applications') map.set(moduleContentId(ep), ep);
    }
    return map;
  });

  protected readonly pinnedApps = computed<PortalModuleContent[]>(() => {
    if (!this.nav.features().pinnedSectionEnabled) return [];
    if (!this.nav.features().pinnedAppsEnabled) return [];
    const q = this.search().toLowerCase();
    return this.nav.pinnedRefs()
      .map((ref) => this.refToEp().get(ref))
      .filter((ep): ep is PortalModuleContent => !!ep && this.matches(ep, q));
  });

  protected readonly sections = computed<NavSectionNode[]>(() => {
    const q = this.search().toLowerCase();
    const mapNodes = (nodes: TreeNode<PortalModuleContent | NavigationGroup>[]): NavSectionNode[] => {
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
            out.push({ id: `group:${group.groupKey}`, kind: 'group', name: group.name, ep: null, children });
          }
        }
      }
      return out;
    };
    return mapNodes(buildAppTree(this.groups(), this.moduleContents()));
  });

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

  protected typeBadge(type: string): string {
    switch (type) {
      case 'iframe': return 'iframe';
      case 'embedded': return 'SPA';
      case 'mfe': return 'MFE';
      default: return type;
    }
  }

  private matches(ep: PortalModuleContent, q: string): boolean {
    if (!q) return true;
    return ep.name.toLowerCase().includes(q) || ep.moduleKey.toLowerCase().includes(q);
  }
}
