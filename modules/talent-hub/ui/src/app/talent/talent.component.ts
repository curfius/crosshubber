import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ModuleClient, canRecruit } from '../shared/module-client';
import { DataGrid, GridCellDirective } from '../shared/grid/data-grid.component';
import { GridColumn } from '../shared/grid/grid-column';
import { UI_I18N } from '../shared/i18n';
import { MfeI18n } from '../shared/mfe-i18n';
import { mfeReadyDispatcher } from '../shared/ready';

type Candidate = {
  id: string;
  name: string;
  headline: string | null;
  seniority: string | null;
  skills: string[];
  languages: string[];
  kind: string;
  availability: string | null;
  contact: Record<string, unknown> | null;
  assignment: Record<string, unknown> | null;
  lastEnrichedAt: string | null;
  stale: boolean;
  notes: string | null;
};

type InboxItem = { ref: string; name: string; headline: string; documentRef: string };

type HistoryEntry = {
  rfpTitle: string | null;
  positionTitle: string | null;
  stage: string;
};

/** Talent pool (P2.8): directory grid + filters + detail modal + sourcing inbox + manual add. */
@Component({
  selector: 'th-talent',
  imports: [DataGrid, GridCellDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      <div class="head">
        <h2>{{ t('talenthub.talent.title') }}</h2>
        <select class="ds-select" [value]="mode()" (change)="onMode($event)">
          <option value="directory">{{ t('talenthub.talent.directory') }}</option>
          <option value="inbox">{{ t('talenthub.talent.inbox') }}</option>
        </select>
      </div>

      @if (error(); as err) {
        <div class="ds-alert ds-alert-danger">{{ err }}</div>
      } @else if (loading() && candidates().length === 0 && inbox().length === 0) {
        <p class="muted">{{ t('common.loading') }}</p>
      } @else if (mode() === 'inbox') {
        <app-data-grid
          [data]="inbox()"
          [columns]="inboxColumns"
          [trackBy]="trackInbox"
          [searchable]="false"
          [groupable]="false"
          [emptyKey]="'talenthub.talent.inboxEmpty'">
          <ng-template gridCell="claim" let-row>
            @if (canWrite()) {
              <button
                type="button"
                class="ds-btn ds-btn-primary ds-btn-sm"
                (click)="claim(row.ref); $event.stopPropagation()">
                {{ t('talenthub.talent.claim') }}
              </button>
            }
          </ng-template>
        </app-data-grid>
        <div class="row actions">
          @if (canWrite()) {
            <input
              class="ds-input"
              [placeholder]="t('talenthub.talent.scanRef')"
              [value]="scanRef()"
              (input)="onScanRef($event)" />
            <button
              type="button"
              class="ds-btn ds-btn-secondary ds-btn-sm"
              [disabled]="scanRef().trim().length === 0"
              (click)="scan()">
              {{ t('talenthub.talent.addByScan') }}
            </button>
          }
        </div>
      } @else {
        <div class="row">
          <input
            class="ds-input"
            [placeholder]="t('talenthub.talent.search')"
            [value]="term()"
            (input)="onTerm($event)" />
          <select class="ds-select" [value]="kindFilter()" (change)="onKind($event)">
            <option value="">{{ t('talenthub.talent.allKinds') }}</option>
            <option value="internal">internal</option>
            <option value="external">external</option>
          </select>
          <label class="ds-label">
            <input
              type="checkbox"
              class="ds-checkbox"
              [checked]="staleOnly()"
              (change)="onStale($event)" />
            {{ t('talenthub.talent.staleOnly') }}
          </label>
        </div>
        <app-data-grid
          [data]="filtered()"
          [columns]="candidateColumns"
          [trackBy]="trackCandidate"
          [searchable]="false"
          [groupable]="false"
          [clickable]="true"
          (rowClick)="openDetail($event.id)"
          [emptyKey]="'talenthub.talent.noMatch'">
          <ng-template gridCell="stale" let-value="value">
            @if (value) {
              <span class="ds-badge ds-badge-warning">{{ t('talenthub.talent.stale') }}</span>
            }
          </ng-template>
        </app-data-grid>
        @if (canWrite()) {
          <div class="ds-card">
            <div class="ds-card-body">
              <h3 class="ds-heading-4">{{ t('talenthub.talent.manualAdd') }}</h3>
              <div class="row">
                <input
                  class="ds-input"
                  [placeholder]="t('talenthub.talent.name')"
                  [value]="newName()"
                  (input)="onName($event)" />
                <input
                  class="ds-input"
                  [placeholder]="t('talenthub.talent.headline')"
                  [value]="newHeadline()"
                  (input)="onHeadline($event)" />
                <select class="ds-select" [value]="newKind()" (change)="onKindPick($event)">
                  <option value="external">external</option>
                  <option value="internal">internal</option>
                </select>
                <button
                  type="button"
                  class="ds-btn ds-btn-primary ds-btn-sm"
                  [disabled]="newName().trim().length === 0"
                  (click)="create()">
                  {{ t('talenthub.talent.add') }}
                </button>
              </div>
            </div>
          </div>
        }
      }

      @if (detail(); as d) {
        <div class="ds-modal-backdrop" (click)="closeDetail()">
          <div class="ds-modal ds-modal-lg" (click)="$event.stopPropagation()">
            <div class="ds-modal-header">
              <span class="ds-modal-title">
                <span class="detail-title-text">{{ d.candidate.name }}</span>
                <span class="ds-badge ds-badge-outline">{{ d.candidate.kind }}</span>
                @if (d.candidate.stale) {
                  <span class="ds-badge ds-badge-warning">{{ t('talenthub.talent.stale') }}</span>
                }
              </span>
              <button type="button" class="ds-modal-close" [attr.aria-label]="t('common.close')" (click)="closeDetail()">×</button>
            </div>
            <div class="ds-modal-body">
              @if (d.candidate.headline; as headline) {
                <div class="muted">{{ headline }}</div>
              }
              @if (d.candidate.skills.length > 0) {
                <div class="row">
                  @for (skill of d.candidate.skills; track skill) {
                    <span class="ds-badge ds-badge-outline">{{ skill }}</span>
                  }
                </div>
              }
              <div class="muted">
                seniority: {{ d.candidate.seniority ?? '—' }} · languages:
                {{ d.candidate.languages.join(', ') || '—' }} · availability:
                {{ d.candidate.availability ?? '—' }}
              </div>
              @if (d.candidate.assignment !== null) {
                <div class="muted assignment">
                  {{ stringify(d.candidate.assignment) }}
                </div>
              }
              <h3 class="ds-heading-4">{{ t('talenthub.talent.processHistory') }}</h3>
              @for (h of d.history; track h.stage + h.positionTitle) {
                <div class="row">
                  <span>{{ h.positionTitle ?? '—' }}</span>
                  <span class="muted">in {{ h.rfpTitle ?? '—' }}</span>
                  <span class="ds-badge ds-badge-outline">{{ h.stage }}</span>
                </div>
              }
              @if (d.history.length === 0) {
                <div class="muted">{{ t('talenthub.talent.noProcesses') }}</div>
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
      .row,
      .actions {
        display: flex;
        gap: 8px;
        align-items: center;
        flex-wrap: wrap;
      }
      .detail-title-text {
        color: var(--portal-text-primary);
        font-weight: var(--portal-font-weight-medium);
      }
      .assignment {
        white-space: pre-wrap;
      }
    `,
  ],
})
export class TalentComponent implements OnInit, OnDestroy {
  private readonly client = inject(ModuleClient);
  private readonly signalReady = mfeReadyDispatcher();
  private readonly kit = inject(UI_I18N);
  private readonly mfeI18n = inject(MfeI18n);

  /** Escape closes the detail modal. */
  private readonly onEscape = (event: KeyboardEvent): void => {
    if (event.key !== 'Escape') return;
    if (this.detail() !== null) this.closeDetail();
  };

  /** Template convenience; kit labels fall back to English defaults. */
  protected t(key: string, params?: Record<string, string | number>): string {
    return this.kit.t(key, params);
  }

  readonly mode = signal<'directory' | 'inbox'>('directory');
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  readonly candidates = signal<Candidate[]>([]);
  readonly term = signal('');
  readonly kindFilter = signal('');
  readonly staleOnly = signal(false);
  readonly detailId = signal<string | null>(null);
  readonly detail = signal<{ candidate: Candidate; history: HistoryEntry[] } | null>(null);
  readonly inbox = signal<InboxItem[]>([]);
  readonly newName = signal('');
  readonly newHeadline = signal('');
  readonly newKind = signal<'external' | 'internal'>('external');
  readonly scanRef = signal('');

  readonly candidateColumns: GridColumn<Candidate>[] = [
    {
      key: 'name',
      header: 'Name',
      headerKey: 'talenthub.talent.col.name',
      accessor: (c) => c.name,
    },
    {
      key: 'kind',
      header: 'Kind',
      headerKey: 'talenthub.talent.col.kind',
      accessor: (c) => c.kind,
      width: '100px',
    },
    {
      key: 'seniority',
      header: 'Seniority',
      headerKey: 'talenthub.talent.col.seniority',
      accessor: (c) => c.seniority ?? '',
      width: '110px',
    },
    {
      key: 'skills',
      header: 'Skills',
      headerKey: 'talenthub.talent.col.skills',
      accessor: (c) => c.skills,
      format: (v) => (Array.isArray(v) ? v.slice(0, 6).join(' · ') : ''),
    },
    {
      key: 'availability',
      header: 'Availability',
      headerKey: 'talenthub.talent.col.availability',
      accessor: (c) => c.availability ?? '',
      width: '120px',
    },
    {
      key: 'stale',
      header: 'Status',
      headerKey: 'talenthub.talent.col.status',
      accessor: (c) => c.stale,
      sortable: false,
      searchable: false,
      width: '110px',
    },
  ];

  readonly inboxColumns: GridColumn<InboxItem>[] = [
    {
      key: 'name',
      header: 'Name',
      headerKey: 'talenthub.talent.col.name',
      accessor: (i) => i.name,
    },
    {
      key: 'headline',
      header: 'Headline',
      headerKey: 'talenthub.talent.headline',
      accessor: (i) => i.headline,
    },
    {
      key: 'ref',
      header: 'Ref',
      headerKey: 'talenthub.talent.scanRef',
      accessor: (i) => i.ref,
    },
    {
      key: 'claim',
      header: '',
      accessor: () => '',
      sortable: false,
      searchable: false,
      align: 'right',
      width: '110px',
    },
  ];

  readonly trackCandidate = (c: Candidate, _index: number): string => c.id;
  readonly trackInbox = (i: InboxItem, _index: number): string => i.ref;

  private readonly userSig = signal<{
    sub: string;
    name?: string | null;
    roles: string[];
  } | null>(null);

  readonly canWrite = computed(() => canRecruit(this.userSig()));
  readonly filtered = computed(() => {
    const term = this.term().toLowerCase();
    const kind = this.kindFilter();
    return this.candidates().filter(
      (c) =>
        (term.length === 0 || c.name.toLowerCase().includes(term) ||
          c.skills.some((skill) => skill.toLowerCase().includes(term))) &&
        (kind === '' || c.kind === kind) &&
        (!this.staleOnly() || c.stale),
    );
  });

  ngOnInit(): void {
    document.addEventListener('keydown', this.onEscape);
    void this.refresh();
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

  private async refresh(): Promise<void> {
    this.loading.set(true);
    try {
      if (this.mode() === 'inbox') {
        const res = await this.client.get<{ inbox: InboxItem[] }>('/api/sourcing/inbox');
        this.inbox.set(res.inbox ?? []);
      } else {
        const res = await this.client.get<{ candidates: Candidate[] }>('/api/candidates');
        this.candidates.set(res.candidates ?? []);
      }
      this.loading.set(false);
      this.signalReady();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
      this.loading.set(false);
      this.signalReady();
    }
  }

  protected stringify(value: Record<string, unknown> | null): string {
    return value === null ? '' : JSON.stringify(value);
  }

  protected onMode(event: Event): void {
    this.mode.set((event.target as HTMLSelectElement).value as 'directory' | 'inbox');
    this.error.set(null);
    void this.refresh();
  }

  protected onTerm(event: Event): void {
    this.term.set((event.target as HTMLInputElement).value);
  }

  protected onKind(event: Event): void {
    this.kindFilter.set((event.target as HTMLSelectElement).value);
  }

  protected onStale(event: Event): void {
    this.staleOnly.set((event.target as HTMLInputElement).checked);
  }

  protected openDetail(id: string): void {
    this.detailId.set(id);
    void this.client
      .get<{ candidate: Candidate; history: HistoryEntry[] }>('/api/candidates/' + id)
      .then(
        (detail) => {
          this.detail.set(detail);
        },
        (err) => this.error.set(err instanceof Error ? err.message : String(err)),
      );
  }

  protected closeDetail(): void {
    this.detailId.set(null);
    this.detail.set(null);
  }

  protected onName(event: Event): void {
    this.newName.set((event.target as HTMLInputElement).value);
  }

  protected onHeadline(event: Event): void {
    this.newHeadline.set((event.target as HTMLInputElement).value);
  }

  protected onKindPick(event: Event): void {
    this.newKind.set((event.target as HTMLSelectElement).value as 'external' | 'internal');
  }

  protected async create(): Promise<void> {
    try {
      await this.client.post('/api/candidates', {
        name: this.newName().trim(),
        headline: this.newHeadline().trim() || null,
        kind: this.newKind(),
      });
      this.newName.set('');
      this.newHeadline.set('');
      await this.refresh();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }

  protected onScanRef(event: Event): void {
    this.scanRef.set((event.target as HTMLInputElement).value);
  }

  protected async scan(): Promise<void> {
    try {
      await this.client.post('/api/candidates/scan', {
        documentRef: this.scanRef().trim(),
      });
      this.scanRef.set('');
      await this.refresh();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }

  protected async claim(ref: string): Promise<void> {
    try {
      await this.client.post('/api/sourcing/inbox/' + ref + '/claim', {});
      await this.refresh();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }
}
