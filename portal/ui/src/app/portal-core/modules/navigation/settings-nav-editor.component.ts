import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ShellNavEditor } from './shell-nav-editor.component';

@Component({
  selector: 'app-settings-nav-editor',
  imports: [ShellNavEditor],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '<app-shell-nav-editor category="settings" />',
})
export class SettingsNavEditor {}
