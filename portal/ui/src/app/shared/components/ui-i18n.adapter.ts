import { Injectable, inject } from '@angular/core';

import { I18nService } from '../../core/i18n/i18n.service';
import { UiI18n } from './i18n';

/**
 * Binds the shared UI kit (design-system/components) to the portal's
 * DB-backed runtime i18n — the same labels service that powers the shell.
 * Provided as the UI_I18N token in app.config.ts.
 */
@Injectable({ providedIn: 'root' })
export class PortalUiI18n implements UiI18n {
  private readonly i18n = inject(I18nService);

  t(key: string, params?: Record<string, string | number>): string {
    return this.i18n.t(key, params);
  }

  formatDate(value: string | number | Date, options?: Intl.DateTimeFormatOptions): string {
    return this.i18n.formatDate(value, options);
  }

  formatNumber(value: number, options?: Intl.NumberFormatOptions): string {
    return this.i18n.formatNumber(value, options);
  }

  locale(): string {
    return this.i18n.locale();
  }
}
