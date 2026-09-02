import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DndTree } from '../../../shared/components/dnd-tree/dnd-tree.component';
import type { EditableTreeNode } from '../../../core/navigation/navigation.models';
import { newTreeId } from '../../../core/navigation/navigation.models';
import type { NavigationLayout, LayoutSectionNode, LayoutNode } from '../../../core/navigation/navigation.models';
import { NavigationAdminService } from '../../../core/navigation/navigation-admin.service';
import { NavigationStore } from '../../../core/navigation/navigation.store';
import { WorkbenchService } from '../../features/workspaces/workspaces.store';
import type { PortalEntryPoint } from '../../../core/models';
import { entryPointId } from '../../../core/models';
import { I18nService } from '../../../core/i18n/i18n.service';

function layoutToTree(sections: LayoutSectionNode[], resolve: (ref: string) => PortalEntryPoint | undefined): EditableTreeNode[] {
  return sections.map((section) => ({
    id: section.id,
    label: section.name,
    kind: 'folder' as const,
    children: section.children.map((node): EditableTreeNode => {
      if (node.type === 'item') {
        const ep = resolve(node.ref);
        return {
          id: node.id,
          label: ep?.name ?? node.ref,
          kind: 'item' as const,
          color: ep?.color,
          meta: node.ref,
          children: [],
        };
      }
      return {
        id: node.id,
        label: node.name,
        kind: 'folder' as const,
        children: layoutToTree([node], resolve)[0]?.children ?? [],
      };
    }),
  }));
}

function treeToLayout(nodes: EditableTreeNode[], pinnedSectionEnabled: boolean): NavigationLayout {
  const convert = (list: EditableTreeNode[]): (LayoutSectionNode | LayoutNode)[] =>
    list.map((node) =>
      node.kind === 'folder'
        ? { id: node.id, name: node.label, children: convert(node.children) }
        : { id: node.id, type: 'item' as const, ref: String(node.meta ?? '') },
    );
  return {
    pinnedSectionEnabled,
    sections: convert(nodes) as LayoutSectionNode[],
  };
}

/**
 * Settings content: configuration of the Portal Navigation app
 * (Layout tab) + the navigation feature switches (General tab).
 */
@Component({
  selector: 'app-portal-nav-settings',
  imports: [DndTree, FormsModule],
  templateUrl: './portal-nav-settings.component.html',
  styleUrl: './portal-nav-settings.component.css',
})
export class PortalNavSettings implements OnInit {
  private readonly admin = inject(NavigationAdminService);
  protected readonly nav = inject(NavigationStore);
  private readonly wb = inject(WorkbenchService);
  protected readonly i18n = inject(I18nService);

  protected readonly tab = signal<'layout' | 'general'>('layout');
  protected readonly tree = signal<EditableTreeNode[]>([]);
  protected readonly baseline = signal('');
  protected readonly pinnedSectionEnabled = signal(true);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly saveSuccess = signal(false);
  protected readonly appFilter = signal('');

  protected readonly dirty = computed(() => {
    if (this.tab() === 'general') {
      return JSON.stringify(this.nav.features()) !== this.featuresBaseline();
    }
    return JSON.stringify(this.tree()) !== this.baseline() || this.pinnedSectionEnabled() !== this.baselinePinned();
  });
  private featuresBaseline = signal(JSON.stringify(this.nav.features()));
  private baselinePinned = signal(true);

  protected readonly dndNodes = computed(() => this.tree());

  private readonly appsByRef = computed(() => {
    const map = new Map<string, PortalEntryPoint>();
    for (const ep of this.wb.getEntryPoints()) {
      if (ep.category === 'applications' && ep.type !== 'link') map.set(entryPointId(ep), ep);
    }
    return map;
  });

  protected readonly availableApps = computed(() => {
    const usedRefs = new Set<string>();
    const walk = (nodes: EditableTreeNode[]) => {
      for (const node of nodes) {
        if (node.kind === 'item') usedRefs.add(String(node.meta ?? ''));
        walk(node.children);
      }
    };
    walk(this.tree());
    const q = this.appFilter().toLowerCase();
    return [...this.appsByRef().values()]
      .filter((ep) => !usedRefs.has(entryPointId(ep)))
      .filter((ep) => !q || ep.name.toLowerCase().includes(q))
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  protected readonly disabledIds = computed(() => {
    const ids = new Set<string>();
    const walk = (nodes: EditableTreeNode[]) => {
      for (const node of nodes) {
        if (node.kind === 'item' && !this.appsByRef().has(String(node.meta ?? ''))) ids.add(node.id);
        walk(node.children);
      }
    };
    walk(this.tree());
    return ids;
  });

  async ngOnInit(): Promise<void> {
    this.loading.set(true);
    if (!this.nav.loaded()) await this.nav.load();
    const layout = await this.admin.loadLayout();
    if (layout) {
      this.pinnedSectionEnabled.set(layout.pinnedSectionEnabled);
      const tree = layoutToTree(layout.sections, (ref) => this.appsByRef().get(ref));
      this.tree.set(tree);
      this.baseline.set(JSON.stringify(tree));
      this.baselinePinned.set(layout.pinnedSectionEnabled);
    }
    this.featuresBaseline.set(JSON.stringify(this.nav.features()));
    this.loading.set(false);
  }

  protected selectTab(tab: 'layout' | 'general'): void {
    this.tab.set(tab);
    this.saveError.set(null);
    this.saveSuccess.set(false);
  }

  protected onTreeChange(nodes: EditableTreeNode[]): void {
    this.tree.set(nodes);
    this.saveSuccess.set(false);
  }

  protected async togglePinnedSection(): Promise<void> {
    this.pinnedSectionEnabled.update((v) => !v);
    this.saveSuccess.set(false);
  }

  protected async addApp(ep: PortalEntryPoint): Promise<void> {
    const node: EditableTreeNode = {
      id: newTreeId('item'),
      label: ep.name,
      kind: 'item',
      color: ep.color,
      meta: entryPointId(ep),
      children: [],
    };
    this.tree.update((tree) => [...tree, node]);
    this.saveSuccess.set(false);
  }

  protected discard(): void {
    this.tree.set(JSON.parse(this.baseline()) as EditableTreeNode[]);
    this.pinnedSectionEnabled.set(this.baselinePinned());
    this.nav.features.set(JSON.parse(this.featuresBaseline()));
    this.saveError.set(null);
  }

  protected async save(): Promise<void> {
    this.saving.set(true);
    this.saveError.set(null);
    this.saveSuccess.set(false);

    if (this.tab() === 'general') {
      const current = this.nav.features();
      const ok = await this.admin.saveFeatures(current, this.nav);
      this.saving.set(false);
      if (!ok) {
        this.saveError.set(this.i18n.t('navigation.portal.saveFailed'));
        return;
      }
      this.featuresBaseline.set(JSON.stringify(this.nav.features()));
      this.saveSuccess.set(true);
      return;
    }

    const layout: NavigationLayout = treeToLayout(this.tree(), this.pinnedSectionEnabled());
    const result = await this.admin.saveLayout(layout);
    this.saving.set(false);
    if (!result.ok) {
      this.saveError.set(result.error ?? this.i18n.t('navigation.portal.saveFailed'));
      return;
    }
    const tree = layoutToTree(layout.sections, (ref) => this.appsByRef().get(ref));
    this.tree.set(tree);
    this.baseline.set(JSON.stringify(tree));
    this.baselinePinned.set(layout.pinnedSectionEnabled);
    this.saveSuccess.set(true);
  }
}
