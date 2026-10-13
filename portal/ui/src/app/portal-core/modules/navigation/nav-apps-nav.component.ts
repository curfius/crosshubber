import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ShellNavEditor } from './shell-nav-editor.component';

/**
 * Admin settings page (EP navigation:apps): the applications shell-tree editor.
 */
@Component({
  selector: 'app-nav-apps-nav',
  imports: [ShellNavEditor],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="h-full overflow-y-auto p-6">
    <app-shell-nav-editor category="applications" />
  </div>`,
})
export class NavAppsNav {}
