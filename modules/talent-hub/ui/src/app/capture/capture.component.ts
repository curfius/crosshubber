import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  ViewEncapsulation,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ModuleClient, Rfp, canManage } from '../shared/module-client';
import { SHARED_STYLES } from '../shared/styles';
import { mfeReadyDispatcher } from '../shared/ready';

/** RFP Capture app (P1.6): drafts list + capture form; approve button for managers. */
@Component({
  selector: 'th-capture',
  encapsulation: ViewEncapsulation.ShadowDom,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      <div class="head">
        <h2>RFP Capture</h2>
        <span class="muted">{{ drafts().length }} draft(s)</span>
        <button class="primary" (click)="openCapture()">Capture</button>
      </div>

      @if (mode() === 'capture') {
        <div class="card">
          <h3>New request</h3>
          <div class="field">
            <label>Client name</label>
            <input [value]="clientName()" (input)="onClientName($event)" />
          </div>
          <div class="field">
            <label>Title (AI suggests when empty)</label>
            <input [value]="title()" (input)="onTitle($event)" />
          </div>
          <div class="field">
            <label>Request text</label>
            <textarea rows="7" [value]="description()" (input)="onDescription($event)"></textarea>
          </div>
          <div class="row">
            <button class="primary" (click)="submitCapture()">Capture as draft</button>
            <button (click)="backToList()">Cancel</button>
          </div>
          @if (error(); as err) {
            <div class="error">{{ err }}</div>
          }
        </div>
      } @else if (mode() === 'review') {
        @if (selected(); as rfp) {
          <div class="card">
            <div class="row">
              <strong>{{ rfp.title || '(untitled)' }}</strong>
              <span class="badge">{{ rfp.phase }}</span>
              <button (click)="backToList()">Back</button>
            </div>
            @if (rfp.phase === 'draft') {
              @if (analysisState(rfp) !== 'configured') {
                <div class="card error">
                  {{ analysisNarrative(rfp) }} The draft was captured as-is; review and approve
                  below.
                </div>
              }
              <div class="field">
                <label>Title</label>
                <input [value]="rfp.title" (change)="onEditTitle($event)" />
              </div>
              <h3>Positions</h3>
              @for (row of rows(); track $index) {
                <div class="row">
                  <input [value]="row.title" (change)="onRowTitle($event, $index)" />
                  <input
                    type="number"
                    min="1"
                    [value]="row.headcount"
                    (change)="onRowCount($event, $index)"
                  />
                </div>
              }
              <div class="row">
                @if (canManage()) {
                  <button class="primary" (click)="approve()">Approve</button>
                }
              </div>
            }
            <details>
              <summary>Raw capture text</summary>
              <div class="muted">{{ rfp.description }}</div>
            </details>
          </div>
        }
      } @else {
        @if (drafts().length === 0) {
          <div class="card muted">No drafts yet — capture the first request.</div>
        }
        @for (r of drafts(); track r.id) {
          <div class="card clickable" (click)="openReview(r)">
            <div class="row">
              <strong>{{ r.title || '(untitled)' }}</strong>
              <span class="badge">{{ r.kind }}</span>
              <span class="badge">{{ r.serviceType }}</span>
              @if (analysisState(r) !== 'configured') {
                <span class="badge warn">analysis: {{ analysisStatus(r) }}</span>
              }
            </div>
          </div>
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
        align-items: baseline;
        gap: 12px;
      }
      .head button {
        margin-left: auto;
      }
      .row {
        display: flex;
        gap: 8px;
        align-items: baseline;
      }
      button.primary {
        background: var(--portal-accent-primary, #2563eb);
        color: #fff;
        border: none;
      }
      button {
        border: 1px solid var(--portal-badge-border, #d1d5db);
        background: transparent;
        border-radius: 8px;
        cursor: pointer;
      }
      .error {
        color: var(--portal-status-danger, #dc2626);
      }
      .clickable {
        cursor: pointer;
      }
    `,
  ],
})
export class CaptureComponent implements OnInit {
  private readonly client = inject(ModuleClient);
  private readonly signalReady = mfeReadyDispatcher();

  readonly mode = signal<'list' | 'capture' | 'review'>('list');
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  readonly drafts = signal<Rfp[]>([]);
  readonly clientName = signal('');
  readonly title = signal('');
  readonly description = signal('');
  readonly kind = signal<'rfp' | 'rfq'>('rfp');
  readonly serviceType = signal<'tm' | 'direct'>('tm');

  readonly selected = signal<Rfp | null>(null);
  readonly editedTitle = signal('');
  readonly rows = signal<{ title: string; headcount: number }[]>([]);

  private user: { sub: string; name?: string | null; roles: string[] } | null = null;

  readonly canManage = computed(() => canManage(this.user));

  ngOnInit(): void {
    void this.refresh();
  }

  /** Portal mount hook (module-outlet contract): context carries user + language. */
  mount(context: {
    user: { sub: string; name?: string | null; roles: string[] } | null;
    language: string;
  }): void {
    this.user = context.user;
  }

  unmount(): void {}

  private async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      const drafts = await this.client.get<{ rfps: Rfp[] }>('/api/rfps/drafts');
      this.drafts.set(drafts.rfps ?? []);
      this.loading.set(false);
      this.signalReady();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
      this.loading.set(false);
      this.signalReady();
    }
  }

  private openCapture(): void {
    this.error.set(null);
    this.mode.set('capture');
  }

  private openReview(rfp: Rfp): void {
    this.error.set(null);
    this.editedTitle.set('');
    this.rows.set(positionsOf(rfp));
    this.selected.set(rfp);
    this.mode.set('review');
  }

  private backToList(): void {
    this.error.set(null);
    this.mode.set('list');
    void this.refresh();
  }

  private onClientName(event: Event): void {
    this.clientName.set((event.target as HTMLInputElement).value);
  }

  private onTitle(event: Event): void {
    this.title.set((event.target as HTMLInputElement).value);
  }

  private onDescription(event: Event): void {
    this.description.set((event.target as HTMLTextAreaElement).value);
  }

  private analysisState(rfp: Rfp): string {
    const status = (rfp.analysis ?? {})['status'];
    return typeof status === 'string' ? status : 'configured';
  }

  private analysisStatus(rfp: Rfp): string {
    return this.analysisState(rfp);
  }

  private analysisNarrative(rfp: Rfp): string {
    return this.analysisState(rfp) === 'unconfigured'
      ? 'No LLM configured for this tenant — the description was stored as-is.'
      : 'Analysis failed — the description was stored as-is.';
  }

  private onEditTitle(event: Event): void {
    this.editedTitle.set((event.target as HTMLInputElement).value);
  }

  private onRowTitle(event: Event, index: number): void {
    const value = (event.target as HTMLInputElement).value;
    this.rows.update((rows) => rows.map((row, i) => (i === index ? { ...row, title: value } : row)));
  }

  private onRowCount(event: Event, index: number): void {
    const value = Number((event.target as HTMLInputElement).value);
    if (!Number.isFinite(value) || value < 1) {
      return;
    }
    this.rows.update((rows) =>
      rows.map((row, i) => (i === index ? { ...row, headcount: Math.trunc(value) } : row)),
    );
  }

  private async approve(): Promise<void> {
    const rfp = this.selected();
    if (!rfp) {
      return;
    }
    try {
      await this.client.post('/api/rfps/' + rfp.id + '/approve', {
        title: this.editedTitle().trim() || rfp.title || null,
        positions: this.rows(),
      });
      this.editedTitle.set('');
      this.selected.set(null);
      this.mode.set('list');
      void this.refresh();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }

  private async submitCapture(): Promise<void> {
    if (this.description().trim().length === 0) {
      this.error.set('Describe the request first.');
      return;
    }
    try {
      await this.client.post('/api/rfps/capture', {
        kind: this.kind(),
        serviceType: this.serviceType(),
        description: this.description().trim(),
        documentRefs: [],
      });
      this.backToList();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }
}

function positionsOf(rfp: Rfp): { title: string; headcount: number }[] {
  const analysis = (rfp.analysis ?? {}) as Record<string, unknown>;
  const raw = Array.isArray(analysis['positions']) ? analysis['positions'] : [];
  return (raw as Record<string, unknown>[])
    .map((row) => ({
      title: typeof row['title'] === 'string' ? row['title'] : '',
      headcount: typeof row['headcount'] === 'number' ? row['headcount'] : 1,
    }))
    .slice(0, 20);
}
