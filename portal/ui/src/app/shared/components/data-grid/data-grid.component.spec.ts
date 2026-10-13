import { NgTemplateOutlet } from '@angular/common';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, it, expect } from 'vitest';
import { DataGrid } from './data-grid.component';
import { GridColumn } from './grid-column';
import { UI_I18N, UiI18n } from '../i18n';

interface TestRow {
  name: string;
  category: string;
  value: number;
}

const rows: TestRow[] = [
  { name: 'Charlie', category: 'B', value: 30 },
  { name: 'Alice', category: 'A', value: 10 },
  { name: 'Bob', category: 'B', value: 20 },
];

const columns: GridColumn<TestRow>[] = [
  { key: 'name', header: 'Name', accessor: (r) => r.name },
  { key: 'category', header: 'Category', accessor: (r) => r.category },
  { key: 'value', header: 'Value', accessor: (r) => r.value, align: 'right' },
];

/** Passes keys through raw so assertions stay literal. */
const passthroughI18n: UiI18n = {
  t: (key) => key,
  formatDate: (value, options) => new Intl.DateTimeFormat('en-GB', options).format(value as Date),
  formatNumber: (value, options) => new Intl.NumberFormat('en-GB', options).format(value),
  locale: () => 'en-GB',
};

function setup(data: TestRow[] = rows, cols: GridColumn<TestRow>[] = columns) {
  TestBed.configureTestingModule({
    imports: [DataGrid],
    providers: [{ provide: UI_I18N, useValue: passthroughI18n }],
  });
  const fixture = TestBed.createComponent(DataGrid<TestRow>);
  fixture.componentRef.setInput('data', data);
  fixture.componentRef.setInput('columns', cols);
  fixture.componentRef.setInput('trackBy', (r: TestRow, _index: number) => r.name);
  fixture.detectChanges();
  return fixture;
}

function getRowNames(fixture: ReturnType<typeof setup>): string[] {
  const tbody = fixture.nativeElement.querySelector('tbody');
  return [...tbody.querySelectorAll('tr:not(.grid-group-header):not(:has(.grid-empty))')].map(
    (tr) => tr.querySelector('td')?.textContent?.trim() ?? '',
  );
}

function clickHeader(fixture: ReturnType<typeof setup>, key: string): void {
  const th = [...fixture.nativeElement.querySelectorAll('thead tr:first-child th')].find((th) =>
    th.textContent?.includes(key),
  ) as HTMLElement;
  const btn = th.querySelector('button') ?? th;
  btn.click();
  fixture.detectChanges();
}

describe('DataGrid', () => {
  it('renders all rows', () => {
    const fixture = setup();
    expect(getRowNames(fixture)).toEqual(['Charlie', 'Alice', 'Bob']);
  });

  it('sorts ascending on first header click', () => {
    const fixture = setup();
    clickHeader(fixture, 'Name');
    expect(getRowNames(fixture)).toEqual(['Alice', 'Bob', 'Charlie']);
  });

  it('sorts descending on second header click', () => {
    const fixture = setup();
    clickHeader(fixture, 'Name');
    clickHeader(fixture, 'Name');
    expect(getRowNames(fixture)).toEqual(['Charlie', 'Bob', 'Alice']);
  });

  it('clears sort on third header click', () => {
    const fixture = setup();
    clickHeader(fixture, 'Name');
    clickHeader(fixture, 'Name');
    clickHeader(fixture, 'Name');
    expect(getRowNames(fixture)).toEqual(['Charlie', 'Alice', 'Bob']);
  });

  it('sorts numbers numerically', () => {
    const fixture = setup();
    clickHeader(fixture, 'Value');
    const tbody = fixture.nativeElement.querySelector('tbody');
    const values = [...tbody.querySelectorAll('tr')].map(
      (tr) => tr.querySelectorAll('td')[2]?.textContent?.trim() ?? '',
    );
    expect(values).toEqual(['10', '20', '30']);
  });

  it('filters rows by global search (case-insensitive)', () => {
    const fixture = setup();
    const input = fixture.nativeElement.querySelector('input[type="search"]') as HTMLInputElement;
    input.value = 'ali';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(getRowNames(fixture)).toEqual(['Alice']);
  });

  it('shows empty state when no rows match search', () => {
    const fixture = setup();
    const input = fixture.nativeElement.querySelector('input[type="search"]') as HTMLInputElement;
    input.value = 'zzz';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.grid-empty')).not.toBeNull();
    expect(getRowNames(fixture)).toEqual([]);
  });

  it('filters rows by per-column filter', () => {
    const fixture = setup();
    const toggle = fixture.nativeElement.querySelector('.grid-filter-toggle') as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();
    const filterInputs = fixture.nativeElement.querySelectorAll('.grid-filter-input');
    const nameFilter = filterInputs[0] as HTMLInputElement;
    nameFilter.value = 'bob';
    nameFilter.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(getRowNames(fixture)).toEqual(['Bob']);
  });

  it('groups rows by a column and renders group headers', () => {
    const fixture = setup();
    const select = fixture.nativeElement.querySelector('#grid-groupby') as HTMLSelectElement;
    select.value = 'category';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const groupHeaders = fixture.nativeElement.querySelectorAll('.grid-group-header');
    expect(groupHeaders.length).toBe(2);
    const headerText = [...groupHeaders].map((h) => h.textContent);
    expect(headerText.some((t) => t?.includes('A'))).toBe(true);
    expect(headerText.some((t) => t?.includes('B'))).toBe(true);
  });

  it('collapses and expands a group', () => {
    const fixture = setup();
    const select = fixture.nativeElement.querySelector('#grid-groupby') as HTMLSelectElement;
    select.value = 'category';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const toggles = fixture.nativeElement.querySelectorAll('.grid-group-toggle');
    const aliceToggle = [...toggles].find((t) => t.textContent?.includes('A')) as HTMLButtonElement;
    aliceToggle.click();
    fixture.detectChanges();
    expect(getRowNames(fixture)).not.toContain('Alice');
    aliceToggle.click();
    fixture.detectChanges();
    expect(getRowNames(fixture)).toContain('Alice');
  });

  it('applies format function to cell display', () => {
    const cols: GridColumn<TestRow>[] = [
      { key: 'name', header: 'Name', accessor: (r) => r.name },
      {
        key: 'value',
        header: 'Value',
        accessor: (r) => r.value,
        format: (v) => `${v} pts`,
      },
    ];
    const fixture = setup(rows, cols);
    const tbody = fixture.nativeElement.querySelector('tbody');
    const firstRowCells = tbody.querySelectorAll('tr:first-child td');
    expect(firstRowCells[1]?.textContent?.trim()).toBe('30 pts');
  });

  it('does not sort non-sortable columns', () => {
    const cols: GridColumn<TestRow>[] = [
      { key: 'name', header: 'Name', accessor: (r) => r.name, sortable: false },
    ];
    const fixture = setup(rows, cols);
    clickHeader(fixture, 'Name');
    expect(getRowNames(fixture)).toEqual(['Charlie', 'Alice', 'Bob']);
  });

  it('hides toolbar when searchable and groupable are false', () => {
    TestBed.configureTestingModule({ imports: [DataGrid] });
    const fixture = TestBed.createComponent(DataGrid<TestRow>);
    fixture.componentRef.setInput('data', rows);
    fixture.componentRef.setInput('columns', columns);
    fixture.componentRef.setInput('searchable', false);
    fixture.componentRef.setInput('groupable', false);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.grid-toolbar')).toBeNull();
  });

  it('registers and retrieves cell template', () => {
    TestBed.configureTestingModule({ imports: [DataGrid] });
    const fixture = TestBed.createComponent(DataGrid<TestRow>);
    fixture.componentRef.setInput('data', rows);
    fixture.componentRef.setInput('columns', columns);
    fixture.componentRef.setInput('trackBy', (r: TestRow, _index: number) => r.name);
    fixture.detectChanges();

    const grid = fixture.componentInstance as unknown as {
      registerCell(key: string, template: unknown): void;
      getCell(key: string): unknown;
    };
    const mockTemplate = {} as never;
    grid.registerCell('name', mockTemplate);

    expect(grid.getCell('name')).toBe(mockTemplate);
    expect(grid.getCell('other')).toBeNull();
  });

  it('falls back to formatCell when no template registered', () => {
    const fixture = setup();
    const tbody = fixture.nativeElement.querySelector('tbody');
    const firstCell = tbody.querySelector('tr:first-child td');
    expect(firstCell?.textContent?.trim()).toBe('Charlie');
  });

  it('translates header via headerKey when provided', () => {
    const cols: GridColumn<TestRow>[] = [
      { key: 'name', header: 'Name', headerKey: 'grid.groupBy', accessor: (r) => r.name },
    ];
    const fixture = setup(rows, cols);
    const th = fixture.nativeElement.querySelector('thead th');
    expect(th?.textContent).toContain('grid.groupBy');
  });

  it('projects gridActions content into the toolbar', async () => {
    @Component({
      imports: [DataGrid],
      template: `
        <app-data-grid [data]="rows" [columns]="cols" [trackBy]="trackBy">
          <button gridActions type="button">Create New</button>
        </app-data-grid>
      `,
    })
    class Host {
      rows = rows;
      cols = columns;
      trackBy = (r: TestRow) => r.name;
    }
    TestBed.configureTestingModule({
      imports: [Host],
      providers: [{ provide: UI_I18N, useValue: passthroughI18n }],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const zone = fixture.nativeElement.querySelector('.grid-actions');
    expect(zone).not.toBeNull();
    const btn = zone.querySelector('button');
    expect(btn?.textContent?.trim()).toBe('Create New');
    const toolbar = fixture.nativeElement.querySelector('.grid-toolbar');
    const before = [...toolbar.children].filter(
      (el: Element) => !el.classList.contains('grid-actions'),
    );
    expect(before[before.length - 1]?.classList.contains('grid-filter-toggle')).toBe(true);
  });

  it('renders the actions zone div in the toolbar', () => {
    const fixture = setup();
    const zone = fixture.nativeElement.querySelector('.grid-actions');
    expect(zone).not.toBeNull();
    const toolbar = fixture.nativeElement.querySelector('.grid-toolbar');
    const children = [...toolbar.children].filter(
      (el: Element) => !el.classList.contains('grid-actions'),
    );
    const last = children[children.length - 1];
    expect(last?.classList.contains('grid-filter-toggle')).toBe(true);
  });

  it('renders custom emptyKey message', () => {
    const fixture = setup([], columns);
    fixture.componentRef.setInput('emptyKey', 'custom.empty');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.grid-empty')?.textContent?.trim()).toBe(
      'custom.empty',
    );
  });

  it('uses literal header when no headerKey', () => {
    const cols: GridColumn<TestRow>[] = [{ key: 'name', header: 'Name', accessor: (r) => r.name }];
    const fixture = setup(rows, cols);
    const th = fixture.nativeElement.querySelector('thead th');
    expect(th?.textContent).toContain('Name');
  });

  function setupSelectable(
    data: TestRow[] = rows,
    opts: { bulkLabels?: string[]; clickable?: boolean } = {},
  ) {
    TestBed.configureTestingModule({
      imports: [DataGrid],
      providers: [{ provide: UI_I18N, useValue: passthroughI18n }],
    });
    const fixture = TestBed.createComponent(DataGrid<TestRow>);
    fixture.componentRef.setInput('data', data);
    fixture.componentRef.setInput('columns', columns);
    fixture.componentRef.setInput('trackBy', (r: TestRow, _index: number) => r.name);
    fixture.componentRef.setInput('selectable', true);
    if (opts.clickable) fixture.componentRef.setInput('clickable', true);
    fixture.componentRef.setInput(
      'bulkActions',
      (opts.bulkLabels ?? ['Delete']).map((label, i) => ({ id: `a${i}`, label })),
    );
    fixture.detectChanges();
    return fixture;
  }

  it('renders no selection column by default', () => {
    const fixture = setup();
    expect(fixture.nativeElement.querySelector('.grid-select-col')).toBeNull();
    expect(fixture.nativeElement.querySelector('.grid-bulk')).toBeNull();
  });

  it('renders the selection column and bulk dropdown when selectable', () => {
    const fixture = setupSelectable();
    expect(fixture.nativeElement.querySelector('thead .grid-select-col')).not.toBeNull();
    expect(fixture.nativeElement.querySelectorAll('tbody .grid-select-col').length).toBe(3);
    expect(fixture.nativeElement.querySelector('.grid-bulk-btn')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.grid-bulk-caret')).not.toBeNull();
    const order = [...fixture.nativeElement.querySelector('.grid-toolbar').children].map(
      (el: Element) => el.className,
    );
    expect(order.indexOf('grid-bulk')).toBeGreaterThan(
      order.findIndex((c: string) => c.includes('grid-filter-toggle')),
    );
  });

  it('toggles row selection and emits selectionChange', () => {
    const fixture = setupSelectable();
    const emitted: string[][] = [];
    fixture.componentInstance.selectionChange.subscribe((ids) => emitted.push(ids));
    const boxes = fixture.nativeElement.querySelectorAll(
      'tbody tr:first-child .grid-select-col input',
    ) as NodeListOf<HTMLInputElement>;
    boxes[0].checked = true;
    boxes[0].dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(emitted).toEqual([['Charlie']]);
    // Passthrough i18n returns raw keys (no interpolation) — assert the count label key renders.
    expect(fixture.nativeElement.querySelector('.grid-bulk-btn')?.textContent).toContain(
      'grid.selectedCount',
    );
  });

  it('selects all visible rows via the header checkbox', () => {
    const fixture = setupSelectable();
    const emitted: string[][] = [];
    fixture.componentInstance.selectionChange.subscribe((ids) => emitted.push(ids));
    const header = fixture.nativeElement.querySelector('#grid-select-all') as HTMLInputElement;
    header.checked = true;
    header.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(emitted[0]?.sort()).toEqual(['Alice', 'Bob', 'Charlie']);
    header.checked = false;
    header.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(emitted[1]).toEqual([]);
  });

  it('marks the header checkbox indeterminate on partial selection', () => {
    const fixture = setupSelectable();
    const boxes = fixture.nativeElement.querySelectorAll(
      'tbody .grid-select-col input',
    ) as NodeListOf<HTMLInputElement>;
    boxes[0].checked = true;
    boxes[0].dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(
      (fixture.nativeElement.querySelector('#grid-select-all') as HTMLInputElement).indeterminate,
    ).toBe(true);
  });

  it('disables bulk actions with no selection and emits ids on pick', () => {
    const fixture = setupSelectable();
    const emitted: { id: string; ids: string[] }[] = [];
    fixture.componentInstance.bulkAction.subscribe((e) => emitted.push(e));
    const btn = fixture.nativeElement.querySelector('.grid-bulk-btn') as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('.grid-bulk-menu')).toBeNull();
    const boxes = fixture.nativeElement.querySelectorAll(
      'tbody .grid-select-col input',
    ) as NodeListOf<HTMLInputElement>;
    boxes[1].checked = true;
    boxes[1].dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(btn.disabled).toBe(false);
    btn.click();
    fixture.detectChanges();
    const item = fixture.nativeElement.querySelector('.grid-bulk-item') as HTMLButtonElement;
    expect(item?.textContent?.trim()).toBe('Delete');
    item.click();
    fixture.detectChanges();
    expect(emitted).toEqual([{ id: 'a0', ids: ['Alice'] }]);
    expect(fixture.nativeElement.querySelector('.grid-bulk-menu')).toBeNull();
  });

  it('toggles the bulk menu closed on toggle, Escape, and outside click', () => {
    const fixture = setupSelectable();
    const boxes = fixture.nativeElement.querySelectorAll(
      'tbody .grid-select-col input',
    ) as NodeListOf<HTMLInputElement>;
    boxes[0].checked = true;
    boxes[0].dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const btn = fixture.nativeElement.querySelector('.grid-bulk-btn') as HTMLButtonElement;
    btn.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.grid-bulk-menu')).not.toBeNull();
    btn.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.grid-bulk-menu')).toBeNull();
    btn.click();
    fixture.detectChanges();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.grid-bulk-menu')).toBeNull();
    btn.click();
    fixture.detectChanges();
    document.documentElement.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.grid-bulk-menu')).toBeNull();
  });

  it('emits rowClick when clickable and not from the selection checkbox', () => {
    const fixture = setupSelectable(rows, { clickable: true });
    const clicked: TestRow[] = [];
    fixture.componentInstance.rowClick.subscribe((row) => clicked.push(row));
    const firstRow = fixture.nativeElement.querySelector('tbody tr:first-child') as HTMLElement;
    firstRow.click();
    fixture.detectChanges();
    expect(clicked.map((r) => r.name)).toEqual(['Charlie']);
    const box = fixture.nativeElement.querySelector(
      'tbody tr:first-child .grid-select-col input',
    ) as HTMLInputElement;
    box.click();
    fixture.detectChanges();
    expect(clicked.length).toBe(1);
  });

  it('emits no rowClick when not clickable', () => {
    const fixture = setupSelectable();
    const clicked: TestRow[] = [];
    fixture.componentInstance.rowClick.subscribe((row) => clicked.push(row));
    (fixture.nativeElement.querySelector('tbody tr:first-child') as HTMLElement).click();
    fixture.detectChanges();
    expect(clicked).toEqual([]);
  });

  it('spans the empty state across the selection gutter', () => {
    const fixture = setupSelectable([], { bulkLabels: [] });
    const empty = fixture.nativeElement.querySelector('.grid-empty') as HTMLElement;
    expect(empty.getAttribute('colspan')).toBe('4');
  });

  it('renders the toolbar for bulk actions alone', () => {
    TestBed.configureTestingModule({
      imports: [DataGrid],
      providers: [{ provide: UI_I18N, useValue: passthroughI18n }],
    });
    const fixture = TestBed.createComponent(DataGrid<TestRow>);
    fixture.componentRef.setInput('data', rows);
    fixture.componentRef.setInput('columns', columns);
    fixture.componentRef.setInput('searchable', false);
    fixture.componentRef.setInput('groupable', false);
    fixture.componentRef.setInput('selectable', true);
    fixture.componentRef.setInput('bulkActions', [{ id: 'del', label: 'Delete' }]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.grid-toolbar')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.grid-bulk-btn')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('input[type="search"]')).toBeNull();
  });
});
