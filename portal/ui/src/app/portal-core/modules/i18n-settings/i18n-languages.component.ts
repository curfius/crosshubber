import { Component, computed, inject, signal } from '@angular/core';
import { I18nAdminService } from '../../../core/i18n/i18n-admin.service';
import { I18nService } from '../../../core/i18n/i18n.service';

@Component({
  selector: 'app-i18n-languages',
  imports: [],
  templateUrl: './i18n-languages.component.html',
  styleUrl: './i18n-languages.component.css',
})
export class I18nLanguages {
  private readonly admin = inject(I18nAdminService);
  protected readonly i18n = inject(I18nService);

  protected readonly config = this.admin.config;
  protected readonly canEdit = this.admin.canEdit;
  protected readonly saving = this.admin.saving;
  protected readonly loaded = this.admin.loaded;
  protected readonly loadError = this.admin.loadError;

  protected readonly blockedCode = signal<string | null>(null);

  protected readonly languages = computed(() => this.config()?.languages ?? []);
  protected readonly defaultLanguage = computed(() => this.config()?.defaultLanguage ?? '');
  protected readonly fallbackLanguage = computed(() => this.config()?.fallbackLanguage ?? '');

  constructor() {
    void this.admin.load();
  }

  protected async toggle(code: string, currentEnabled: boolean): Promise<void> {
    if (!this.canEdit() || this.saving()) return;
    const ok = await this.admin.updateLanguage(code, { enabled: !currentEnabled });
    if (!ok) this.blockedCode.set(code);
    else this.blockedCode.set(null);
  }

  /** Reason a language cannot be disabled (default/fallback), or null. */
  protected lockReason(code: string): string | null {
    if (this.defaultLanguage() === code) return this.i18n.t('i18n.languages.disableDefault');
    if (this.fallbackLanguage() === code) return this.i18n.t('i18n.languages.disableFallback');
    return null;
  }
}
