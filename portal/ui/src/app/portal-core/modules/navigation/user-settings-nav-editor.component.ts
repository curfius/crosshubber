import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ShellNavEditor } from './shell-nav-editor.component';

@Component({
  selector: 'app-user-settings-nav-editor',
  imports: [ShellNavEditor],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '<app-shell-nav-editor category="user-settings" />',
})
export class UserSettingsNavEditor {}
