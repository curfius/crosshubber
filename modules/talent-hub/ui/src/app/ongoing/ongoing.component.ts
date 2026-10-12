import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  ViewEncapsulation,
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
import { SHARED_STYLES } from '../shared/styles';
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

/** Ongoing RFPs (P2.7): phase board + detail with processes, submissions, close/reject. */
@Component({
  selector: 'th-ongoing',
  encapsulation: ViewEncapsulation.ShadowDom,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      <div class="head">
        <h2>Ongoing RFPs</h2>
        @if (detailId() !== null) {
          <button (click)="openList()">Back</button>
        } @else {
          <select [value]="filter()" (change)="onFilter($event)">
            @for (phase of phases; track phase) {
              <option [value]="phase">{{ phase }}</option>
            }
            <option value="">all phases</option>
          </select>
          <input placeholder="search title" [value]="term()" (input)="onTerm($event)" />
        }
      </div>

      @if (error(); as err) {
        <div class="card error">{{ err }}</div>
      } @else if (loading()) {
        <div class="muted">Loading…</div>
      } @else if (detailId() !== null) {
        @if (detail(); as d) {
          <div class="card">
            <div class="row">
              <strong>{{ d.rfp.title }}</strong>
              <span class="badge">{{ d.rfp.phase }}</span>
              <span class="badge">{{ d.rfp.kind }}</span>
              <span class="badge">{{ d.rfp.serviceType }}</span>
            </div>
            <div class="muted">
              client: {{ d.client.name }} @if (d.client.industry) {({{ d.client.industry }})}
            </div>
          </div>

          <div class="card">
            <h3>Positions</h3>
            @for (position of d.positions; track position.id) {
              <div class="row">
                <strong>{{ position.title }}</strong>
                <span class="badge">×{{ position.headcount }}</span>
                <span class="badge">{{ position.status }}</span>
                @for (proc of processesFor(position.id); track proc.id) {
                  <span class="proc">
                    {{ proc.candidateName }}
                    <span class="badge">{{ proc.stage }}</span>
                    @if (canRecruit()) {
                      @for (target of nextStages(proc.stage); track target) {
                        <button (click)="transition(proc, target)">{{ target }}</button>
                      }
                    }
                  </span>
                }
              </div>
            }
            @if (canRecruit() && d.rfp.phase !== 'draft') {
              <div class="row">
                <select (change)="onPickCandidate($event)">
                  <option value="">open process for candidate…</option>
                  @for (c of candidates(); track c.id) {
                    <option [value]="c.id">{{ c.name }}</option>
                  }
                </select>
              </div>
            }
            @if (canRecruit()) {
              <div class="row">
                <input placeholder="new position title" [value]="positionTitle()" (input)="onPositionTitle($event)" />
                <button
                  [disabled]="positionTitle().trim().length === 0"
                  (click)="createPosition()"
                >
                  add position
                </button>
              </div>
            }
          </div>

          <div class="card">
            <h3>Phase history</h3>
            @for (h of d.phaseHistory; track h.at) {
              <div class="row">
                <span>{{ h.from ?? 'created' }} → {{ h.to }}</span>
                <span class="badge">{{ h.trigger }}</span>
                <span class="muted">{{ h.actor }}</span>
              </div>
            }
          </div>

          <div class="card">
            <h3>Submissions</h3>
            @for (s of d.submissions; track s.id) {
              <div class="row">
                <strong>{{ s.sentBy }}</strong>
                <span class="badge" [class.ok]="s.response?.outcome === 'accepted'">
                  {{ s.response?.outcome ?? 'waiting' }}
                </span>
                <span class="muted">{{ s.note }}</span>
              </div>
            }
            @if (d.submissions.length === 0) {
              <div class="muted">No submissions yet.</div>
            }
          </div>

          @if (canManage()) {
            <div class="row">
              @if (!TERMINAL_PHASES.includes(d.rfp.phase)) {
                <button class="primary" (click)="dialog.set('close')">Close…</button>
                <button (click)="dialog.set('reject')">Reject…</button>
              } @else if (d.rfp.phase !== 'archived') {
                <button (click)="archive()">Archive</button>
              }
            </div>
          }
        }
        @if (dialog(); as which) {
          @if (detail(); as d) {
            <div class="overlay" (click)="dialog.set(null)">
              <div class="card dialog" (click)="$event.stopPropagation()">
                <h3>Confirm {{ which }}</h3>
                <p class="muted">{{ activeCount() }} active process(es) will be cancelled.</p>
                @for (proc of activeRows(); track proc.id) {
                  <div class="row">
                    <span class="badge danger">cancel</span>
                    <span>{{ proc.candidateName }} · {{ proc.stage }}</span>
                  </div>
                }
                <input
                  placeholder="reason {{ which === 'reject' ? '(required)' : '(optional)' }}"
                  [value]="reason()"
                  (input)="onReason($event)"
                />
                <div class="row">
                  <button
                    class="primary"
                    [disabled]="which === 'reject' && reason().trim().length === 0"
                    (click)="confirmTerminal(which)"
                  >
                    confirm {{ which }}
                  </button>
                  <button (click)="dialog.set(null)">keep open</button>
                </div>
              </div>
            </div>
          }
        }
      } @else {
        @for (group of groups(); track group.phase) {
          @if (group.rfps.length > 0) {
            <div class="card">
              <h3>{{ group.phase }} ({{ group.rfps.length }})</h3>
              @for (r of group.rfps; track r.id) {
                <div class="row clickable" (click)="openDetail(r.id)">
                  <strong>{{ r.title }}</strong>
                  <span class="badge">{{ r.kind }}</span>
                  <span class="badge">{{ r.serviceType }}</span>
                  @if (r.deadline; as dl) {
                  <span class="muted">due {{ dateOf(dl) }}</span>
                  }
                </div>
              }
            </div>
          }
        }
      }
    </div>
  `,
  styles: [
    SHARED_STYLES +
      /* css */ `
      .wrap {
        padding: 16px;
        height: 100%;
        overflow: auto;
        box-sizing: border-box;
      }
      .head {
        display: flex;
        gap: 10px;
        align-items: center;
        margin-bottom: 12px;
      }
      .head h2 {
        margin: 0;
      }
      .row,
      .actions {
        display: flex;
        gap: 8px;
        align-items: center;
        flex-wrap: wrap;
      }
      input,
      select,
      button {
        border: 1px solid var(--portal-badge-border, #d1d5db);
        border-radius: 8px;
        padding: 5px 10px;
        font: inherit;
        color: inherit;
        background: transparent;
      }
      button.primary {
        background: var(--portal-accent-primary, #2563eb);
        color: #fff;
        border: none;
      }
      button {
        border: 1px solid var(--portal-badge-border, #d1d5db);
        background: transparent;
        cursor: pointer;
      }
      .proc span {
        margin-right: 4px;
      }
      .clickable {
        cursor: pointer;
        padding: 4px 0;
      }
      .overlay {
        position: fixed;
        inset: 0;
        background: rgba(0, 0, 0, 0.4);
        display: flex;
        align-items: center;
        justify-content: center;
      }
      .dialog {
        max-width: 460px;
        display: flex;
        flex-direction: column;
        gap: 10px;
      }
    `,
  ],
})
export class OngoingComponent implements OnInit {
  private readonly client = inject(ModuleClient);
  private readonly signalReady = mfeReadyDispatcher();

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

  private user: { sub: string; name?: string | null; roles: string[] } | null = null;

  readonly canManage = computed(() => canManage(this.user));
  readonly canRecruit = computed(() => canRecruit(this.user));

  readonly groups = computed(() => {
    const term = this.term().toLowerCase();
    const phaseFilter = this.filter();
    return this.phases.map((phase) => ({
      phase,
      rfps: this.rfps().filter(
        (r) =>
          (phaseFilter === '' || r.phase === phaseFilter) &&
          (term.length === 0 || r.title.toLowerCase().includes(term)),
      ),
    }));
  });

  readonly activeCount = computed(() => this.activeRows().length);

  readonly activeRows = computed(() =>
    this.rows().filter((row) => !TERMINAL_STAGES.includes(row.stage as never)),
  );

  ngOnInit(): void {
    void this.refreshList();
  }

  mount(context: {
    user: { sub: string; name?: string | null; roles: string[] } | null;
    language: string;
  }): void {
    this.user = context.user;
  }

  unmount(): void {}

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

  openList(): void {
    this.detailId.set(null);
    this.detail.set(null);
    this.dialog.set(null);
    void this.refreshList();
  }

  private async openDetail(id: string): Promise<void> {
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

  dateOf(iso: string): string {
    return new Date(iso).toLocaleDateString();
  }

  onFilter(event: Event): void {
    this.filter.set((event.target as HTMLSelectElement).value);
  }

  onTerm(event: Event): void {
    this.term.set((event.target as HTMLInputElement).value);
  }

  private async transition(row: ProcessRow, target: string): Promise<void> {
    try {
      await this.client.patch('/api/processes/' + row.id + '/stage', { toStage: target });
      void this.openDetail(this.detailId()!);
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }

  onPickCandidate(event: Event): void {
    this.pickedCandidate.set((event.target as HTMLSelectElement).value);
    const candidateId = this.pickedCandidate();
    if (candidateId) {
      void this.openProcess(candidateId);
    }
  }

  private async openProcess(candidateId: string): Promise<void> {
    const positions = (this.detail()?.positions ?? []);
    if (positions.length === 0) {
      this.error.set('create a position first.');
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

  onPositionTitle(event: Event): void {
    this.positionTitle.set((event.target as HTMLInputElement).value);
  }

  private async createPosition(): Promise<void> {
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

  onReason(event: Event): void {
    this.reason.set((event.target as HTMLInputElement).value);
  }

  private async confirmTerminal(which: 'close' | 'reject'): Promise<void> {
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

  nextStages(stage: string): string[] {
    return STAGE_TRANSITIONS[stage as keyof typeof STAGE_TRANSITIONS] ?? [];
  }

  processesFor(positionId: string): ProcessRow[] {
    return this.rows().filter((row) => row.positionId === positionId);
  }

  private async archive(): Promise<void> {
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

