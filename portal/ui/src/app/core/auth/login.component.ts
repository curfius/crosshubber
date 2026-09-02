import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { I18nService } from '../i18n/i18n.service';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class Login {
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18nService);

  protected readonly username = signal('');
  protected readonly password = signal('');
  protected readonly error = signal('');
  protected readonly busy = signal(false);

  /** Backend error strings → label keys (fallback shows the raw message). */
  private static readonly ERROR_KEYS: Record<string, string> = {
    'invalid credentials': 'login.invalidCredentials',
    'login failed': 'login.invalidCredentials',
  };

  constructor() {
    void this.i18n.init();
  }

  async onSubmit(): Promise<void> {
    this.error.set('');
    this.busy.set(true);
    try {
      const startRes = await fetch('/api/login/start');
      const { flowId } = (await startRes.json()) as { flowId?: string };
      if (!flowId) throw new Error('no flow');

      const res = await fetch('/api/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ flowId, username: this.username(), password: this.password() }),
      });
      const body = (await res.json()) as { ok?: boolean; error?: string };
      if (!res.ok || !body.ok) {
        const key = Login.ERROR_KEYS[body.error ?? ''] ?? 'login.invalidCredentials';
        this.error.set(this.i18n.t(key));
        return;
      }
      await this.router.navigate(['/']);
    } catch {
      this.error.set(this.i18n.t('login.unreachable'));
    } finally {
      this.busy.set(false);
    }
  }
}
