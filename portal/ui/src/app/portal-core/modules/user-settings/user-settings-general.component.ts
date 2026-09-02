import { Component, computed, inject } from '@angular/core';
import { PORTAL_THEMES, ThemeService } from '../../../core/theme/theme.service';
import { I18nService } from '../../../core/i18n/i18n.service';

/**
 * Portal-owned "General User Settings" screen: theme + language, persisted
 * per user in the DB via ThemeService.setTheme / I18nService.setLanguage
 * (changes apply immediately — no Save button).
 */
@Component({
  selector: 'app-user-settings-general',
  imports: [],
  templateUrl: './user-settings-general.component.html',
  styleUrl: './user-settings-general.component.css',
})
export class UserSettingsGeneral {
  protected readonly themeSvc = inject(ThemeService);
  protected readonly i18n = inject(I18nService);

  protected readonly themes = PORTAL_THEMES;
  protected readonly currentTheme = this.themeSvc.theme;
  protected readonly currentLanguage = this.i18n.locale;
  protected readonly languages = this.i18n.enabledLanguages;
  protected readonly showLanguages = computed(() => this.languages().length > 1);

  protected selectTheme(value: string): void {
    this.themeSvc.setTheme(value);
  }

  protected selectLanguage(code: string): void {
    this.i18n.setLanguage(code);
  }
}
