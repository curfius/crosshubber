import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ShellNavEditor } from './shell-nav-editor.component';

/**
 * Admin settings page (EP navigation:settings-tree): the admin-settings
 * shell-tree editor that mirrors the Settings shell.
 */
@Component({
  selector: 'app-nav-settings-nav',
  imports: [ShellNavEditor],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="h-full overflow-y-auto p-6">
    <app-shell-nav-editor category="admin-settings" />
  </div>`,
})
export class NavSettingsNav {}
