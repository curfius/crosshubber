import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { Login } from './core/auth/login.component';
import { Shell } from './portal-core/layout/shell.component';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter([
      { path: 'login', component: Login },
      { path: 'w/:name', component: Shell },
      { path: '', component: Shell },
      { path: '**', redirectTo: '' },
    ]),
  ],
};
