export interface GridColumn<T> {
  key: string;
  header: string;
  headerKey?: string;
  accessor: (row: T) => unknown;
  format?: (value: unknown, row: T) => string;
  sortable?: boolean;
  searchable?: boolean;
  align?: 'left' | 'right' | 'center';
  width?: string;
}

export interface GridCellContext<T> {
  $implicit: T;
  value: unknown;
}
