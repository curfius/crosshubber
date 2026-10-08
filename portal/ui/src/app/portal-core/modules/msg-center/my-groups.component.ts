import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { I18nService } from '../../../core/i18n/i18n.service';
import { MsgCenterStore } from '../../../core/msg-center/msg-center.store';

/**
 * User settings "My groups" card (plan §9): membership is the subscription — join open groups,
 * leave any group (never blocked), per-group email flag greyed until the Phase 7 channel ships.
 */
@Component({
  selector: 'app-msg-center-my-groups',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './my-groups.component.html',
  styleUrl: './my-groups.component.css',
})
export class MsgCenterMyGroups implements OnInit {
  private readonly store = inject(MsgCenterStore);
  protected readonly i18n = inject(I18nService);
  protected readonly busy = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly groups = this.store.groups;
  protected readonly loading = this.store.groupsLoading;
  protected readonly emailBusy = signal<string | null>(null);

  /** My mirror preferences (address + global fallback switch) — Phase 7 live. */
  protected readonly myEmail = signal('');
  protected readonly emailFallback = signal(false);

  async ngOnInit(): Promise<void> {
    void this.store.loadGroups();
    await this.loadMyEmail();
  }

  private async loadMyEmail(): Promise<void> {
    try {
      const res = await fetch('/api/msgcenter/my-email');
      if (res.ok) {
        const payload = (await res.json()) as { email: string; emailFallback: boolean };
        this.myEmail.set(payload.email ?? '');
        this.emailFallback.set(!!payload.emailFallback);
      }
    } catch {
      // mirror prefs stay as-is on transient failures
    }
  }

  protected async saveMyEmail(): Promise<void> {
    try {
      const res = await fetch('/api/msgcenter/my-email', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: this.myEmail().trim(),
          emailFallback: this.emailFallback(),
        }),
      });
      if (res.ok) {
        const payload = (await res.json()) as { email: string; emailFallback: boolean };
        this.myEmail.set(payload.email ?? '');
        this.emailFallback.set(!!payload.emailFallback);
      }
    } catch {
      // keep local state on transient failures
    }
  }

  protected async join(key: string): Promise<void> {
    await this.run(key, () => this.store.joinGroup(key));
  }

  protected async leave(key: string): Promise<void> {
    await this.run(key, () => this.store.leaveGroup(key));
  }

  protected async toggleGroupEmail(key: string, enabled: boolean): Promise<void> {
    this.emailBusy.set(key);
    try {
      await fetch(`/api/msgcenter/my-groups/${key}/email-flag`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled }),
      });
      await this.store.loadGroups();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    } finally {
      this.emailBusy.set(null);
    }
  }

  private async run(key: string, action: () => Promise<void>): Promise<void> {
    this.busy.set(key);
    this.error.set(null);
    try {
      await action();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    } finally {
      this.busy.set(null);
    }
  }
}
