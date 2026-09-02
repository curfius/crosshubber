import { Component, computed, Directive, ElementRef, inject, input, output, signal, AfterViewInit } from '@angular/core';
import { DragDropModule, CdkDragDrop } from '@angular/cdk/drag-drop';
import { FormsModule } from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import type { EditableTreeNode } from '../../../core/navigation/navigation.models';
import { moveNode, moveNodeRelative, newTreeId, findParent } from '../../../core/navigation/navigation.models';

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
  into?: boolean;
  empty?: boolean;
}

/**
 * Reusable drag & drop tree editor (D7). Used by the pinned-apps editor,
 * both shell-tree editors and the Portal Navigation layout editor.
 *
 * Rendering is FLAT: every row is wrapped in its own single-item cdkDropList
 * and each folder row is followed by a thin "drop into folder" strip. All
 * drop-list rects are disjoint, which avoids CDK's first-match sibling
 * resolution that makes nested lists unreachable. Placement is derived from
 * `event.dropPoint` vs the hovered row's midpoint (before/after).
 *
 * - Mutations are emitted as a full new tree via `nodesChange`.
 * - New folders start in rename mode with the input focused and selected.
 * - Keyboard a11y: per-node move up / down / out / into-previous-folder buttons.
 */
@Component({
  selector: 'app-dnd-tree',
  imports: [DragDropModule, FormsModule, NgTemplateOutlet, Autofocus],
  templateUrl: './dnd-tree.component.html',
  styleUrl: './dnd-tree.component.css',
})
export class DndTree {
  readonly nodes = input.required<EditableTreeNode[]>();
  readonly maxDepth = input(3);
  readonly showDelete = input(true);
  readonly showAddFolder = input(true);
  readonly addFolderLabel = input('New section');
  /** Node ids rendered greyed-out (e.g. refs to deleted entry points). */
  readonly disabledIds = input<ReadonlySet<string>>(new Set());

  readonly nodesChange = output<EditableTreeNode[]>();
  /** Fired when the user clicks a leaf item's main row. */
  readonly itemClick = output<EditableTreeNode>();

  protected readonly expanded = signal<Set<string>>(new Set());
  protected readonly renamingId = signal<string | null>(null);
  protected readonly renameValue = signal('');
  protected readonly draggingNodeId = signal<string | null>(null);

  protected readonly dropListsDisabled = computed(() => this.renamingId() !== null);
  protected readonly isDragging = computed(() => this.draggingNodeId() !== null);

  protected isExpanded(id: string): boolean {
    return this.expanded().has(id);
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

  protected deleteNode(node: EditableTreeNode): void {
    if (node.kind === 'folder' && node.children.length > 0) {
      if (!window.confirm(`Delete "${node.label}" and its contents?`)) return;
    }
    this.emitMutated((tree) => {
      const remove = (nodes: EditableTreeNode[]): boolean => {
        const idx = nodes.findIndex((n) => n.id === node.id);
        if (idx >= 0) {
          nodes.splice(idx, 1);
          return true;
        }
        for (const child of nodes) if (remove(child.children)) return true;
        return false;
      };
      remove(tree);
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
    let next: EditableTreeNode[] | null = null;

    if (data.into) {
      // Drop strip under a folder → first child of that folder.
      next = moveNode(this.nodes(), dragged.id, data.parentId, 0, this.maxDepth());
    } else if (data.empty) {
      next = moveNode(this.nodes(), dragged.id, null, 0, this.maxDepth());
    } else if (data.nodeId) {
      // Row list → before/after the hovered node, derived from dropPoint.
      const rowEl = event.container.element.nativeElement.querySelector<HTMLElement>('[data-node-id]');
      const after = rowEl
        ? event.dropPoint.y > rowEl.getBoundingClientRect().top + rowEl.getBoundingClientRect().height / 2
        : false;
      next = moveNodeRelative(this.nodes(), dragged.id, data.nodeId, after ? 'after' : 'before', this.maxDepth());
    }
    if (next) this.nodesChange.emit(next);
  }

  protected moveUp(node: EditableTreeNode): void {
    this.emitSiblingShift(node, -1);
  }

  protected moveDown(node: EditableTreeNode): void {
    this.emitSiblingShift(node, 1);
  }

  protected moveOut(node: EditableTreeNode): void {
    this.emitMutated((tree) => {
      const parent = findParent(tree, node.id);
      if (!parent) return null;
      const grandparent = findParent(tree, parent.id);
      const parentIndex = grandparent
        ? grandparent.children.findIndex((c) => c.id === parent.id)
        : tree.findIndex((n) => n.id === parent.id);
      const targetIndex = parentIndex + 1;
      return moveNode(tree, node.id, grandparent ? grandparent.id : null, targetIndex, this.maxDepth());
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
          const targetDepth = this.depthOfNode(tree, sibling.id) + 1;
          const nodeDepth = this.subtreeDepthOf(node);
          if (nodeDepth + targetDepth > this.maxDepth()) return null;
          return moveNode(tree, node.id, sibling.id, sibling.children.length, this.maxDepth());
        }
      }
      return null;
    });
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

  /** Runs a mutation against a clone of the tree; emits when it changed. */
  private emitMutated(mutate: (tree: EditableTreeNode[]) => EditableTreeNode[] | null): void {
    const next = mutate(structuredClone(this.nodes()));
    if (next) this.nodesChange.emit(next);
  }
}
