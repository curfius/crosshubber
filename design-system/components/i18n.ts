import { InjectionToken } from '@angular/core';

/**
 * Neutral i18n + locale-formatting contract for shared UI-kit components.
 *
 * Consumers bind UI_I18N at bootstrap:
 *   portal shell → adapter over the portal I18nService (DB-backed labels)
 *   module MFEs  → MfeI18n (fetches the same public label bundles)
 *
 * The default factory serves the shared kit's built-in keys in English and
 * passes unknown keys through raw, so a component stays renderable before any
 * adapter is bound.
 */
export interface UiI18n {
  t(key: string, params?: Record<string, string | number>): string;
  formatDate(value: string | number | Date, options?: Intl.DateTimeFormatOptions): string;
  formatNumber(value: number, options?: Intl.NumberFormatOptions): string;
  locale(): string;
}

/** BCP-47 tag used when no adapter is bound (matches the portal fallback locale). */
export const DEFAULT_LOCALE = 'en-GB';

/** Labels the shared kit owns itself (grid strings). Keyed exactly like the portal catalog. */
export const SHARED_KIT_LABELS: Record<string, string> = {
  'grid.searchPlaceholder': 'Search…',
  'grid.groupBy': 'Group by',
  'grid.groupByNone': 'None',
  'grid.noResults': 'No results',
  'grid.filterPlaceholder': 'Filter…',
  'grid.groupCount': '{count} rows',
  'grid.selectAll': 'Select all rows',
  'grid.selectRow': 'Select this row',
  'grid.bulkActions': 'Bulk actions',
  'grid.selectedCount': '{count} selected',
};

export const UI_I18N = new InjectionToken<UiI18n>('UI_I18N', {
  providedIn: 'root',
  factory: () => new DefaultUiI18n(),
});

class DefaultUiI18n implements UiI18n {
  t(key: string): string {
    return SHARED_KIT_LABELS[key] ?? key;
  }

  formatDate(value: string | number | Date, options?: Intl.DateTimeFormatOptions): string {
    const date = typeof value === 'string' ? new Date(value) : value;
    return new Intl.DateTimeFormat(DEFAULT_LOCALE, options).format(date);
  }

  formatNumber(value: number, options?: Intl.NumberFormatOptions): string {
    return new Intl.NumberFormat(DEFAULT_LOCALE, options).format(value);
  }

  locale(): string {
    return DEFAULT_LOCALE;
  }
}
