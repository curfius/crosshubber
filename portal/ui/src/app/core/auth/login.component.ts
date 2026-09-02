import { Component, inject, signal } from '@angular/core';
import { I18nService } from '../i18n/i18n.service';

/**
 * Login page — kicks off the standard OIDC redirect flow via GET /api/login/start and
 * displays the error when the callback lands back on /login?error=...
 */
@Component({
  selector: 'app-login',
  imports: [],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class Login {
  protected readonly i18n = inject(I18nService);

  protected readonly error = signal('');

  constructor() {
    void this.i18n.init();
    const urlParams = new URLSearchParams(window.location.search);
    const error = urlParams.get('error');
    if (error) {
      this.error.set(this.i18n.t('login.unreachable'));
    } else {
      window.location.href = '/api/login/start';
    }
  }
}
