import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import {
  ModuleClient,
  PHASES,
  STAGE_TRANSITIONS,
  TERMINAL_STAGES,
  canManage,
  canRecruit,
} from '../shared/module-client';
import { DataGrid } from '../shared/grid/data-grid.component';
import { GridColumn } from '../shared/grid/grid-column';
import { UI_I18N } from '../shared/i18n';
import { MfeI18n } from '../shared/mfe-i18n';
import { mfeReadyDispatcher } from '../shared/ready';

type Rfp = {
  id: string;
  title: string;
  kind: string;
  phase: string;
  serviceType: string;
  deadline: string | null;
};

type Detail = {
  rfp: Rfp;
  client: {
    name: string;
    industry: string | null;
    accountOwner: string | null;
    notes: string | null;
  };
  positions: { id: string; title: string; headcount: number; status: string }[];
  processes: { id: string; positionId: string; candidateId: string; stage: string }[];
  submissions: {
    id: string;
    note: string | null;
    sentBy: string;
    sentAt: string;
    response: { outcome?: string } | null;
  }[];
  phaseHistory: {
    from: string | null;
    to: string;
    trigger: string;
    actor: string;
    at: string;
  }[];
};

type ProcessRow = { id: string; positionId: string; candidateName: string; stage: string };

/** Ongoing RFPs (P2.7): flat grid + detail modal with processes, submissions, close/reject. */
@Component({
  selector: 'th-ongoing',
  imports: [DataGrid],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      <div class="head">
        <h2>{{ t('talenthub.ongoing.title') }}</h2>
        <select class="ds-select" [value]="filter()" (change)="onFilter($event)">
          @for (phase of phases; track phase) {
            <option [value]="phase">{{ phase }}</option>
          }
          <option value="">{{ t('talenthub.ongoing.allPhases') }}</option>
        </select>
        <input
          class="ds-input"
          [placeholder]="t('talenthub.ongoing.searchTitle')"
          [value]="term()"
          (input)="onTerm($event)" />
      </div>

      @if (error(); as err) {
        <div class="ds-alert ds-alert-danger">{{ err }}</div>
      } @else if (loading() && rfps().length === 0) {
        <p class="muted">{{ t('common.loading') }}</p>
      } @else {
        <app-data-grid
          [data]="visibleRfps()"
          [columns]="rfpColumns"
          [trackBy]="trackRfp"
          [searchable]="false"
          [groupable]="false"
          [clickable]="true"
          (rowClick)="openDetail($event.id)" />
      }

      @if (detail(); as d) {
        <div class="ds-modal-backdrop" (click)="closeDetail()">
          <div class="ds-modal ds-modal-lg" (click)="$event.stopPropagation()">
            <div class="ds-modal-header">
              <span class="ds-modal-title">
                <span class="detail-title-text">{{ d.rfp.title }}</span>
                <span class="ds-badge ds-badge-outline">{{ d.rfp.phase }}</span>
              </span>
              <button type="button" class="ds-modal-close" [attr.aria-label]="t('common.close')" (click)="closeDetail()">×</button>
            </div>
            <div class="ds-modal-body">
              <div class="detail-meta">
                <span class="muted">
                  {{ t('talenthub.ongoing.client') }}: {{ d.client.name }}
                  @if (d.client.industry) { ({{ d.client.industry }}) }
                </span>
                <span class="ds-badge ds-badge-outline">{{ d.rfp.kind }}</span>
                <span class="ds-badge ds-badge-outline">{{ d.rfp.serviceType }}</span>
              </div>

              <h3 class="ds-heading-4">{{ t('talenthub.ongoing.positions') }}</h3>
              @for (position of d.positions; track position.id) {
                <div class="row">
                  <strong>{{ position.title }}</strong>
                  <span class="ds-badge ds-badge-outline">×{{ position.headcount }}</span>
                  <span class="ds-badge ds-badge-outline">{{ position.status }}</span>
                  @for (proc of processesFor(position.id); track proc.id) {
                    <span class="proc">
                      {{ proc.candidateName }}
                      <span class="ds-badge ds-badge-outline">{{ proc.stage }}</span>
                      @if (canRecruit()) {
                        @for (target of nextStages(proc.stage); track target) {
                          <button
                            type="button"
                            class="ds-btn ds-btn-secondary ds-btn-sm"
                            (click)="transition(proc, target)">
                            {{ target }}
                          </button>
                        }
                      }
                    </span>
                  }
                </div>
              }
              @if (canRecruit() && d.rfp.phase !== 'draft') {
                <div class="ds-form-group">
                  <select class="ds-select" (change)="onPickCandidate($event)">
                    <option value="">{{ t('talenthub.ongoing.openProcess') }}</option>
                    @for (c of candidates(); track c.id) {
                      <option [value]="c.id">{{ c.name }}</option>
                    }
                  </select>
                </div>
              }
              @if (canRecruit()) {
                <div class="row">
                  <input
                    class="ds-input"
                    [placeholder]="t('talenthub.ongoing.newPosition')"
                    [value]="positionTitle()"
                    (input)="onPositionTitle($event)" />
                  <button
                    type="button"
                    class="ds-btn ds-btn-primary ds-btn-sm"
                    [disabled]="positionTitle().trim().length === 0"
                    (click)="createPosition()">
                    {{ t('talenthub.ongoing.addPosition') }}
                  </button>
                </div>
              }

              <h3 class="ds-heading-4">{{ t('talenthub.ongoing.phaseHistory') }}</h3>
              @for (h of d.phaseHistory; track h.at) {
                <div class="row">
                  <span>{{ h.from ?? 'created' }} → {{ h.to }}</span>
                  <span class="ds-badge ds-badge-outline">{{ h.trigger }}</span>
                  <span class="muted">{{ h.actor }}</span>
                </div>
              }

              <h3 class="ds-heading-4">{{ t('talenthub.ongoing.submissions') }}</h3>
              @for (s of d.submissions; track s.id) {
                <div class="row">
                  <strong>{{ s.sentBy }}</strong>
                  <span
                    class="ds-badge"
                    [class.ds-badge-success]="s.response?.outcome === 'accepted'"
                    [class.ds-badge-outline]="s.response?.outcome !== 'accepted'">
                    {{ s.response?.outcome ?? t('talenthub.ongoing.waiting') }}
                  </span>
                  <span class="muted">{{ s.note }}</span>
                </div>
              }
              @if (d.submissions.length === 0) {
                <div class="muted">{{ t('talenthub.ongoing.noSubmissions') }}</div>
              }

              @if (canManage()) {
                <div class="row">
                  @if (!TERMINAL_PHASES.includes(d.rfp.phase)) {
                    <button type="button" class="ds-btn ds-btn-primary ds-btn-sm" (click)="dialog.set('close')">
                      {{ t('talenthub.ongoing.close') }}
                    </button>
                    <button type="button" class="ds-btn ds-btn-secondary ds-btn-sm" (click)="dialog.set('reject')">
                      {{ t('talenthub.ongoing.reject') }}
                    </button>
                  } @else if (d.rfp.phase !== 'archived') {
                    <button type="button" class="ds-btn ds-btn-secondary ds-btn-sm" (click)="archive()">
                      {{ t('talenthub.ongoing.archive') }}
                    </button>
                  }
                </div>
              }
              @if (dialog(); as which) {
                <h3 class="ds-heading-4">{{ t('talenthub.ongoing.confirmTitle', { action: which }) }}</h3>
                <p class="muted">
                  {{ t('talenthub.ongoing.activeProcesses', { count: activeCount() }) }}
                </p>
                @for (proc of activeRows(); track proc.id) {
                  <div class="row">
                    <span class="ds-badge ds-badge-danger">cancel</span>
                    <span>{{ proc.candidateName }} · {{ proc.stage }}</span>
                  </div>
                }
                <div class="ds-form-group">
                  <input
                    class="ds-input"
                    [placeholder]="
                      which === 'reject'
                        ? t('talenthub.ongoing.reasonRequired')
                        : t('talenthub.ongoing.reasonOptional')
                    "
                    [value]="reason()"
                    (input)="onReason($event)" />
                </div>
                <div class="row">
                  <button
                    type="button"
                    class="ds-btn ds-btn-primary ds-btn-sm"
                    [disabled]="which === 'reject' && reason().trim().length === 0"
                    (click)="confirmTerminal(which)">
                    {{ t('talenthub.ongoing.confirmAction', { action: which }) }}
                  </button>
                  <button type="button" class="ds-btn ds-btn-ghost ds-btn-sm" (click)="dialog.set(null)">
                    {{ t('talenthub.ongoing.keepOpen') }}
                  </button>
                </div>
              }
              @if (error(); as err) {
                <div class="ds-alert ds-alert-danger">{{ err }}</div>
              }
            </div>
            <div class="ds-modal-footer">
              <button type="button" class="ds-btn ds-btn-ghost" (click)="closeDetail()">
                {{ t('common.close') }}
              </button>
            </div>
          </div>
        </div>
      }
    </div>
  `,
  styles: [
    /* css */ `
      :host {
        display: block;
        height: 100%;
      }
      .wrap {
        padding: 16px;
        height: 100%;
        overflow: auto;
        box-sizing: border-box;
      }
      .head {
        display: flex;
        align-items: center;
        gap: 10px;
        margin-bottom: 12px;
      }
      .head h2 {
        margin: 0;
        color: var(--portal-text-primary);
        font-size: var(--portal-font-size-xl);
        font-weight: var(--portal-font-weight-semibold);
      }
      .muted {
        color: var(--portal-text-muted);
        font-size: var(--portal-font-size-sm);
      }
      .row {
        display: flex;
        gap: 8px;
        align-items: center;
        flex-wrap: wrap;
      }
      .detail-title-text {
        color: var(--portal-text-primary);
        font-weight: var(--portal-font-weight-medium);
      }
      .detail-meta {
        display: flex;
        align-items: center;
        gap: 10px;
        margin-bottom: 12px;
        flex-wrap: wrap;
      }
      .proc span {
        margin-right: 4px;
      }
    `,
  ],
})
export class OngoingComponent implements OnInit, OnDestroy {
  private readonly client = inject(ModuleClient);
  private readonly signalReady = mfeReadyDispatcher();
  private readonly kit = inject(UI_I18N);
  private readonly mfeI18n = inject(MfeI18n);

  /** Escape closes the confirm step first, then the detail modal. */
  private readonly onEscape = (event: KeyboardEvent): void => {
    if (event.key !== 'Escape') return;
    if (this.dialog() !== null) this.dialog.set(null);
    else if (this.detail() !== null) this.closeDetail();
  };

  /** Template convenience; kit labels fall back to English defaults. */
  protected t(key: string, params?: Record<string, string | number>): string {
    return this.kit.t(key, params);
  }

  readonly terminalPhases = TERMINAL_STAGES;
  readonly STAGE_TRANSITIONS = STAGE_TRANSITIONS;
  readonly TERMINAL_PHASES = ['closed', 'rejected', 'archived'];
  readonly phases = PHASES;

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly rfps = signal<Rfp[]>([]);
  readonly detailId = signal<string | null>(null);
  readonly detail = signal<Detail | null>(null);
  readonly candidates = signal<{ id: string; name: string }[]>([]);
  readonly rows = signal<ProcessRow[]>([]);
  readonly filter = signal('');
  readonly term = signal('');
  readonly dialog = signal<'close' | 'reject' | null>(null);
  readonly reason = signal('');
  readonly positionTitle = signal('');
  readonly pickedCandidate = signal('');

  readonly rfpColumns: GridColumn<Rfp>[] = [
    {
      key: 'title',
      header: 'Title',
      headerKey: 'talenthub.capture.col.title',
      accessor: (r) => r.title,
    },
    {
      key: 'kind',
      header: 'Kind',
      headerKey: 'talenthub.capture.col.kind',
      accessor: (r) => r.kind,
    },
    {
      key: 'serviceType',
      header: 'Service',
      headerKey: 'talenthub.capture.col.service',
      accessor: (r) => r.serviceType,
    },
    {
      key: 'phase',
      header: 'Phase',
      headerKey: 'talenthub.ongoing.col.phase',
      accessor: (r) => r.phase,
    },
    {
      key: 'deadline',
      header: 'Deadline',
      headerKey: 'talenthub.capture.col.deadline',
      accessor: (r) => r.deadline ?? '',
      format: (v) =>
        v ? this.kit.formatDate(String(v), { dateStyle: 'medium' }) : '',
      width: '130px',
    },
  ];

  readonly trackRfp = (r: Rfp, _index: number): string => r.id;

  private readonly userSig = signal<{
    sub: string;
    name?: string | null;
    roles: string[];
  } | null>(null);

  readonly canManage = computed(() => canManage(this.userSig()));
  readonly canRecruit = computed(() => canRecruit(this.userSig()));

  readonly visibleRfps = computed(() => {
    const term = this.term().toLowerCase();
    const phaseFilter = this.filter();
    return this.rfps().filter(
      (r) =>
        (phaseFilter === '' || r.phase === phaseFilter) &&
        (term.length === 0 || r.title.toLowerCase().includes(term)),
    );
  });

  readonly activeCount = computed(() => this.activeRows().length);

  readonly activeRows = computed(() =>
    this.rows().filter((row) => !TERMINAL_STAGES.includes(row.stage as never)),
  );

  ngOnInit(): void {
    document.addEventListener('keydown', this.onEscape);
    void this.refreshList();
    void this.loadPortalContext();
  }

  ngOnDestroy(): void {
    document.removeEventListener('keydown', this.onEscape);
  }

  mount(context: {
    user: { sub: string; name?: string | null; roles: string[] } | null;
    language: string;
  }): void {
    this.userSig.set(context.user);
    if (context.language) {
      this.mfeI18n.setContextLanguage(context.language);
    }
  }

  unmount(): void {}

  /**
   * Identity/language arrive via the portal document (same-origin session):
   * Angular Elements exposes no component methods, so the mount() contract
   * cannot be relied on — the element loads its own /api/config context instead.
   */
  private async loadPortalContext(): Promise<void> {
    try {
      const res = await fetch('/api/config');
      if (res.ok) {
        const config = (await res.json()) as {
          user?: { sub: string; name?: string | null; roles: string[] } | null;
          preferences?: { language?: string };
        };
        if (config.user) {
          this.userSig.set(config.user);
        }
        const preferred = config.preferences?.language;
        const language =
          typeof preferred === 'string' && preferred
            ? preferred
            : new Intl.Locale(navigator.language || 'en-GB').toString();
        this.mfeI18n.setContextLanguage(language);
      }
    } catch {
      // No portal context — kit labels fall back to English defaults.
    }
  }

  private async refreshList(): Promise<void> {
    this.loading.set(true);
    try {
      const rfps = await this.clientModuleList();
      this.rfps.set(rfps);
      this.loading.set(false);
      this.signalReady();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
      this.loading.set(false);
      this.signalReady();
    }
  }

  private async clientModuleList(): Promise<Rfp[]> {
    const res = (await this.client.get<{ rfps: Rfp[] }>('/api/rfps')) as { rfps: Rfp[] };
    return res.rfps ?? [];
  }

  protected closeDetail(): void {
    this.detailId.set(null);
    this.detail.set(null);
    this.dialog.set(null);
    this.reason.set('');
  }

  protected async openDetail(id: string): Promise<void> {
    this.detailId.set(id);
    this.loading.set(true);
    try {
      const [detail, candidates] = await Promise.all([
        this.client.get<Detail>('/api/rfps/' + id),
        this.client.get<{ candidates: { id: string; name: string }[] }>('/api/candidates'),
      ]);
      this.candidates.set(candidates.candidates ?? []);
      this.detail.set(detail);
      const nameById = new Map(this.candidates().map((c) => [c.id, c.name]));
      this.rows.set(
        (detail.processes ?? []).map((proc) => ({
          id: proc.id,
          positionId: proc.positionId,
          stage: proc.stage,
          candidateName: nameById.get(proc.candidateId) ?? proc.candidateId,
        })),
      );
      this.loading.set(false);
      this.signalReady();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
      this.loading.set(false);
      this.signalReady();
    }
  }

  protected onFilter(event: Event): void {
    this.filter.set((event.target as HTMLSelectElement).value);
  }

  protected onTerm(event: Event): void {
    this.term.set((event.target as HTMLInputElement).value);
  }

  protected async transition(row: ProcessRow, target: string): Promise<void> {
    try {
      await this.client.patch('/api/processes/' + row.id + '/stage', { toStage: target });
      void this.openDetail(this.detailId()!);
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }

  protected onPickCandidate(event: Event): void {
    this.pickedCandidate.set((event.target as HTMLSelectElement).value);
    const candidateId = this.pickedCandidate();
    if (candidateId) {
      void this.openProcess(candidateId);
    }
  }

  private async openProcess(candidateId: string): Promise<void> {
    const positions = (this.detail()?.positions ?? []);
    if (positions.length === 0) {
      this.error.set(this.t('talenthub.ongoing.createPositionFirst'));
      return;
    }
    try {
      await this.client.post('/api/positions/' + positions[0].id + '/processes', {
        candidateId,
      });
      await this.openDetail(this.detailId()!);
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }

  protected onPositionTitle(event: Event): void {
    this.positionTitle.set((event.target as HTMLInputElement).value);
  }

  protected async createPosition(): Promise<void> {
    try {
      await this.client.post('/api/rfps/' + this.detailId() + '/positions', {
        title: this.positionTitle().trim(),
        headcount: 1,
      });
      this.positionTitle.set('');
      await this.openDetail(this.detailId()!);
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }

  protected onReason(event: Event): void {
    this.reason.set((event.target as HTMLInputElement).value);
  }

  protected async confirmTerminal(which: 'close' | 'reject'): Promise<void> {
    const id = this.detailId();
    if (!id) {
      return;
    }
    const path =
      which === 'close'
        ? '/api/rfps/' + id + '/close'
        : '/api/rfps/' + id + '/reject';
    const body =
      which === 'close'
        ? { confirm: true, reason: this.reason().trim() || undefined }
        : { reason: this.reason().trim() };
    try {
      await this.client.post(path, body);
      this.dialog.set(null);
      this.reason.set('');
      await this.openDetail(id);
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
      this.dialog.set(null);
    }
  }

  protected nextStages(stage: string): string[] {
    return STAGE_TRANSITIONS[stage as keyof typeof STAGE_TRANSITIONS] ?? [];
  }

  protected processesFor(positionId: string): ProcessRow[] {
    return this.rows().filter((row) => row.positionId === positionId);
  }

  protected async archive(): Promise<void> {
    const id = this.detailId();
    if (!id) {
      return;
    }
    try {
      await this.client.post('/api/rfps/' + id + '/archive', {});
      await this.openDetail(id);
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }
}
