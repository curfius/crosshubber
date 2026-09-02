import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';

export interface DsTreeNode {
  id: string;
  label: string;
  meta?: string;
  dotColor?: string;
  actionable?: boolean;
  children?: DsTreeNode[];
  data?: unknown;
}

@Component({
  selector: 'app-ds-tree',
  imports: [NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div [class]="'ds-tree' + (boxed() ? '' : ' ds-tree-unboxed')">
      @if (searchable()) {
        <div class="ds-tree-header ds-search">
          <svg class="ds-search-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8"/><path d="m21 21-4.3-4.3"/>
          </svg>
          <input
            type="text"
            class="ds-input"
            placeholder="Filter..."
            [value]="query()"
            (input)="query.set($any($event.target).value)" />
        </div>
      }

      @if (visibleRoots().length === 0) {
        <p class="ds-caption" style="padding: var(--portal-space-2) var(--portal-space-3)">No items</p>
      } @else {
        <ng-template #level let-nodes let-depth="depth">
          @for (n of nodes; track n.id) {
            <button
              type="button"
              class="ds-tree-node"
              [class.ds-tree-node-selected]="n.id === selectedId()"
              (click)="onNodeClick(n)">
              @if (hasChildren(n)) {
                <span
                  class="ds-tree-chevron"
                  [class.open]="isExpanded(n.id)"
                  (click)="toggleExpand(n.id); $event.stopPropagation()"
                  aria-hidden="true">
                  <svg width="10" height="10" viewBox="0 0 16 16" fill="currentColor"><path d="M6 4l4 4-4 4"/></svg>
                </span>
              }
              @if (n.dotColor) {
                <span class="ds-tree-dot" [style.background]="n.dotColor"></span>
              }
              <span class="ds-tree-label">{{ n.label }}</span>
              @if (n.meta) {
                <span class="ds-tree-meta">{{ n.meta }}</span>
              }
              @if (n.actionable) {
                <span
                  class="ds-tree-action"
                  role="button"
                  aria-label="Delete"
                  (click)="actionClick.emit(n); $event.stopPropagation()">
                  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/>
                  </svg>
                </span>
              }
            </button>
            @if (hasChildren(n) && isExpanded(n.id)) {
              <div style="padding-left: 12px">
                <ng-container *ngTemplateOutlet="level; context: { $implicit: n.children, depth: depth + 1 }" />
              </div>
            }
          }
        </ng-template>
        <ng-container *ngTemplateOutlet="level; context: { $implicit: visibleRoots(), depth: 0 }" />
      }

      <ng-content select="[dsTreeFooter]" />
    </div>
  `,
})
export class DsTree {
  readonly nodes = input.required<DsTreeNode[]>();
  readonly selectedId = input<string | null>(null);
  readonly boxed = input(true);
  readonly searchable = input(false);

  readonly nodeSelected = output<DsTreeNode>();
  readonly actionClick = output<DsTreeNode>();

  protected readonly query = signal('');
  protected readonly expanded = signal<Set<string>>(new Set());


  protected readonly visibleRoots = computed(() => this.filterNodes(this.nodes(), this.query()));

  protected hasChildren(n: DsTreeNode): boolean {
    return !!n.children?.length;
  }

  protected isExpanded(id: string): boolean {
    if (this.query().trim()) return true;
    return this.expanded().has(id);
  }

  protected toggleExpand(id: string): void {
    const next = new Set(this.expanded());
    if (!next.delete(id)) next.add(id);
    this.expanded.set(next);
  }

  /** Groups toggle expansion; leaves emit selection. */
  protected onNodeClick(n: DsTreeNode): void {
    if (this.hasChildren(n)) {
      this.toggleExpand(n.id);
      return;
    }
    this.nodeSelected.emit(n);
  }


  /** Keep a node when it or any descendant matches the query. */
  private filterNodes(nodes: DsTreeNode[], q: string): DsTreeNode[] {
    const needle = q.trim().toLowerCase();
    if (!needle) return nodes;
    const out: DsTreeNode[] = [];
    for (const n of nodes) {
      const children = n.children ? this.filterNodes(n.children, q) : [];
      if (children.length > 0 || n.label.toLowerCase().includes(needle)) {
        out.push({ ...n, children });
      }
    }
    return out;
  }
}
