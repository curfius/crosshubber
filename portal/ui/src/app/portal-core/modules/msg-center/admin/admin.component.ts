import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { I18nService } from '../../../../core/i18n/i18n.service';
import { apiFetch } from '../../../../core/http/api-fetch';
import { MsgCenterStore } from '../../../../core/msg-center/msg-center.store';

interface ModuleStats {
  moduleKey: string;
  total: number;
  open: number;
  done: number;
}

interface ActivityRow {
  id: number;
  actorSub: string;
  actorName: string | null;
  action: string;
  detail: Record<string, unknown>;
  createdAt: string | null;
}

interface DlqEntry {
  originalSubject?: string;
  reason?: string;
  streamSeq?: number;
}

interface SmtpConfigView {
  host: string | null;
  port: number | null;
  from: string | null;
  tls: string | null;
  authUser: string | null;
  passwordSet: boolean;
  updatedAt: string | null;
}

/**
 * Message Center Admin (plan §9): per-module counters, DLQ peek, and per-task activity
 * timelines (admin-only audit) with force-release / reset actions.
 */
@Component({
  selector: 'app-msg-center-admin',
  imports: [DatePipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './admin.component.html',
  styleUrl: './admin.component.css',
})
export class MsgCenterAdmin implements OnInit {
  protected readonly i18n = inject(I18nService);
  protected readonly store = inject(MsgCenterStore);

  protected readonly perModule = signal<ModuleStats[]>([]);
  protected readonly dlq = signal<DlqEntry[]>([]);
  protected readonly activity = signal<ActivityRow[] | null>(null);
  protected readonly taskId = signal('');
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  // SMTP settings card state
  protected readonly smtp = signal<SmtpConfigView | null>(null);
  protected readonly smtpHost = signal('');
  protected readonly smtpPort = signal<number | null>(null);
  protected readonly smtpFrom = signal('');
  protected readonly smtpTls = signal('none');
  protected readonly smtpUser = signal('');
  protected readonly smtpPassword = signal('');
  protected readonly smtpInfo = signal<string | null>(null);

  protected readonly totalMessages = computed(() =>
    this.perModule().reduce((sum, m) => sum + m.total, 0),
  );

  ngOnInit(): void {
    void this.refresh();
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [overview, dlqRes, smtpRes] = await Promise.all([
        apiFetch('/api/msgcenter/admin/overview'),
        apiFetch('/api/msgcenter/admin/dlq'),
        apiFetch('/api/msgcenter/admin/settings/email'),
      ]);
      const overviewPayload = (await overview.json()) as { perModule?: ModuleStats[] };
      const dlqPayload = (await dlqRes.json()) as { entries?: DlqEntry[] };
      const smtpPayload = (await smtpRes.json()) as { email?: SmtpConfigView };
      this.perModule.set(overviewPayload.perModule ?? []);
      this.dlq.set(
        (dlqPayload.entries ?? []).map((raw) => this.parseEntry(raw)).filter((e) => !!e),
      );
      this.applySmtp(smtpPayload.email ?? null);
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    } finally {
      this.loading.set(false);
    }
  }

  private applySmtp(view: SmtpConfigView | null): void {
    this.smtp.set(view);
    this.smtpHost.set(view?.host ?? '');
    this.smtpPort.set(view?.port ?? null);
    this.smtpFrom.set(view?.from ?? '');
    this.smtpTls.set(view?.tls ?? 'none');
    this.smtpUser.set(view?.authUser ?? '');
    this.smtpPassword.set(''); // never render the stored secret
  }

  protected onSmtpPortInput(event: Event): void {
    const raw = (event.target as HTMLInputElement).value;
    this.smtpPort.set(raw === '' ? null : Number(raw));
  }

  protected async saveSmtp(): Promise<void> {
    await this.guarded(async () => {
      const res = await apiFetch('/api/msgcenter/admin/settings/email', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          host: this.smtpHost() || null,
          port: this.smtpPort(),
          from: this.smtpFrom() || null,
          tls: this.smtpTls() || null,
          authUser: this.smtpUser() || null,
          password: this.smtpPassword() === '' ? null : this.smtpPassword(),
        }),
      });
      const payload = (await res.json()) as { email?: SmtpConfigView };
      this.applySmtp(payload.email ?? null);
      this.smtpInfo.set('saved');
    });
  }

  protected async testSmtp(): Promise<void> {
    await this.guarded(async () => {
      await apiFetch('/api/msgcenter/admin/settings/email/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ to: this.smtpFrom() === '' ? null : this.smtpFrom() }),
      });
      this.smtpInfo.set('test sent — check the Mailpit UI');
    });
  }

  private async guarded(action: () => Promise<void>): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    this.smtpInfo.set(null);
    try {
      await action();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    } finally {
      this.loading.set(false);
    }
  }

  protected onTaskIdInput(event: Event): void {
    const target = event.target as { value?: string };
    this.taskId.set(target.value ?? '');
  }

  protected async loadActivity(): Promise<void> {
    const id = Number(this.taskId());
    if (!Number.isFinite(id) || id <= 0) {
      this.error.set('invalid task id');
      return;
    }
    this.activity.set(null);
    this.error.set(null);
    try {
      const res = await apiFetch(`/api/msgcenter/admin/tasks/${id}/activity`);
      const payload = (await res.json()) as { activity?: ActivityRow[] };
      this.activity.set(payload.activity ?? []);
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }

  protected async forceRelease(discardDraft: boolean): Promise<void> {
    const id = Number(this.taskId());
    try {
      await apiFetch(`/api/msgcenter/admin/tasks/${id}/release`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ discardDraft }),
      });
      await this.loadActivity();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }

  protected async adminReset(): Promise<void> {
    const id = Number(this.taskId());
    try {
      await apiFetch(`/api/msgcenter/admin/tasks/${id}/reset`, { method: 'POST' });
      await this.loadActivity();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }

  private parseEntry(raw: unknown): DlqEntry | null {
    if (typeof raw === 'string') {
      try {
        return JSON.parse(raw) as DlqEntry;
      } catch {
        return null;
      }
    }
    return raw as DlqEntry;
  }
}
