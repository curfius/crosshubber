import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DndTree } from '../../../shared/components/dnd-tree/dnd-tree.component';
import type { EditableTreeNode } from '../../../core/navigation/navigation.models';
import { newTreeId, REF_RE } from '../../../core/navigation/navigation.models';
import type { PinnedNode } from '../../../core/navigation/navigation.models';
import { NavigationStore } from '../../../core/navigation/navigation.store';
import { WorkbenchService } from '../../features/workspaces/workspaces.store';
import type { PortalEntryPoint } from '../../../core/models';
import { entryPointId } from '../../../core/models';
import { I18nService } from '../../../core/i18n/i18n.service';

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Item rows are hydrated with the entry-point display NAME (stored refs are
 * resolved through the workbench entry points); unresolvable refs fall back
 * to the raw ref code.
 */
function toEditable(tree: PinnedNode[], resolve: (ref: string) => PortalEntryPoint | undefined): EditableTreeNode[] {
  return tree.map((node) => {
    if (node.nodeType === 'folder') {
      return {
        id: node.id,
        label: node.name ?? '',
        kind: 'folder' as const,
        children: toEditable(node.children, resolve),
      };
    }
    const ref = node.ref ?? '';
    const ep = resolve(ref);
    return {
      id: node.id,
      label: ep?.name ?? ref,
      kind: 'item' as const,
      color: ep?.color,
      meta: ref,
      children: [],
    };
  });
}

function toPinned(nodes: EditableTreeNode[]): PinnedNode[] {
  return nodes.map((node) =>
    node.kind === 'folder'
      ? {
          id: UUID_RE.test(node.id) ? node.id : newTreeId('folder'),
          nodeType: 'folder' as const,
          name: node.label,
          children: toPinned(node.children),
        }
      : {
          id: UUID_RE.test(node.id) ? node.id : newTreeId('item'),
          nodeType: 'item' as const,
          ref: String(node.meta ?? node.label),
          children: [],
        },
  );
}

/**
 * User-settings content: per-user pinned apps tree manager (ex-favorites).
 * Adding an application pins it; removing/deleting it unpins. Autosaves the
 * full tree on every mutation (D10). The "Pinned Applications" root is a
 * locked editor-only section and is not persisted.
 */
@Component({
  selector: 'app-pinned-apps-editor',
  imports: [DndTree, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './pinned-apps-editor.component.html',
  styleUrl: './pinned-apps-editor.component.css',
})
export class PinnedAppsEditor implements OnInit {
  private readonly nav = inject(NavigationStore);
  private readonly wb = inject(WorkbenchService);
  protected readonly i18n = inject(I18nService);

  protected readonly editableTree = signal<EditableTreeNode[]>([]);
  protected readonly saving = signal(false);
  protected readonly saveError = signal(false);
  protected readonly appFilter = signal('');

  private readonly appsByRef = computed(() => {
    const map = new Map<string, PortalEntryPoint>();
    for (const ep of this.wb.getEntryPoints()) {
      if (ep.category === 'applications' && ep.type !== 'link') map.set(entryPointId(ep), ep);
    }
    return map;
  });

  /** Apps available to pin (visible to the user, category applications). */
  protected readonly availableApps = computed(() => {
    const q = this.appFilter().toLowerCase();
    return this.wb.getEntryPoints()
      .filter((ep) => ep.category === 'applications' && ep.type !== 'link' && ep.active !== false)
      .filter((ep) => !q || ep.name.toLowerCase().includes(q))
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  protected readonly pinnedRefs = computed(() => {
    const refs = new Set<string>();
    const walk = (nodes: EditableTreeNode[]) => {
      for (const node of nodes) {
        if (node.kind === 'item') refs.add(String(node.meta ?? ''));
        walk(node.children);
      }
    };
    walk(this.editableTree());
    return refs;
  });

  protected readonly dndNodes = computed(() => this.editableTree());

  async ngOnInit(): Promise<void> {
    if (!this.nav.loaded()) await this.nav.load();
    this.editableTree.set(toEditable(this.nav.pinnedTree(), (ref) => this.appsByRef().get(ref)));
  }

  protected async onTreeChange(nodes: EditableTreeNode[]): Promise<void> {
    this.editableTree.set(nodes);
    await this.save();
  }

  protected async addApp(ep: PortalEntryPoint): Promise<void> {
    const ref = entryPointId(ep);
    if (this.pinnedRefs().has(ref)) return;
    const node: EditableTreeNode = {
      id: newTreeId('item'),
      label: ep.name,
      kind: 'item',
      color: ep.color,
      meta: ref,
      children: [],
    };
    this.editableTree.update((tree) => [...tree, node]);
    await this.save();
  }

  protected openApp(node: EditableTreeNode): void {
    const ref = String(node.meta ?? '');
    if (!REF_RE.test(ref)) return;
    const idx = ref.indexOf(':');
    const ep = this.wb.findEntryPoint(ref.slice(0, idx), ref.slice(idx + 1));
    if (ep) this.wb.openApp(ep);
  }

  private async save(): Promise<void> {
    this.saving.set(true);
    this.saveError.set(false);
    const ok = await this.nav.saveTree(toPinned(this.editableTree()));
    if (ok) this.editableTree.set(toEditable(this.nav.pinnedTree(), (ref) => this.appsByRef().get(ref)));
    else this.saveError.set(true);
    this.saving.set(false);
  }
}
