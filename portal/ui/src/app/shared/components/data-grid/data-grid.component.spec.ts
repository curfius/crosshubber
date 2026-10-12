import { NgTemplateOutlet } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { describe, it, expect } from 'vitest';
import { DataGrid } from './data-grid.component';
import { GridColumn } from './grid-column';

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

function setup(data: TestRow[] = rows, cols: GridColumn<TestRow>[] = columns) {
  TestBed.configureTestingModule({ imports: [DataGrid] });
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
  const th = [...fixture.nativeElement.querySelectorAll('thead tr:first-child th')].find(
    (th) => th.textContent?.includes(key),
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

  it('uses literal header when no headerKey', () => {
    const cols: GridColumn<TestRow>[] = [
      { key: 'name', header: 'Name', accessor: (r) => r.name },
    ];
    const fixture = setup(rows, cols);
    const th = fixture.nativeElement.querySelector('thead th');
    expect(th?.textContent).toContain('Name');
  });
});
