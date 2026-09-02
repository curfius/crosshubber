import { Component, computed, inject, signal } from '@angular/core';
import { I18nAdminService } from '../../../core/i18n/i18n-admin.service';
import { I18nService } from '../../../core/i18n/i18n.service';

const COMMON_TIMEZONES = [
  'UTC',
  'Europe/Lisbon',
  'Europe/London',
  'Europe/Paris',
  'Europe/Madrid',
  'Europe/Berlin',
  'Europe/Rome',
  'Europe/Amsterdam',
  'Europe/Brussels',
  'Europe/Zurich',
  'America/New_York',
  'America/Chicago',
  'America/Denver',
  'America/Los_Angeles',
  'America/Sao_Paulo',
  'Africa/Luanda',
  'Asia/Dubai',
  'Asia/Tokyo',
  'Asia/Singapore',
  'Australia/Sydney',
];

const WEEKDAY_KEYS = [
  'i18n.general.day.sunday',
  'i18n.general.day.monday',
  'i18n.general.day.tuesday',
  'i18n.general.day.wednesday',
  'i18n.general.day.thursday',
  'i18n.general.day.friday',
  'i18n.general.day.saturday',
];

@Component({
  selector: 'app-i18n-general-settings',
  imports: [],
  templateUrl: './i18n-general-settings.component.html',
  styleUrl: './i18n-general-settings.component.css',
})
export class I18nGeneralSettings {
  private readonly admin = inject(I18nAdminService);
  protected readonly i18n = inject(I18nService);

  protected readonly config = this.admin.config;
  protected readonly canEdit = this.admin.canEdit;
  protected readonly saving = this.admin.saving;
  protected readonly loaded = this.admin.loaded;
  protected readonly loadError = this.admin.loadError;

  protected readonly timezones = COMMON_TIMEZONES;
  protected readonly weekdayKeys = WEEKDAY_KEYS;

  protected readonly saveState = signal<'idle' | 'ok' | 'error'>('idle');
  private saveTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone;

  protected readonly enabledLanguages = computed(() => this.config()?.languages.filter((l) => l.enabled) ?? []);
  protected readonly timezone = computed(() => this.config()?.overrides.timezone ?? '');
  protected readonly firstDayOfWeek = computed(() => this.config()?.overrides.firstDayOfWeek ?? -1);

  constructor() {
    void this.admin.load();
  }

  protected async onDefaultLanguageChange(code: string): Promise<void> {
    if (!code) return;
    const ok = await this.admin.updateSettings({ defaultLanguage: code });
    this.flash(ok);
  }

  protected async onFallbackLanguageChange(code: string): Promise<void> {
    if (!code) return;
    const ok = await this.admin.updateSettings({ fallbackLanguage: code });
    this.flash(ok);
  }

  protected async onTimezoneChange(value: string): Promise<void> {
    const current = this.config();
    if (!current) return;
    const overrides = { ...current.overrides };
    if (value) overrides.timezone = value;
    else delete overrides.timezone;
    const ok = await this.admin.updateSettings({ overrides });
    this.flash(ok);
  }

  protected async onFirstDayOfWeekChange(value: string): Promise<void> {
    const current = this.config();
    if (!current) return;
    const overrides = { ...current.overrides };
    if (value === '') delete overrides.firstDayOfWeek;
    else overrides.firstDayOfWeek = Number(value);
    const ok = await this.admin.updateSettings({ overrides });
    this.flash(ok);
  }

  private flash(ok: boolean): void {
    this.saveState.set(ok ? 'ok' : 'error');
    if (this.saveTimer) clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => this.saveState.set('idle'), 2500);
  }
}
