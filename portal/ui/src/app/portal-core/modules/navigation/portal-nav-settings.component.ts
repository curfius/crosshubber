import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DndTree } from '../../../shared/components/dnd-tree/dnd-tree.component';
import { ShellNavEditor } from './shell-nav-editor.component';
import type { EditableTreeNode } from '../../../core/navigation/navigation.models';
import type { NavigationLayout, LayoutSectionNode, LayoutNode, LayoutItemNode } from '../../../core/navigation/navigation.models';
import { NavigationAdminService } from '../../../core/navigation/navigation-admin.service';
import { NavigationStore } from '../../../core/navigation/navigation.store';
import { WorkbenchService } from '../../features/workspaces/workspaces.store';
import type { PortalEntryPoint } from '../../../core/models';
import { entryPointId } from '../../../core/models';
import { I18nService } from '../../../core/i18n/i18n.service';

function layoutToTree(
  sections: LayoutNode[],
  resolve: (ref: string) => PortalEntryPoint | undefined,
): EditableTreeNode[] {
  return sections.map((node) => {
    if (node.type === 'item') {
      const ep = resolve(node.ref);
      return {
        id: node.id,
        label: ep?.name ?? node.ref,
        kind: 'item' as const,
        color: ep?.color,
        hidden: node.hidden === true ? true : undefined,
        meta: node.ref,
        children: [],
      };
    }
    return {
      id: node.id,
      label: node.name,
      kind: 'folder' as const,
      hidden: node.hidden === true ? true : undefined,
      children: layoutToTree(node.children, resolve),
    };
  });
}

function treeToLayout(nodes: EditableTreeNode[], pinnedSectionEnabled: boolean): NavigationLayout {
  const convert = (list: EditableTreeNode[]): LayoutNode[] =>
    list.map((node) => {
      if (node.kind === 'folder') {
        const section: LayoutSectionNode = { id: node.id, name: node.label, children: convert(node.children) };
        if (node.hidden) section.hidden = true;
        return section;
      }
      const item: LayoutItemNode = { id: node.id, type: 'item', ref: String(node.meta ?? '') };
      if (node.hidden) item.hidden = true;
      return item;
    });
  return {
    pinnedSectionEnabled,
    sections: convert(nodes),
  };
}

/**
 * Settings content of the portal-nav screen (admin, role portal-navigation-edit).
 * Four tabs: General (feature switches), App Navigation (portal layout tree),
 * Settings Navigation and User Settings Navigation (embedded shell tree editors).
 */
@Component({
  selector: 'app-portal-nav-settings',
  imports: [DndTree, ShellNavEditor, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './portal-nav-settings.component.html',
  styleUrl: './portal-nav-settings.component.css',
})
export class PortalNavSettings implements OnInit {
  private readonly admin = inject(NavigationAdminService);
  protected readonly nav = inject(NavigationStore);
  private readonly wb = inject(WorkbenchService);
  protected readonly i18n = inject(I18nService);

  protected readonly tab = signal<'general' | 'app' | 'settings' | 'user'>('general');
  protected readonly tree = signal<EditableTreeNode[]>([]);
  protected readonly baseline = signal('');
  protected readonly pinnedSectionEnabled = signal(true);
  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly saveSuccess = signal(false);

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

  protected selectTab(tab: 'general' | 'app' | 'settings' | 'user'): void {
    this.tab.set(tab);
    this.saveError.set(null);
    this.saveSuccess.set(false);
  }

  protected onTreeChange(nodes: EditableTreeNode[]): void {
    this.tree.set(nodes);
    this.saveSuccess.set(false);
  }

  protected togglePinnedSection(): void {
    this.pinnedSectionEnabled.update((v) => !v);
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
