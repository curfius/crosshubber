import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SettingsService } from '../../../core/settings/settings.service';
import { NavigationStore } from '../../../core/navigation/navigation.store';
import type { NavigationFeatures } from '../../../core/navigation/navigation.models';
import { I18nService } from '../../../core/i18n/i18n.service';
import { WorkbenchService } from '../../../core/workspaces/workspaces.store';
import type { PortalModuleContent } from '../../../core/models';
import { moduleContentId } from '../../../core/models';

interface FeatureToggle {
  key: keyof NavigationFeatures;
  titleKey: string;
  hintKey: string;
}

/**
 * Admin settings page (role portal-navigation-edit, EP navigation:general):
 * two sections — the home application plus the navigation feature switches
 * (pinned apps and workspaces sidebar capabilities). When a capability is
 * enabled it is always shown on the Portal Navigation app page.
 */
@Component({
  selector: 'app-nav-general-settings',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="h-full overflow-y-auto p-6">
      <div class="mb-5">
        <h1 class="ds-heading-3">{{ i18n.t('navigation.general.title') }}</h1>
        <p class="mt-0.5 text-xs text-[var(--portal-text-muted)]">
          {{ i18n.t('navigation.general.description') }}
        </p>
      </div>

      <div class="max-w-xl space-y-6">
        <!-- 1. Home application -->
        <section>
          <h2
            class="mb-2 text-xs font-semibold uppercase tracking-widest text-[var(--portal-text-muted)]"
          >
            {{ i18n.t('navigation.general.homeApp') }}
          </h2>
          <div class="ds-card p-5">
            <div class="flex flex-wrap items-center justify-between gap-3">
              <div class="min-w-0">
                <p class="mt-1 text-xs text-[var(--portal-text-muted)]">
                  {{ i18n.t('navigation.general.homeAppHint') }}
                </p>
              </div>
              <div class="flex items-center gap-3">
                @if (saveSuccess()) {
                  <span class="text-xs text-[var(--portal-status-success)]">{{
                    i18n.t('common.saved')
                  }}</span>
                }
                @if (saveError() !== null) {
                  <span class="text-xs text-[var(--portal-status-danger)]">{{
                    i18n.t('common.error')
                  }}</span>
                }
                <select
                  class="ds-input w-56 py-1.5 text-xs"
                  [disabled]="!isAdmin() || savingHomeApp()"
                  [ngModel]="homeApp()"
                  (ngModelChange)="onHomeAppChange($event)"
                  [attr.aria-label]="i18n.t('navigation.general.homeApp')"
                >
                  @for (ep of homeAppOptions(); track refOf(ep)) {
                    <option [value]="refOf(ep)">{{ ep.name }}</option>
                  }
                </select>
              </div>
            </div>
            @if (!isAdmin()) {
              <p class="mt-3 text-xs text-[var(--portal-text-disabled)]">
                {{ i18n.t('settings.readonlyHint') }}
              </p>
            }
          </div>
        </section>

        <!-- 2. Navigation features -->
        <section>
          <h2
            class="mb-2 text-xs font-semibold uppercase tracking-widest text-[var(--portal-text-muted)]"
          >
            {{ i18n.t('navigation.general.featuresSection') }}
          </h2>
          <div class="space-y-4">
            @for (toggle of toggles; track toggle.key) {
              <div class="ds-card p-5">
                <div class="flex items-center justify-between">
                  <div>
                    <h3 class="ds-heading-3 text-sm">{{ i18n.t(toggle.titleKey) }}</h3>
                    <p class="mt-1 text-xs text-[var(--portal-text-muted)]">
                      {{ i18n.t(toggle.hintKey) }}
                    </p>
                  </div>
                  <button
                    type="button"
                    (click)="toggleFeature(toggle.key)"
                    [attr.aria-pressed]="featureOn(toggle.key)"
                    [class]="
                      featureOn(toggle.key)
                        ? 'relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent bg-[var(--portal-accent-primary)] transition-colors duration-200'
                        : 'relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent bg-[var(--portal-border-strong)] transition-colors duration-200'
                    "
                    role="switch"
                    [attr.aria-checked]="featureOn(toggle.key)"
                  >
                    <span
                      [class]="
                        featureOn(toggle.key)
                          ? 'pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 translate-x-5'
                          : 'pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow ring-0 transition duration-200 translate-x-0'
                      "
                    ></span>
                  </button>
                </div>
              </div>
            }

            @if (featureError() !== null) {
              <div
                class="rounded-lg border border-[var(--portal-status-danger)] bg-[var(--portal-bg-surface)] px-3 py-2 text-xs text-[var(--portal-status-danger)]"
              >
                {{ i18n.t('navigation.general.saveFailed') }}
              </div>
            }
          </div>
        </section>
      </div>
    </div>
  `,
})
export class NavGeneralSettings implements OnInit {
  private readonly settingsService = inject(SettingsService);
  private readonly wb = inject(WorkbenchService);
  protected readonly nav = inject(NavigationStore);
  protected readonly i18n = inject(I18nService);

  protected readonly toggles: FeatureToggle[] = [
    {
      key: 'pinnedAppsEnabled',
      titleKey: 'navigation.features.pinnedApps',
      hintKey: 'navigation.features.pinnedAppsHint',
    },
    {
      key: 'workspacesEnabled',
      titleKey: 'navigation.features.workspaces',
      hintKey: 'navigation.features.workspacesHint',
    },
  ];

  protected readonly isAdmin = this.settingsService.isAdmin;
  protected readonly homeApp = this.settingsService.homeApp;
  protected readonly savingHomeApp = signal(false);
  protected readonly saveSuccess = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly featureError = signal<string | null>(null);

  protected readonly homeAppOptions = computed<PortalModuleContent[]>(() =>
    this.wb
      .getModuleContents()
      .filter((ep) => ep.category === 'applications' && ep.type !== 'link' && ep.active !== false)
      .sort((a, b) => a.name.localeCompare(b.name)),
  );

  protected refOf(ep: PortalModuleContent): string {
    return moduleContentId(ep);
  }

  protected featureOn(key: keyof NavigationFeatures): boolean {
    return !!this.nav.features()[key];
  }

  async ngOnInit(): Promise<void> {
    if (!this.nav.loaded()) await this.nav.load();
  }

  protected async onHomeAppChange(ref: string): Promise<void> {
    if (!ref || ref === this.homeApp()) return;
    this.savingHomeApp.set(true);
    this.saveError.set(null);
    this.saveSuccess.set(false);
    const ok = await this.settingsService.updateHomeApp(ref);
    this.savingHomeApp.set(false);
    if (!ok) {
      this.saveError.set('retry');
      return;
    }
    // Re-point the Home tab fixture at the newly selected app.
    this.wb.setHomeApp(ref);
    this.wb.ensureHomeTab();
    this.saveSuccess.set(true);
  }

  protected async toggleFeature(key: keyof NavigationFeatures): Promise<void> {
    this.featureError.set(null);
    const ok = await this.nav.saveFeatures({ [key]: !this.nav.features()[key] });
    if (!ok) {
      this.featureError.set('retry');
    }
  }
}
