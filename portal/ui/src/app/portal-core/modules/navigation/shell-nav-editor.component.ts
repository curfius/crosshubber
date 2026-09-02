import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { DragDropModule } from '@angular/cdk/drag-drop';
import { DndTree } from '../../../shared/components/dnd-tree/dnd-tree.component';
import type { EditableTreeNode } from '../../../core/navigation/navigation.models';
import { newTreeId } from '../../../core/navigation/navigation.models';
import type { ShellTreePayload, ShellTreeResponse, ShellGroupPayload, ShellItemPayload } from '../../../core/navigation/navigation.models';
import { NavigationAdminService, type ShellCategory } from '../../../core/navigation/navigation-admin.service';
import { ConfigService } from '../../../core/config/config.service';
import { I18nService } from '../../../core/i18n/i18n.service';

interface ShellGroupMeta {
  groupKey?: string;
}

interface ShellItemMeta {
  moduleKey: string;
  entryKey: string;
}

function slugify(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'section';
}

export function shellTreeFromResponse(resp: ShellTreeResponse): EditableTreeNode[] {
  const groupNodes = new Map<string, EditableTreeNode>();
  for (const g of resp.groups) {
    groupNodes.set(g.groupKey, {
      id: `group:${g.groupKey}`,
      label: g.name,
      kind: 'folder',
      meta: { groupKey: g.groupKey } satisfies ShellGroupMeta,
      children: [],
    });
  }
  const roots: EditableTreeNode[] = [];
  for (const g of resp.groups) {
    const node = groupNodes.get(g.groupKey)!;
    if (g.parentKey && groupNodes.has(g.parentKey)) groupNodes.get(g.parentKey)!.children.push(node);
    else roots.push(node);
  }
  for (const item of resp.items) {
    const node: EditableTreeNode = {
      id: `item:${item.moduleKey}:${item.entryKey}`,
      label: item.name,
      kind: 'item',
      color: item.color,
      meta: { moduleKey: item.moduleKey, entryKey: item.entryKey } satisfies ShellItemMeta,
      children: [],
    };
    if (item.groupKey && groupNodes.has(item.groupKey)) groupNodes.get(item.groupKey)!.children.push(node);
    else roots.push(node);
  }
  return roots;
}

export function shellTreeToPayload(nodes: EditableTreeNode[], reservedKeys: Set<string>): ShellTreePayload {
  const groups: ShellGroupPayload[] = [];
  const items: ShellItemPayload[] = [];
  const usedKeys = new Set(reservedKeys);
  const uniqueKey = (name: string): string => {
    const base = `nav-${slugify(name)}`;
    let key = base;
    let n = 2;
    while (usedKeys.has(key)) key = `${base}-${n++}`;
    usedKeys.add(key);
    return key;
  };
  const walk = (list: EditableTreeNode[], parentKey: string | null): void => {
    for (const node of list) {
      if (node.kind === 'folder') {
        const meta = node.meta as ShellGroupMeta | undefined;
        const key = meta?.groupKey ?? uniqueKey(node.label);
        groups.push({ groupKey: key, name: node.label, parentKey });
        walk(node.children, key);
      } else {
        const meta = node.meta as ShellItemMeta;
        items.push({ moduleKey: meta.moduleKey, entryKey: meta.entryKey, groupKey: parentKey });
      }
    }
  };
  walk(nodes, null);
  return { groups, items };
}

/**
 * Shared editor for the Settings / User Settings shell nav trees (D3):
 * renders groups + entry points of one category and saves the FULL tree
 * with explicit Save/Discard (D10).
 */
@Component({
  selector: 'app-shell-nav-editor',
  imports: [DndTree, DragDropModule],
  templateUrl: './shell-nav-editor.component.html',
  styleUrl: './shell-nav-editor.component.css',
})
export class ShellNavEditor implements OnInit {
  readonly category = input.required<ShellCategory>();

  private readonly admin = inject(NavigationAdminService);
  private readonly configService = inject(ConfigService);
  protected readonly i18n = inject(I18nService);

  protected readonly tree = signal<EditableTreeNode[]>([]);
  protected readonly baseline = signal('');
  protected readonly loading = signal(true);
  protected readonly loadError = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly saveSuccess = signal(false);

  protected readonly dirty = computed(() => JSON.stringify(this.tree()) !== this.baseline());

  protected readonly dndNodes = computed(() => this.tree());

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  protected async reload(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(false);
    const resp = await this.admin.loadShellTree(this.category());
    if (!resp) {
      this.loadError.set(true);
      this.loading.set(false);
      return;
    }
    const tree = shellTreeFromResponse(resp);
    this.tree.set(tree);
    this.baseline.set(JSON.stringify(tree));
    this.loading.set(false);
  }

  protected onTreeChange(nodes: EditableTreeNode[]): void {
    this.tree.set(nodes);
    this.saveSuccess.set(false);
  }

  protected discard(): void {
    this.tree.set(JSON.parse(this.baseline()) as EditableTreeNode[]);
    this.saveError.set(null);
  }

  protected async save(): Promise<void> {
    this.saving.set(true);
    this.saveError.set(null);
    this.saveSuccess.set(false);
    const payload = shellTreeToPayload(this.tree(), this.existingGroupKeys());
    const result = await this.admin.saveShellTree(this.category(), payload);
    this.saving.set(false);
    if (!result.ok) {
      this.saveError.set(result.error ?? 'error');
      return;
    }
    const resp = await this.admin.loadShellTree(this.category());
    if (resp) {
      const tree = shellTreeFromResponse(resp);
      this.tree.set(tree);
      this.baseline.set(JSON.stringify(tree));
    }
    this.saveSuccess.set(true);
    // Ask the shell to refresh /api/config so the settings nav updates now.
    this.configService.notifyChanged();
  }

  private existingGroupKeys(): Set<string> {
    const keys = new Set<string>();
    const walk = (nodes: EditableTreeNode[]) => {
      for (const node of nodes) {
        if (node.kind === 'folder') {
          const meta = node.meta as ShellGroupMeta | undefined;
          if (meta?.groupKey) keys.add(meta.groupKey);
          walk(node.children);
        }
      }
    };
    walk(this.tree());
    return keys;
  }
}
