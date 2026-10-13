import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { Login } from './core/auth/login.component';
import { Shell } from './portal-core/layout/shell.component';
import { UI_I18N } from './shared/components/i18n';
import { PortalUiI18n } from './shared/components/ui-i18n.adapter';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    { provide: UI_I18N, useExisting: PortalUiI18n },
    provideRouter([
      { path: 'login', component: Login },
      { path: 'w/:name', component: Shell },
      { path: '', component: Shell },
      { path: '**', redirectTo: '' },
    ]),
  ],
};
