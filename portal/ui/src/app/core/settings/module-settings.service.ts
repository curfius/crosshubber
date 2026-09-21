import { Injectable } from '@angular/core';
import { ScopedSettingsClient } from './scoped-settings-client';

/** Client for module-scoped `module_settings` documents (module key = scope). */
@Injectable({ providedIn: 'root' })
export class ModuleSettingsService extends ScopedSettingsClient {
  protected readonly logTag = 'module-settings';
  protected readonly urlPrefix = '/api/module-settings';
}
