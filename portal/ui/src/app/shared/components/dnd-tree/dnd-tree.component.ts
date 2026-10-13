import {
  ChangeDetectionStrategy,
  Component,
  computed,
  Directive,
  ElementRef,
  inject,
  input,
  output,
  signal,
  AfterViewInit,
} from '@angular/core';
import { DragDropModule, CdkDragDrop } from '@angular/cdk/drag-drop';
import { FormsModule } from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import type { EditableTreeNode } from '../../../core/navigation/navigation.models';
import {
  moveNode,
  moveNodeRelative,
  newTreeId,
  findParent,
} from '../../../core/navigation/navigation.models';
import { I18nService } from '../../../core/i18n/i18n.service';
import { ToastService } from '../../../core/toast/toast.service';
import { ConfirmDialog } from '../confirm-dialog/confirm-dialog.component';

/** Focuses (and selects) its host input as soon as it is rendered. */
@Directive({
  selector: '[appAutofocus]',
  standalone: true,
})
export class Autofocus implements AfterViewInit {
  private readonly el = inject<ElementRef<HTMLInputElement>>(ElementRef);
  ngAfterViewInit(): void {
    this.el.nativeElement.focus();
    this.el.nativeElement.select?.();
  }
}

export interface DropListData {
  parentId: string | null;
  nodeId?: string;
  /** Kind of the row that owns the drop list (drives folder default-drop semantics). */
  targetKind?: 'folder' | 'item';
  into?: boolean;
  empty?: boolean;
  /** Root strip: drop appends at the end of the top level. */
  append?: boolean;
}

/**
 * Reusable drag & drop tree editor (D7). Used by the pinned-apps editor,
 * both shell-tree editors and the Portal Navigation layout editor.
 *
 * Rendering is FLAT: every row is wrapped in its own single-item cdkDropList
 * and each folder row is followed by a thin "drop into folder" strip. All
 * drop-list rects are disjoint, which avoids CDK's first-match sibling
 * resolution that makes nested lists unreachable.
 *
 * Drop semantics: dropping on a section row moves the dragged node INSIDE the
 * section (appended as its last child); holding Shift while dropping places it
 * BELOW the section instead (after its whole subtree). Item rows keep the
 * dropPoint midpoint (before/after). The thin strip under a section row is an
 * explicit inside-target.
 *
 * - The WHOLE row is the drag source; the grip icon is a visual affordance.
 * - Mutations are emitted as a full new tree via `nodesChange`.
 * - New sections start in rename mode with the input focused and selected;
 *   clicking a section label starts a rename too; clicking anywhere else on a
 *   section row expands/collapses it.
 * - Keyboard a11y: per-node move up / down / out / into-previous-section buttons.
 * - Optional locked root section (`rootLabel`): rendered above the tree, never
 *   movable/deletable; its drop strip appends to the top level.
 * - Optional per-row visibility toggle (`showVisibility`): sets `hidden` on the
 *   node; hiding a section effectively hides everything below it (rows render
 *   dimmed). Effective visibility = own `hidden` OR any ancestor's.
 * - Optional avatar color affordance (`showColorPicker`): hovering the avatar
 *   reveals a pencil that emits `colorPick` (items AND sections).
 * - Root policy (`itemsRequireFolder`): shell-tree editors set it so ITEMS can
 *   never live at the root level; the pinned-apps editor allows root items.
 *   Deleting a non-empty section surfaces its children to the parent level;
 *   item children are re-parented into the nearest sibling folder instead
 *   when that level is the root (deletion is rejected when none exists).
 * - Delete policy (`deleteMode`): `folders-only` restricts the bin to section
 *   rows; `folders-and-items` allows items too (pinned = unpin). Deleting a
 *   non-empty section asks for confirmation.
 */
@Component({
  selector: 'app-dnd-tree',
  imports: [DragDropModule, FormsModule, NgTemplateOutlet, Autofocus, ConfirmDialog],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './dnd-tree.component.html',
  styleUrl: './dnd-tree.component.css',
})
export class DndTree {
  readonly nodes = input.required<EditableTreeNode[]>();
  readonly maxDepth = input(3);
  readonly showDelete = input(true);
  readonly showAddFolder = input(true);
  readonly addFolderLabel = input('New section');
  /** When set, renders a locked root section row above the tree. */
  readonly rootLabel = input<string | null>(null);
  /** Renders the per-row hidden/visible eye toggle. */
  readonly showVisibility = input(false);
  /** Renders the per-item avatar-color palette button (emits `colorPick`). */
  readonly showColorPicker = input(false);
  /** When set, ITEMS can never live at the root level (shell-tree editors). */
  readonly itemsRequireFolder = input(false);
  /** Which rows offer the bin: sections only, or sections and apps. */
  readonly deleteMode = input<'folders-only' | 'folders-and-items'>('folders-and-items');
  /** Node ids rendered greyed-out (e.g. refs to deleted entry points). */
  readonly disabledIds = input<ReadonlySet<string>>(new Set());

  readonly nodesChange = output<EditableTreeNode[]>();
  /** Fired when the user clicks a leaf item's main row. */
  readonly itemClick = output<EditableTreeNode>();
  /** Fired when the user hits an item's avatar-color button. */
  readonly colorPick = output<EditableTreeNode>();

  protected readonly i18n = inject(I18nService);
  protected readonly toast = inject(ToastService);

  protected readonly expanded = signal<Set<string>>(new Set());
  protected readonly renamingId = signal<string | null>(null);
  protected readonly renameValue = signal('');
  protected readonly draggingNodeId = signal<string | null>(null);
  protected readonly confirmNode = signal<EditableTreeNode | null>(null);

  protected readonly dropListsDisabled = computed(() => this.renamingId() !== null);
  protected readonly isDragging = computed(() => this.draggingNodeId() !== null);

  protected isExpanded(id: string): boolean {
    return this.expanded().has(id);
  }

  /**
   * Row click: leaf items emit `itemClick`; section rows expand/collapse —
   * except clicks from the label (rename), action buttons, or the rename
   * input, which keep their own behaviour.
   */
  protected onRowClick(node: EditableTreeNode, event: Event): void {
    if (node.kind !== 'folder') {
      this.itemClick.emit(node);
      return;
    }
    const target = event.target as HTMLElement | null;
    if (target?.closest?.('.dnd-label, button, input')) return;
    this.toggleExpanded(node.id, event);
  }

  protected toggleExpanded(id: string, event: Event): void {
    event.stopPropagation();
    this.expanded.update((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  protected startRename(node: EditableTreeNode, event: Event): void {
    event.stopPropagation();
    this.renamingId.set(node.id);
    this.renameValue.set(node.label);
  }

  protected commitRename(): void {
    const id = this.renamingId();
    if (id === null) return;
    const value = this.renameValue().trim();
    this.renamingId.set(null);
    if (!value) return;
    this.emitMutated((tree) => {
      const apply = (nodes: EditableTreeNode[]): boolean => {
        for (const node of nodes) {
          if (node.id === id) {
            node.label = value;
            return true;
          }
          if (apply(node.children)) return true;
        }
        return false;
      };
      apply(tree);
      return tree;
    });
  }

  protected cancelRename(): void {
    this.renamingId.set(null);
  }

  protected canDelete(node: EditableTreeNode): boolean {
    if (!this.showDelete()) return false;
    if (this.deleteMode() === 'folders-only') return node.kind === 'folder';
    return true;
  }

  protected deleteNode(node: EditableTreeNode): void {
    if (node.kind === 'folder' && node.children.length > 0) {
      this.confirmNode.set(node);
      return;
    }
    this.performDelete(node);
  }

  protected confirmDelete(): void {
    const node = this.confirmNode();
    this.confirmNode.set(null);
    if (node) this.performDelete(node);
  }

  protected cancelDelete(): void {
    this.confirmNode.set(null);
  }

  /** Removes the node; a folder's children surface to its parent level. Items
   *  never surface to the root level — they are re-parented into the nearest
   *  sibling folder (mutation rejected when none exists). */
  private performDelete(node: EditableTreeNode): void {
    this.emitMutated((tree) => {
      const parent = findParent(tree, node.id);
      const list = parent ? parent.children : tree;
      const idx = list.findIndex((n) => n.id === node.id);
      if (idx < 0) return null;
      const removed = list[idx];
      if (removed.kind !== 'folder' || removed.children.length === 0) {
        list.splice(idx, 1);
        return tree;
      }
      const items = removed.children.filter((c) => c.kind === 'item');
      const folders = removed.children.filter((c) => c.kind === 'folder');
      // Host lookup must happen BEFORE the removed node is replaced in the list.
      const before = [...list.slice(0, idx).reverse()].find((n) => n.kind === 'folder');
      const after = list.slice(idx + 1).find((n) => n.kind === 'folder');
      list.splice(idx, 1, ...folders);
      if (parent === null && items.length > 0) {
        const host = before ?? after;
        if (!host) return null;
        host.children.push(...items);
      } else if (parent !== null && items.length > 0) {
        list.push(...items);
      }
      return tree;
    });
  }

  protected toggleVisibility(node: EditableTreeNode, event: Event): void {
    event.stopPropagation();
    this.emitMutated((tree) => {
      const apply = (nodes: EditableTreeNode[]): boolean => {
        for (const n of nodes) {
          if (n.id === node.id) {
            n.hidden = !n.hidden;
            return true;
          }
          if (apply(n.children)) return true;
        }
        return false;
      };
      apply(tree);
      return tree;
    });
  }

  protected addFolder(parentId: string | null): void {
    const folder: EditableTreeNode = {
      id: newTreeId('folder'),
      label: this.addFolderLabel(),
      kind: 'folder',
      children: [],
    };
    this.emitMutated((tree) => {
      if (parentId === null) {
        tree.push(folder);
      } else {
        const find = (nodes: EditableTreeNode[]): boolean => {
          for (const node of nodes) {
            if (node.id === parentId) {
              node.children.push(folder);
              return true;
            }
            if (find(node.children)) return true;
          }
          return false;
        };
        find(tree);
      }
      return tree;
    });
    // Start renaming immediately; the input focuses via appAutofocus.
    this.renamingId.set(folder.id);
    this.renameValue.set(folder.label);
    this.expanded.update((s) => {
      const next = new Set(s);
      if (parentId) next.add(parentId);
      next.add(folder.id);
      return next;
    });
  }

  protected onDragStarted(node: EditableTreeNode): void {
    this.draggingNodeId.set(node.id);
  }

  protected onDragEnded(): void {
    this.draggingNodeId.set(null);
  }

  protected onDrop(event: CdkDragDrop<DropListData>): void {
    this.draggingNodeId.set(null);
    const dragged = event.item.data as EditableTreeNode;
    const data = event.container.data;
    const native = event.event as (Event & { shiftKey?: boolean }) | null;
    const shift = native?.shiftKey === true;
    const rootForbidden = this.itemsRequireFolder() && dragged.kind === 'item';
    let next: EditableTreeNode[] | null = null;

    if (data.into && data.append) {
      // Root section strip → append at the end of the top level.
      if (rootForbidden) {
        this.toast.show(this.i18n.t('dnd.rootNotAllowed'));
        return;
      }
      next = moveNode(this.nodes(), dragged.id, null, this.nodes().length, this.maxDepth());
    } else if (data.into) {
      // Drop strip under a folder → append at the end of its children (moveNode clamps the index).
      next = moveNode(
        this.nodes(),
        dragged.id,
        data.parentId,
        Number.MAX_SAFE_INTEGER,
        this.maxDepth(),
      );
    } else if (data.empty) {
      if (rootForbidden) {
        this.toast.show(this.i18n.t('dnd.rootNotAllowed'));
        return;
      }
      next = moveNode(this.nodes(), dragged.id, null, 0, this.maxDepth());
    } else if (data.nodeId) {
      if (data.targetKind === 'folder' && !shift) {
        // Drop on a section moves the dragged node INSIDE it, appended as its last child.
        next = moveNode(
          this.nodes(),
          dragged.id,
          data.nodeId,
          Number.MAX_SAFE_INTEGER,
          this.maxDepth(),
        );
      } else if (data.parentId === null && rootForbidden) {
        // A sibling position next to a root-level row is still the root level.
        this.toast.show(this.i18n.t('dnd.rootNotAllowed'));
        return;
      } else if (data.targetKind === 'folder') {
        // Shift + drop on a section places the dragged node BELOW it (after its subtree).
        next = moveNodeRelative(this.nodes(), dragged.id, data.nodeId, 'after', this.maxDepth());
      } else {
        // Item rows: before/after the hovered node, derived from dropPoint.
        const rowEl =
          event.container.element?.nativeElement?.querySelector<HTMLElement>('[data-node-id]');
        const after = rowEl
          ? event.dropPoint.y >
            rowEl.getBoundingClientRect().top + rowEl.getBoundingClientRect().height / 2
          : false;
        next = moveNodeRelative(
          this.nodes(),
          dragged.id,
          data.nodeId,
          after ? 'after' : 'before',
          this.maxDepth(),
        );
      }
    }
    if (next) {
      this.nodesChange.emit(next);
    } else {
      this.toast.show(this.i18n.t('dnd.moveRejected'));
    }
  }

  protected moveUp(node: EditableTreeNode): void {
    this.emitSiblingShift(node, -1);
  }

  protected moveDown(node: EditableTreeNode): void {
    this.emitSiblingShift(node, 1);
  }

  protected moveOut(node: EditableTreeNode): void {
    const tree = this.nodes();
    const parent = findParent(tree, node.id);
    if (!parent) {
      this.toast.show(this.i18n.t('dnd.moveRejected'));
      return;
    }
    if (this.itemsRequireFolder() && node.kind === 'item' && !findParent(tree, parent.id)) {
      // Moving the item out would leave it at the root level.
      this.toast.show(this.i18n.t('dnd.rootNotAllowed'));
      return;
    }
    this.emitMutated((nextTree) => {
      const nextParent = findParent(nextTree, node.id);
      if (!nextParent) return null;
      const grandparent = findParent(nextTree, nextParent.id);
      const parentIndex = grandparent
        ? grandparent.children.findIndex((c) => c.id === nextParent.id)
        : nextTree.findIndex((n) => n.id === nextParent.id);
      const targetIndex = parentIndex + 1;
      return moveNode(
        nextTree,
        node.id,
        grandparent ? grandparent.id : null,
        targetIndex,
        this.maxDepth(),
      );
    });
  }

  protected moveIn(node: EditableTreeNode): void {
    this.emitMutated((tree) => {
      const parent = findParent(tree, node.id);
      const list = parent ? parent.children : tree;
      const index = list.findIndex((n) => n.id === node.id);
      for (let i = index - 1; i >= 0; i--) {
        const sibling = list[i];
        if (sibling.kind === 'folder') {
          // moveNode itself enforces the depth cap (no double-counted levels).
          return moveNode(tree, node.id, sibling.id, sibling.children.length, this.maxDepth());
        }
      }
      return null;
    });
  }

  /** Move-in is offered only when a previous sibling folder exists and the
   *  move fits the depth cap (same math as moveNode). */
  protected canMoveIn(node: EditableTreeNode): boolean {
    const tree = this.nodes();
    const parent = findParent(tree, node.id);
    const list = parent ? parent.children : tree;
    const index = list.findIndex((n) => n.id === node.id);
    for (let i = index - 1; i >= 0; i--) {
      const sibling = list[i];
      if (sibling.kind === 'folder') {
        return this.subtreeDepthOf(node) + this.depthOfNode(tree, sibling.id) <= this.maxDepth();
      }
    }
    return false;
  }

  private emitSiblingShift(node: EditableTreeNode, delta: number): void {
    this.emitMutated((tree) => {
      const parent = findParent(tree, node.id);
      const list = parent ? parent.children : tree;
      const index = list.findIndex((n) => n.id === node.id);
      const target = index + delta;
      if (index < 0 || target < 0 || target >= list.length) return null;
      const parentId = parent ? parent.id : null;
      return moveNode(tree, node.id, parentId, target, this.maxDepth());
    });
  }

  private depthOfNode(nodes: EditableTreeNode[], id: string, depth = 1): number {
    for (const node of nodes) {
      if (node.id === id) return depth;
      const d = this.depthOfNode(node.children, id, depth + 1);
      if (d > 0) return d;
    }
    return -1;
  }

  private subtreeDepthOf(node: EditableTreeNode): number {
    if (node.children.length === 0) return 1;
    return 1 + Math.max(...node.children.map((c) => this.subtreeDepthOf(c)));
  }

  /**
   * Runs a mutation against a clone of the tree; emits when it changed and
   * shows a toast when the (move) mutation was rejected.
   */
  private emitMutated(mutate: (tree: EditableTreeNode[]) => EditableTreeNode[] | null): void {
    const next = mutate(structuredClone(this.nodes()));
    if (next) {
      this.nodesChange.emit(next);
    } else {
      this.toast.show(this.i18n.t('dnd.moveRejected'));
    }
  }
}
