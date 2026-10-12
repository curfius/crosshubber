import { ChangeDetectionStrategy, Component, TemplateRef, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NgTemplateOutlet } from '@angular/common';
import { GridColumn, GridCellContext } from './grid-column';

export type { GridColumn } from './grid-column';
export { GridCellDirective } from './grid-cell.directive';
import { I18nService } from '../../../core/i18n/i18n.service';

export type GridSortDirection = 'asc' | 'desc';

export interface GridGroup<T> {
  key: string;
  header: string;
  value: string;
  rows: T[];
}

@Component({
  selector: 'app-data-grid',
  imports: [FormsModule, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './data-grid.component.html',
  styleUrl: './data-grid.component.css',
})
export class DataGrid<T> {
  protected readonly i18n = inject(I18nService);

  readonly data = input.required<T[]>();
  readonly columns = input.required<GridColumn<T>[]>();
  readonly searchable = input(true);
  readonly groupable = input(true);
  readonly trackBy = input<(row: T, index: number) => string | number>((_, i) => i);

  protected readonly sortColumn = signal<string | null>(null);
  protected readonly sortDirection = signal<GridSortDirection>('asc');
  protected readonly globalSearch = signal('');
  protected readonly columnFilters = signal<Record<string, string>>({});
  protected readonly groupBy = signal<string | null>(null);
  protected readonly collapsedGroups = signal<Set<string>>(new Set());
  protected readonly showFilters = signal(false);
  protected readonly cellTemplates = signal<Record<string, TemplateRef<GridCellContext<T>>>>({});

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
