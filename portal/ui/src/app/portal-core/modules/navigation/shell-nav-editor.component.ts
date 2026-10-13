import { ChangeDetectionStrategy, Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DndTree } from '../../../shared/components/dnd-tree/dnd-tree.component';
import type { EditableTreeNode } from '../../../core/navigation/navigation.models';
import { AVATAR_COLORS, avatarColor } from '../../../core/navigation/navigation.models';
import type { ShellTreePayload, ShellTreeResponse, ShellGroupPayload, ShellItemPayload } from '../../../core/navigation/navigation.models';
import { NavigationAdminService, type ShellCategory } from '../../../core/navigation/navigation-admin.service';
import { ConfigService } from '../../../core/config/config.service';
import { I18nService } from '../../../core/i18n/i18n.service';

interface ShellGroupMeta {
  groupKey?: string;
}

interface ShellItemMeta {
  moduleKey: string;
  contentKey: string;
}

function slugify(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'section';
}

export function shellTreeFromResponse(resp: ShellTreeResponse): EditableTreeNode[] {
  const groupNodes = new Map<string, EditableTreeNode>();
  for (const g of resp.groups) {
    // Editor-only badges explain why a row may be absent from the runtime shell:
    // `hidden` (eye) and `roles` (role-restricted groups are dropped per-user).
    const folderBadge = g.hidden === true ? ('hidden' as const) : g.roles?.length ? ('roles' as const) : undefined;
    groupNodes.set(g.groupKey, {
      id: `group:${g.groupKey}`,
      label: g.name,
      kind: 'folder',
      color: g.color ?? undefined,
      hidden: g.hidden === true ? true : undefined,
      badge: folderBadge,
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
      id: `item:${item.moduleKey}:${item.contentKey}`,
      label: item.name,
      kind: 'item',
      color: item.color,
      fallbackColor: avatarColor(`${item.moduleKey}:${item.contentKey}`),
      hidden: item.hidden === true ? true : undefined,
      badge: item.hidden === true ? ('hidden' as const) : undefined,
      meta: { moduleKey: item.moduleKey, contentKey: item.contentKey } satisfies ShellItemMeta,
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
  // Items must never save at the root level: legacy ungrouped rows fall back to
  // their (real or synthesized) module section `mod-<moduleKey>`.
  const groupNameByKey = new Map<string, string>();
  const collectNames = (list: EditableTreeNode[]): void => {
    for (const node of list) {
      if (node.kind === 'folder') {
        const key = (node.meta as ShellGroupMeta | undefined)?.groupKey;
        if (key) groupNameByKey.set(key, node.label);
        collectNames(node.children);
      }
    }
  };
  collectNames(nodes);
  const moduleGroup = (moduleKey: string): string => {
    const key = `mod-${moduleKey}`;
    if (!groupNameByKey.has(key) && !groups.some((g) => g.groupKey === key)) {
      groups.push({ groupKey: key, name: groupNameByKey.get(key) ?? moduleKey, parentKey: null, hidden: false });
    }
    return key;
  };
  const walk = (list: EditableTreeNode[], parentKey: string | null): void => {
    for (const node of list) {
      if (node.kind === 'folder') {
        const meta = node.meta as ShellGroupMeta | undefined;
        const key = meta?.groupKey ?? uniqueKey(node.label);
        groups.push({ groupKey: key, name: node.label, parentKey, hidden: !!node.hidden, color: node.color ?? null });
        walk(node.children, key);
      } else {
        const meta = node.meta as ShellItemMeta;
        const fallbackKey = parentKey ?? moduleGroup(meta.moduleKey);
        items.push({
          moduleKey: meta.moduleKey,
          contentKey: meta.contentKey,
          groupKey: fallbackKey,
          hidden: !!node.hidden,
          color: node.color ?? null,
        });
      }
    }
  };
  walk(nodes, null);
  return { groups, items };
}

/** Sets `color` (undefined = hash fallback) on node `id`; returns a new tree. */
export function setNodeColor(
  nodes: EditableTreeNode[],
  id: string,
  color: string | undefined,
): EditableTreeNode[] {
  return setNodeColorDeep(nodes, id, color, false);
}

/** Sets `color` on node `id`; when `applyToAll` the color also applies to every
 *  descendant (the "Apply to all" section affordance). Returns a new tree. */
export function setNodeColorDeep(
  nodes: EditableTreeNode[],
  id: string,
  color: string | undefined,
  applyToAll: boolean,
): EditableTreeNode[] {
  return nodes.map((n) => {
    if (n.id === id) {
      const next = { ...n, color } as EditableTreeNode;
      if (applyToAll) next.children = colorAll(n.children, color);
      return next;
    }
    return { ...n, children: setNodeColorDeep(n.children, id, color, applyToAll) };
  });
}

function colorAll(nodes: EditableTreeNode[], color: string | undefined): EditableTreeNode[] {
  return nodes.map((n) => ({ ...n, color, children: colorAll(n.children, color) }));
}

/**
 * Applications/Admin Settings/User Settings navigation tree editor (D3) — embedded as a tab of
 * the portal-nav settings screen. Renders groups + entry points of one category under
 * a locked root section, with per-row hidden/visible toggles, and saves the FULL
 * tree with explicit Save/Discard (D10).
 */
@Component({
  selector: 'app-shell-nav-editor',
  imports: [DndTree, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
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

  /** Node whose avatar-color palette is open (item AND section rows). */
  protected readonly colorPicker = signal<EditableTreeNode | null>(null);
  protected readonly avatarColors = AVATAR_COLORS;
  /** "Apply to all" for section colors: recolors every descendant. Checked by default. */
  protected readonly applyToAll = signal(true);

  protected readonly dirty = computed(() => JSON.stringify(this.tree()) !== this.baseline());

  protected readonly dndNodes = computed(() => this.tree());

  /** Block title + locked root section label ("... Navigation Tree") for the category. */
  protected readonly treeTitle = computed(() => {
    switch (this.category()) {
      case 'applications':
        return this.i18n.t('navigation.portal.rootSection');
      case 'admin-settings':
        return this.i18n.t('navigation.shell.settingsTreeTitle');
      default:
        return this.i18n.t('navigation.shell.userSettingsTreeTitle');
    }
  });

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

  /** Applies an explicit avatar color to the picked node; null reverts to the
   *  hash fallback. For sections the "Apply to all" toggle extends the color
   *  to every descendant. */
  protected applyAvatarColor(color: string | null): void {
    const target = this.colorPicker();
    if (!target) return;
    const applyAll = target.kind === 'folder' && this.applyToAll();
    this.tree.set(setNodeColorDeep(this.tree(), target.id, color ?? undefined, applyAll));
    this.colorPicker.set(null);
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
