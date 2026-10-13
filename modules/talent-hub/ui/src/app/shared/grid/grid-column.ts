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

/** One entry of the grid's optional bulk-actions dropdown. */
export interface GridBulkAction {
  id: string;
  /** Fallback label when `labelKey` is absent or unresolvable. */
  label: string;
  /** i18n key resolved via the kit's `UI_I18N` contract (preferred). */
  labelKey?: string;
  /** Marks destructive actions (e.g. delete) for danger styling. */
  danger?: boolean;
}

/** Payload emitted when a bulk action is picked for the current selection. */
export interface GridBulkActionEvent {
  id: string;
  /** Selected row ids (via the grid's `trackBy`), in selection order. */
  ids: string[];
}
