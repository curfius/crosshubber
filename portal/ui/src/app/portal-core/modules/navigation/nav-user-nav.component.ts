import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ShellNavEditor } from './shell-nav-editor.component';

/**
 * Admin settings page (EP navigation:user-tree): the user-settings shell-tree
 * editor that mirrors the User Settings shell.
 */
@Component({
  selector: 'app-nav-user-nav',
  imports: [ShellNavEditor],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="h-full overflow-y-auto p-6">
    <app-shell-nav-editor category="user-settings" />
  </div>`,
})
export class NavUserNav {}
