import { ChangeDetectionStrategy, Component, OnDestroy, TemplateRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { GridBulkAction, GridBulkActionEvent, GridColumn, GridCellContext } from './grid-column';
import { UI_I18N } from '../i18n';

export type { GridBulkAction, GridBulkActionEvent, GridColumn } from './grid-column';
export { GridCellDirective } from './grid-cell.directive';

export type GridSortDirection = 'asc' | 'desc';

export interface GridGroup<T> {
  key: string;
  header: string;
  value: string;
  rows: T[];
}

/**
 * Shared data grid (design-system kit): sortable columns, global search,
 * group-by, per-column filter row toggled from the toolbar, custom cell
 * templates via `gridCell` and an actions zone (`[gridActions]` projection)
 * rendered after the filter toggle. Optional line selection (checkbox first
 * column + bulk-actions dropdown) and clickable rows cover bulk workflows.
 * Styles are component-owned (token-based) so the grid renders identically
 * in the portal shell and in module MFEs.
 */
@Component({
  selector: 'app-data-grid',
  imports: [NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './data-grid.component.html',
  styleUrl: './data-grid.component.css',
})
export class DataGrid<T> implements OnDestroy {
  protected readonly i18n = inject(UI_I18N);

  readonly data = input.required<T[]>();
  readonly columns = input.required<GridColumn<T>[]>();
  readonly searchable = input(true);
  readonly groupable = input(true);
  readonly trackBy = input<(row: T, index: number) => string | number>((_, i) => i);
  /** Empty-state message key; defaults to the kit's own "no results" string. */
  readonly emptyKey = input<string | null>(null);
  /** Renders a checkbox first column and enables the bulk-actions dropdown. */
  readonly selectable = input(false);
  /** Bulk actions offered in the toolbar dropdown (only when `selectable`). */
  readonly bulkActions = input<GridBulkAction[]>([]);
  /** Makes body rows clickable (emits `rowClick`); checkbox/cell controls stop propagation. */
  readonly clickable = input(false);

  /** Emits the selected row ids (via `trackBy`) after every selection change. */
  readonly selectionChange = output<string[]>();
  /** Emits the picked bulk action together with the selected row ids. */
  readonly bulkAction = output<GridBulkActionEvent>();
  /** Emits the clicked row (only when `clickable`). */
  readonly rowClick = output<T>();

  protected readonly sortColumn = signal<string | null>(null);
  protected readonly sortDirection = signal<GridSortDirection>('asc');
  protected readonly globalSearch = signal('');
  protected readonly columnFilters = signal<Record<string, string>>({});
  protected readonly groupBy = signal<string | null>(null);
  protected readonly collapsedGroups = signal<Set<string>>(new Set());
  protected readonly showFilters = signal(false);
  protected readonly cellTemplates = signal<Record<string, TemplateRef<GridCellContext<T>>>>({});
  /** Selected row ids (keys of `trackBy`, stringified) — only populated when `selectable`. */
  protected readonly selectedIds = signal<Set<string>>(new Set());

  constructor() {
    // Prunes ids that left the dataset (e.g. after a delete + refresh).
    effect(() => {
      const live = new Set(this.data().map((row, index) => String(this.trackBy()(row, index))));
      const selected = this.selectedIds();
      if ([...selected].every((id) => live.has(id))) return;
      this.selectedIds.set(new Set([...selected].filter((id) => live.has(id))));
    });
  }

  protected readonly groupableColumns = computed(() =>
    this.columns().filter((c) => c.sortable !== false),
  );

  protected readonly visibleRows = computed(() => {
    const q = this.globalSearch().trim().toLowerCase();
    const filters = this.columnFilters();
    const cols = this.columns();
    return this.data().filter((row) => {
      if (q) {
        const match = cols.some((c) => {
          if (c.searchable === false) return false;
          return this.accessorValue(c, row).toLowerCase().includes(q);
        });
        if (!match) return false;
      }
      for (const c of cols) {
        const filterVal = (filters[c.key] ?? '').trim().toLowerCase();
        if (!filterVal) continue;
        if (c.searchable === false) continue;
        if (!this.accessorValue(c, row).includes(filterVal)) return false;
      }
      return true;
    });
  });

  protected readonly sortedRows = computed(() => {
    const col = this.sortColumn();
    if (!col) return this.visibleRows();
    const dir = this.sortDirection();
    const column = this.columns().find((c) => c.key === col);
    if (!column || column.sortable === false) return this.visibleRows();
    return [...this.visibleRows()].sort((a, b) => {
      const av = this.accessorValue(column, a);
      const bv = this.accessorValue(column, b);
      const cmp = this.compareValues(av, bv);
      return dir === 'asc' ? cmp : -cmp;
    });
  });

  protected readonly groupedRows = computed<GridGroup<T>[]>(() => {
    const groupKey = this.groupBy();
    if (!groupKey) return [];
    const column = this.columns().find((c) => c.key === groupKey);
    if (!column) return [];
    const groups = new Map<string, T[]>();
    for (const row of this.sortedRows()) {
      const gv = String(column.accessor(row) ?? '');
      const existing = groups.get(gv);
      if (existing) existing.push(row);
      else groups.set(gv, [row]);
    }
    return [...groups.entries()].map(([value, rows]) => ({
      key: value,
      header: column.header,
      value,
      rows,
    }));
  });

  protected trackRow(row: T, index: number): string | number {
    return this.trackBy()(row, index);
  }

  protected readonly hasGrouping = computed(() => this.groupBy() !== null);

  protected readonly isEmpty = computed(() => this.visibleRows().length === 0);

  /** Physical column count (data columns + optional selection gutter). */
  protected readonly columnCount = computed(
    () => this.columns().length + (this.selectable() ? 1 : 0),
  );

  /** Row id used for selection state (stable while `trackBy` is stable). */
  protected rowIdOf(row: T): string {
    return String(this.trackBy()(row, this.data().indexOf(row)));
  }

  /** Ids of the currently visible (filtered + sorted) rows. */
  protected readonly visibleIds = computed(() => this.sortedRows().map((row) => this.rowIdOf(row)));

  protected readonly allSelected = computed(() => {
    const ids = this.visibleIds();
    if (ids.length === 0) return false;
    const selected = this.selectedIds();
    return ids.every((id) => selected.has(id));
  });

  protected readonly someSelected = computed(() => {
    if (this.allSelected()) return false;
    const selected = this.selectedIds();
    return this.visibleIds().some((id) => selected.has(id));
  });

  protected readonly selectedCount = computed(() => this.selectedIds().size);

  protected readonly showBulk = computed(
    () => this.selectable() && this.bulkActions().length > 0,
  );

  protected isSelected(id: string): boolean {
    return this.selectedIds().has(id);
  }

  protected selectedCountLabel(): string {
    return this.i18n.t('grid.selectedCount', { count: this.selectedCount() });
  }

  protected bulkLabel(action: GridBulkAction): string {
    return action.labelKey ? this.i18n.t(action.labelKey) : action.label;
  }

  protected onToggleRow(id: string, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const next = new Set(this.selectedIds());
    if (checked) next.add(id);
    else next.delete(id);
    this.selectedIds.set(next);
    this.selectionChange.emit([...next]);
  }

  protected onToggleAll(event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const next = new Set(this.selectedIds());
    if (checked) for (const id of this.visibleIds()) next.add(id);
    else for (const id of this.visibleIds()) next.delete(id);
    this.selectedIds.set(next);
    this.selectionChange.emit([...next]);
  }

  /** Bulk menu open state; document listeners close it on outside click / Escape. */
  protected readonly bulkOpen = signal(false);
  private bulkCleanup: (() => void) | null = null;

  ngOnDestroy(): void {
    this.closeBulkMenu();
  }

  protected toggleBulkMenu(): void {
    if (this.bulkOpen()) {
      this.closeBulkMenu();
      return;
    }
    this.bulkOpen.set(true);
    const onPointer = (event: PointerEvent): void => {
      const target = event.target as HTMLElement | null;
      if (target instanceof HTMLElement && target.closest('.grid-bulk')) return;
      this.closeBulkMenu();
    };
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') this.closeBulkMenu();
    };
    document.addEventListener('pointerdown', onPointer);
    document.addEventListener('keydown', onKey);
    this.bulkCleanup = () => {
      document.removeEventListener('pointerdown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }

  protected closeBulkMenu(): void {
    this.bulkCleanup?.();
    this.bulkCleanup = null;
    this.bulkOpen.set(false);
  }

  protected pickBulkAction(id: string): void {
    this.closeBulkMenu();
    if (!id) return;
    this.bulkAction.emit({ id, ids: [...this.selectedIds()] });
  }

  /** Clears the selection (call after a bulk action completes). */
  clearSelection(): void {
    if (this.selectedIds().size === 0) return;
    this.selectedIds.set(new Set());
    this.selectionChange.emit([]);
  }

  protected onRowClick(row: T): void {
    if (this.clickable()) this.rowClick.emit(row);
  }

  protected emptyLabel(): string {
    const key = this.emptyKey();
    return this.i18n.t(key ?? 'grid.noResults');
  }

  protected toggleSort(column: GridColumn<T>): void {
    if (column.sortable === false) return;
    if (this.sortColumn() === column.key) {
      if (this.sortDirection() === 'asc') this.sortDirection.set('desc');
      else {
        this.sortColumn.set(null);
        this.sortDirection.set('asc');
      }
    } else {
      this.sortColumn.set(column.key);
      this.sortDirection.set('asc');
    }
  }

  protected onGlobalSearch(event: Event): void {
    this.globalSearch.set((event.target as HTMLInputElement).value);
  }

  protected onColumnFilter(key: string, event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    const next = { ...this.columnFilters() };
    if (value.trim()) next[key] = value;
    else delete next[key];
    this.columnFilters.set(next);
  }

  protected onGroupByChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.groupBy.set(value || null);
    this.collapsedGroups.set(new Set());
  }

  protected toggleFilters(): void {
    this.showFilters.update((v) => !v);
  }

  protected toggleGroup(key: string): void {
    const next = new Set(this.collapsedGroups());
    if (!next.delete(key)) next.add(key);
    this.collapsedGroups.set(next);
  }

  protected formatCell(column: GridColumn<T>, row: T): string {
    const value = column.accessor(row);
    if (column.format) return column.format(value, row);
    return String(value ?? '');
  }

  protected headerLabel(column: GridColumn<T>): string {
    return column.headerKey ? this.i18n.t(column.headerKey) : column.header;
  }

  protected ariaSort(column: GridColumn<T>): 'ascending' | 'descending' | 'none' {
    if (this.sortColumn() !== column.key) return 'none';
    return this.sortDirection() === 'asc' ? 'ascending' : 'descending';
  }

  protected sortIndicator(column: GridColumn<T>): string {
    if (this.sortColumn() !== column.key) return '';
    return this.sortDirection() === 'asc' ? '▲' : '▼';
  }

  protected hasActiveFilters(): boolean {
    return Object.values(this.columnFilters()).some((v) => v.trim());
  }

  registerCell(key: string, template: TemplateRef<GridCellContext<T>>): void {
    this.cellTemplates.update((prev) => ({ ...prev, [key]: template }));
  }

  protected getCell(columnKey: string): TemplateRef<GridCellContext<T>> | null {
    return this.cellTemplates()[columnKey] ?? null;
  }

  private accessorValue(column: GridColumn<T>, row: T): string {
    return String(column.accessor(row) ?? '').toLowerCase();
  }

  private compareValues(a: unknown, b: unknown): number {
    if (typeof a === 'number' && typeof b === 'number') return a - b;
    return String(a).localeCompare(String(b));
  }
}
