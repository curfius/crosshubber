import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { WorkbenchService } from '../../features/workspaces/workspaces.store';
import { I18nService } from '../../../core/i18n/i18n.service';
import type { PortalEntryPoint } from '../../../core/models';

@Component({
  selector: 'app-workspace-toolbar',
  imports: [FormsModule],
  templateUrl: './toolbar.component.html',
  styleUrl: './toolbar.component.css',
})
export class WorkspaceToolbar {
  private readonly wb = inject(WorkbenchService);
  readonly i18n = inject(I18nService);

  readonly userName = input('');
  readonly workspacesEnabled = input(true);
  readonly logout = output<void>();

  protected readonly active = this.wb.activeWorkspace;
  protected readonly dirty = this.wb.dirty;
  protected readonly editMode = this.wb.editMode;
  protected readonly hideSingleTab = this.wb.hideSingleTabToolbar;
  protected readonly wsDescription = this.wb.description;
  protected readonly wsColor = this.wb.workspaceColor;

  protected readonly showProfile = signal(false);
  protected readonly showLanguageMenu = signal(false);
  protected readonly showSaveDialog = signal(false);
  protected readonly saveName = signal('');
  protected readonly saveDescription = signal('');
  protected readonly saveColor = signal('');
  protected readonly colors = ['#f97316', '#fbbf24', '#2563eb', '#14b8a6', '#6366f1', '#a855f7'];

  /** Hide the entry when the user-settings builtin module is disabled/removed. */
  protected readonly userSettingsAvailable = computed(() =>
    !!this.wb.findEntryPoint('user-settings', 'user-settings-shell'),
  );

  protected openSaveDialog(): void {
    this.saveName.set(this.active() ?? 'Workspace');
    this.saveDescription.set(this.wsDescription());
    this.saveColor.set(this.wsColor() ?? this.colors[0]);
    this.showSaveDialog.set(true);
  }

  protected async saveOverwrite(): Promise<void> {
    const name = this.saveName().trim() || 'Workspace';
    const desc = this.saveDescription().trim();
    const color = this.saveColor();
    await this.wb.saveWorkspace(name, desc, true, color);
    this.showSaveDialog.set(false);
    this.wb.editMode.set(false);
  }

  protected async saveAsNew(): Promise<void> {
    const name = this.saveName().trim() || 'Workspace';
    const desc = this.saveDescription().trim();
    const color = this.saveColor();
    await this.wb.saveAsNewWorkspace(name, desc, true, color);
    this.showSaveDialog.set(false);
    this.wb.editMode.set(false);
  }

  protected enterEdit(): void {
    this.wb.editMode.set(true);
  }

  protected async deleteAndExit(): Promise<void> {
    const name = this.active();
    if (name) await this.wb.deleteWorkspace(name);
    this.wb.editMode.set(false);
  }

  protected async discardChanges(): Promise<void> {
    await this.wb.discardChanges();
    this.showSaveDialog.set(false);
  }

  protected createNew(): void {
    this.wb.createNewWorkspace();
  }

  protected closePanels(): void {
    this.showProfile.set(false);
    this.showLanguageMenu.set(false);
    this.showSaveDialog.set(false);
  }

  protected selectLanguage(code: string): void {
    this.i18n.setLanguage(code);
    this.showLanguageMenu.set(false);
  }

  protected toggleLanguageMenu(): void {
    const next = !this.showLanguageMenu();
    this.showProfile.set(false);
    this.showSaveDialog.set(false);
    this.showLanguageMenu.set(next);
  }

  protected toggleProfile(): void {
    const next = !this.showProfile();
    this.showLanguageMenu.set(false);
    this.showSaveDialog.set(false);
    this.showProfile.set(next);
  }

  /** Shows the current language's native name in the switcher button. */
  protected activeLanguageLabel(): string {
    const code = this.i18n.locale();
    const language = this.i18n.enabledLanguages().find((l) => l.code === code);
    return language?.nativeName ?? code.toUpperCase();
  }

  protected openUserSettings(): void {
    const ep = this.wb.findEntryPoint('user-settings', 'user-settings-shell');
    if (ep) {
      this.showProfile.set(false);
      this.wb.openApp(ep);
    }
  }

  protected openSettings(): void {
    const eps = this.wb.getEntryPoints();
    const settingsEp = eps.find(
      (ep) => ep.category === 'features' && ep.entryKey === 'settings-shell',
    );
    if (settingsEp) {
      this.wb.openApp(settingsEp);
    }
  }

  protected initials(name: string): string {
    return name
      .split(/\s+/)
      .map((p) => p[0])
      .join('')
      .slice(0, 2)
      .toUpperCase();
  }
}
