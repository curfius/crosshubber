import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SettingsService } from '../../../core/settings/settings.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import { WorkbenchService } from '../../features/workspaces/workspaces.store';
import type { PortalEntryPoint } from '../../../core/models';
import { entryPointId } from '../../../core/models';

/**
 * Instance-level settings (D13). Currently configures which application the
 * unclosable Home tab renders. Autosaves on change (admin-gated).
 */
@Component({
  selector: 'app-portal-general-settings',
  imports: [FormsModule],
  templateUrl: './portal-general-settings.component.html',
  styleUrl: './portal-general-settings.component.css',
})
export class PortalGeneralSettings {
  private readonly settingsService = inject(SettingsService);
  private readonly wb = inject(WorkbenchService);
  protected readonly i18n = inject(I18nService);

  protected readonly isAdmin = this.settingsService.isAdmin;
  protected readonly homeApp = this.settingsService.homeApp;
  protected readonly saving = signal(false);
  protected readonly saveError = signal(false);
  protected readonly saveSuccess = signal(false);

  /** Candidate apps for the Home tab (visible applications, no links). */
  protected readonly homeAppOptions = computed<PortalEntryPoint[]>(() =>
    this.wb
      .getEntryPoints()
      .filter((ep) => ep.category === 'applications' && ep.type !== 'link' && ep.active !== false)
      .sort((a, b) => a.name.localeCompare(b.name)),
  );

  protected refOf(ep: PortalEntryPoint): string {
    return entryPointId(ep);
  }

  protected async onHomeAppChange(ref: string): Promise<void> {
    if (!ref || ref === this.homeApp()) return;
    this.saving.set(true);
    this.saveError.set(false);
    this.saveSuccess.set(false);
    const ok = await this.settingsService.updateHomeApp(ref);
    this.saving.set(false);
    if (!ok) {
      this.saveError.set(true);
      return;
    }
    // Re-point the Home tab fixture at the newly selected app.
    this.wb.setHomeApp(ref);
    this.wb.ensureHomeTab();
    this.saveSuccess.set(true);
  }
}
