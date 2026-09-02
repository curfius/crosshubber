import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import { WorkbenchService } from '../../features/workspaces/workspaces.store';
import { NavigationStore } from '../../../core/navigation/navigation.store';
import { NavigationAdminService } from '../../../core/navigation/navigation-admin.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import type { PortalEntryPoint } from '../../../core/models';
import { entryPointId } from '../../../core/models';
import type { NavigationLayout, LayoutNode, LayoutSectionNode, LayoutItemNode } from '../../../core/navigation/navigation.models';

/**
 * Portal Navigation application: dashboard-style page rendering apps per the
 * admin-managed layout config, plus the user's pinned apps section.
 */
@Component({
  selector: 'app-portal-navigation',
  imports: [FormsModule, NgTemplateOutlet],
  templateUrl: './portal-navigation.component.html',
  styleUrl: './portal-navigation.component.css',
})
export class PortalNavigation implements OnInit {
  private readonly wb = inject(WorkbenchService);
  protected readonly nav = inject(NavigationStore);
  private readonly admin = inject(NavigationAdminService);
  protected readonly i18n = inject(I18nService);

  protected readonly layout = signal<NavigationLayout | null>(null);
  protected readonly search = signal('');
  protected readonly collapsed = signal<Set<string>>(new Set());

  protected readonly entryPoints = signal<PortalEntryPoint[]>([]);

  /** refs visible to the current user (role-filtered /api/config data). */
  private readonly refToEp = computed(() => {
    const map = new Map<string, PortalEntryPoint>();
    for (const ep of this.entryPoints()) {
      if (ep.category === 'applications') map.set(entryPointId(ep), ep);
    }
    return map;
  });

  protected readonly pinnedApps = computed<PortalEntryPoint[]>(() => {
    if (!this.layout()?.pinnedSectionEnabled) return [];
    if (!this.nav.features().pinnedAppsEnabled) return [];
    const q = this.search().toLowerCase();
    return this.nav.pinnedRefs()
      .map((ref) => this.refToEp().get(ref))
      .filter((ep): ep is PortalEntryPoint => !!ep && this.matches(ep, q));
  });

  protected readonly sections = computed<LayoutNode[]>(() => {
    const q = this.search().toLowerCase();
    const filterNodes = (nodes: LayoutNode[]): LayoutNode[] => {
      const out: LayoutNode[] = [];
      for (const node of nodes) {
        if (node.type === 'item') {
          const ep = this.refToEp().get(node.ref);
          if (ep && this.matches(ep, q)) out.push(node);
        } else {
          const children = filterNodes(node.children);
          if (children.length > 0) out.push({ ...node, children });
        }
      }
      return out;
    };
    return filterNodes(this.layout()?.sections ?? []);
  });

  async ngOnInit(): Promise<void> {
    this.entryPoints.set(this.wb.getEntryPoints());
    if (!this.nav.loaded()) await this.nav.load();
    const layout = await this.admin.loadLayout();
    if (layout) this.layout.set(layout);
  }

  protected isPinned(ep: PortalEntryPoint): boolean {
    return this.nav.isPinned(entryPointId(ep));
  }

  protected async togglePin(ep: PortalEntryPoint, event: Event): Promise<void> {
    event.stopPropagation();
    await this.nav.togglePin(entryPointId(ep));
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

  protected epOf(node: LayoutNode): PortalEntryPoint | null {
    return node.type === 'item' ? this.refToEp().get((node as LayoutItemNode).ref) ?? null : null;
  }

  protected openApp(ep: PortalEntryPoint): void {
    this.wb.openApp(ep);
  }

  protected openInNewTab(ep: PortalEntryPoint, event: Event): void {
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

  private matches(ep: PortalEntryPoint, q: string): boolean {
    if (!q) return true;
    return ep.name.toLowerCase().includes(q) || ep.moduleKey.toLowerCase().includes(q);
  }
}
