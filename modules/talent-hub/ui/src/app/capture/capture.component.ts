import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';

import {
  DraftItem,
  DocumentMeta,
  ModuleClient,
  Rfp,
  RfpDetail,
  canManage,
} from '../shared/module-client';
import { GridCellDirective, DataGrid } from '../shared/grid/data-grid.component';
import { GridBulkAction, GridColumn } from '../shared/grid/grid-column';
import { UI_I18N } from '../shared/i18n';
import { MfeI18n } from '../shared/mfe-i18n';
import { mfeReadyDispatcher } from '../shared/ready';

interface PortalUserContext {
  sub: string;
  name?: string | null;
  roles: string[];
}

interface PositionRow {
  title: string;
  headcount: number;
}

/** Capture payload — everything the POST /api/rfps/capture contract needs. */
interface CreatePayload {
  clientRef?: string | null;
  newClient?: { name: string } | null;
  kind: string;
  serviceType: string;
  title: string | null;
  deadline: string | null;
  description: string;
  documentRefs: string[];
}

/**
 * RFP Capture app: drafts grid → detail modal (review/approve) + create-new
 * modal with attachments. The element runs in the portal document, so the
 * design-system `.ds-*` classes and `--portal-*` tokens style it from there;
 * module styles cover layout only.
 */
@Component({
  selector: 'th-capture',
  imports: [DataGrid, GridCellDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      @if (mode() === 'create') {
        <div class="ds-modal-backdrop" (click)="closeCreate()">
          <div class="ds-modal ds-modal-lg" (click)="$event.stopPropagation()">
            <div class="ds-modal-header">
              <span class="ds-modal-title">{{ t('talenthub.capture.modal.create') }}</span>
              <button type="button" class="ds-modal-close" [attr.aria-label]="t('common.close')" (click)="closeCreate()">×</button>
            </div>
            <div class="ds-modal-body">
              <div class="ds-form-group">
                <label class="ds-label">{{ t('talenthub.capture.field.client') }}</label>
                <select class="ds-select" [value]="clientChoice()" (change)="onClientChoice($event)">
                  @if (clients().length > 0) {
                    <option value="pick">{{ t('talenthub.capture.client.pick') }}</option>
                  }
                  <option value="new">{{ t('talenthub.capture.client.new') }}</option>
                </select>
              </div>
              @if (clientChoice() === 'pick' && clients().length > 0) {
                <div class="ds-form-group">
                  <label class="ds-label">{{ t('talenthub.capture.client.name') }}</label>
                  <select class="ds-select" [value]="clientRef()" (change)="onClientRef($event)">
                    @for (c of clients(); track c.id) {
                      <option [value]="c.id">{{ c.name }}</option>
                    }
                  </select>
                </div>
              } @else {
                <div class="ds-form-group">
                  <label class="ds-label">{{ t('talenthub.capture.client.newName') }}</label>
                  <input
                    class="ds-input"
                    [value]="clientName()"
                    (input)="onClientName($event)"
                    [placeholder]="t('talenthub.capture.client.newNameHint')" />
                </div>
              }
              <div class="ds-form-group">
                <label class="ds-label">{{ t('talenthub.capture.field.title') }}</label>
                <input class="ds-input" [value]="title()" (input)="onTitle($event)" />
                <span class="ds-form-hint">{{ t('talenthub.capture.title.hint') }}</span>
              </div>
              <div class="field-split">
                <div class="ds-form-group">
                  <label class="ds-label">{{ t('talenthub.capture.field.kind') }}</label>
                  <select class="ds-select" [value]="kind()" (change)="onKind($event)">
                    <option value="rfp">{{ t('talenthub.capture.kind.rfp') }}</option>
                    <option value="rfq">{{ t('talenthub.capture.kind.rfq') }}</option>
                  </select>
                </div>
                <div class="ds-form-group">
                  <label class="ds-label">{{ t('talenthub.capture.field.serviceType') }}</label>
                  <select class="ds-select" [value]="serviceType()" (change)="onServiceType($event)">
                    <option value="tm">{{ t('talenthub.capture.service.tm') }}</option>
                    <option value="direct">{{ t('talenthub.capture.service.direct') }}</option>
                  </select>
                </div>
                <div class="ds-form-group">
                  <label class="ds-label">{{ t('talenthub.capture.field.deadline') }}</label>
                  <input type="date" class="ds-input" [value]="deadline()" (change)="onDeadline($event)" />
                  <span class="ds-form-hint">{{ t('talenthub.capture.deadline.hint') }}</span>
                </div>
              </div>
              <div class="ds-form-group">
                <label class="ds-label">{{ t('talenthub.capture.field.request') }}</label>
                <textarea
                  rows="6"
                  class="ds-textarea"
                  [value]="description()"
                  (input)="onDescription($event)"></textarea>
              </div>
              <div class="ds-form-group">
                <label class="ds-label">{{ t('talenthub.capture.attachments.label') }}</label>
                <input #files type="file" multiple class="hidden" (change)="onFiles($event)" />
                <div class="attach-row">
                  <button type="button" class="ds-btn ds-btn-secondary ds-btn-sm" [disabled]="attaching() > 0" (click)="pickFiles()">
                    {{ t('talenthub.capture.attachments.add') }}
                  </button>
                  @if (attaching() > 0) {
                    <span class="muted">{{ t('talenthub.capture.attachments.uploading', { count: attaching() }) }}</span>
                  }
                </div>
                <span class="ds-form-hint">{{ t('talenthub.capture.attachments.hint') }}</span>
                @for (a of attachments(); track a.ref) {
                  <div class="attach-row">
                    <span class="attach-name">{{ a.name }}</span>
                    <span class="muted">{{ formatSize(a.size) }}</span>
                    <button type="button" class="attach-remove" [attr.aria-label]="t('common.remove')" (click)="removeAttachment(a.ref)">×</button>
                  </div>
                }
              </div>
              @if (error(); as err) {
                <div class="ds-alert ds-alert-danger">{{ err }}</div>
              }
            </div>
            <div class="ds-modal-footer">
              <button type="button" class="ds-btn ds-btn-ghost" (click)="closeCreate()">
                {{ t('talenthub.capture.cancel') }}
              </button>
              <button type="button" class="ds-btn ds-btn-primary" [disabled]="saving()" (click)="submitCreate()">
                {{ saving() ? t('talenthub.capture.saving') : t('talenthub.capture.save') }}
              </button>
            </div>
          </div>
        </div>
      } @else if (mode() === 'detail') {
        @if (selected(); as d) {
          <div class="ds-modal-backdrop" (click)="closeDetail()">
            <div class="ds-modal ds-modal-lg" (click)="$event.stopPropagation()">
              <div class="ds-modal-header">
                <span class="ds-modal-title">
                  <span class="detail-title-text">
                    @if (d.rfp.title) { {{ d.rfp.title }} } @else { {{ t('talenthub.capture.untitled') }} }
                  </span>
                  <span class="ds-badge ds-badge-outline">{{ t('talenthub.capture.phase.' + d.rfp.phase) }}</span>
                </span>
                <button type="button" class="ds-modal-close" [attr.aria-label]="t('common.close')" (click)="closeDetail()">×</button>
              </div>
              <div class="ds-modal-body">
                <div class="detail-meta">
                  <span class="muted">{{ d.client?.name ?? t('talenthub.capture.unknownClient') }}</span>
                  <span class="ds-badge ds-badge-outline">{{ t('talenthub.capture.origin.' + d.rfp.source) }}</span>
                  @if (d.rfp.deadline) {
                    <span class="muted">{{ t('talenthub.capture.col.deadline') }}: {{ tDate(d.rfp.deadline) }}</span>
                  }
                </div>
                @if (analysisState(d.rfp) !== 'configured') {
                  <div class="ds-alert ds-alert-warning">
                    {{ t('talenthub.capture.analysis.' + analysisState(d.rfp)) }}
                    {{ t('talenthub.capture.analysis.storedAsIs') }}
                  </div>
                }
                @if (d.rfp.phase === 'draft') {
                  <div class="ds-form-group">
                    <label class="ds-label">{{ t('talenthub.capture.field.title') }}</label>
                    <input class="ds-input" [value]="editedTitle() || d.rfp.title" (change)="onEditTitle($event)" />
                  </div>
                  <h3 class="ds-heading-4">{{ t('talenthub.capture.detail.positions') }}</h3>
                  <div class="row-headers">
                    <span class="ds-label">{{ t('talenthub.capture.detail.position') }}</span>
                    <span class="ds-label">{{ t('talenthub.capture.detail.headcount') }}</span>
                  </div>
                  @for (row of rows(); track $index) {
                    <div class="row-edit">
                      <input class="ds-input" [value]="row.title" (change)="onRowTitle($event, $index)" />
                      <input
                        type="number"
                        min="1"
                        class="ds-input"
                        [value]="row.headcount"
                        (change)="onRowCount($event, $index)" />
                    </div>
                  }
                }
                @if (documentsOf(d).length > 0) {
                  <h3 class="ds-heading-4">{{ t('talenthub.capture.attachments.label') }}</h3>
                  @for (doc of documentsOf(d); track doc.ref) {
                    <div class="attach-row">
                      <span class="attach-name">{{ doc.name }}</span>
                      <span class="muted">{{ formatSize(doc.size) }}</span>
                    </div>
                  }
                }
                <details>
                  <summary>{{ t('talenthub.capture.detail.raw') }}</summary>
                  <div class="muted">{{ d.rfp.description }}</div>
                </details>
                @if (error(); as err) {
                  <div class="ds-alert ds-alert-danger">{{ err }}</div>
                }
              </div>
              <div class="ds-modal-footer">
                <button type="button" class="ds-btn ds-btn-ghost" (click)="closeDetail()">
                  {{ t('common.close') }}
                </button>
                @if (d.rfp.phase === 'draft' && canManage()) {
                  <button type="button" class="ds-btn ds-btn-primary" [disabled]="approving()" (click)="approve()">
                    {{ approving() ? t('talenthub.capture.saving') : t('talenthub.capture.approve') }}
                  </button>
                }
              </div>
            </div>
          </div>
        }
      } @else {
        <div class="head">
          <h2>{{ t('talenthub.capture.title') }}</h2>
        </div>
        @if (error(); as err) {
          <div class="ds-alert ds-alert-danger">{{ err }}</div>
        }
        @if (loading() && drafts().length === 0) {
          <p class="muted">{{ t('common.loading') }}</p>
        } @else {
          <app-data-grid
            [data]="drafts()"
            [columns]="draftColumns"
            [trackBy]="trackDraft"
            [emptyKey]="'talenthub.capture.empty'"
            [selectable]="canManage()"
            [bulkActions]="bulkActionDefs"
            [clickable]="true"
            (rowClick)="openDetailRow($event)"
            (selectionChange)="onSelection($event)"
            (bulkAction)="onBulk($event)">
            <button gridActions type="button" class="ds-btn ds-btn-primary" (click)="openCreate()">
              {{ t('talenthub.capture.create') }}
            </button>
            <ng-template gridCell="title" let-row let-value="value">
              <button type="button" class="grid-title" (click)="openDetail(row.rfp); $event.stopPropagation()">
                @if (value) { {{ value }} } @else { {{ t('talenthub.capture.untitled') }} }
              </button>
            </ng-template>
            <ng-template gridCell="analysis" let-value="value">
              @if (value && value !== 'configured') {
                <span class="ds-badge ds-badge-warning">{{ analysisBadge(value) }}</span>
              }
            </ng-template>
          </app-data-grid>
        }
        @if (hasPendingDelete()) {
          <div class="ds-modal-backdrop" (click)="closeConfirmDelete()">
            <div class="ds-modal" (click)="$event.stopPropagation()">
              <div class="ds-modal-header">
                <span class="ds-modal-title">{{ t('common.delete') }}</span>
                <button type="button" class="ds-modal-close" [attr.aria-label]="t('common.close')" (click)="closeConfirmDelete()">×</button>
              </div>
              <div class="ds-modal-body">
                <p>{{ t('talenthub.capture.confirmDelete', { count: pendingDelete().length }) }}</p>
                @if (error(); as err) {
                  <div class="ds-alert ds-alert-danger">{{ err }}</div>
                }
              </div>
              <div class="ds-modal-footer">
                <button type="button" class="ds-btn ds-btn-ghost" (click)="closeConfirmDelete()">
                  {{ t('talenthub.capture.cancel') }}
                </button>
                <button type="button" class="ds-btn ds-btn-danger" [disabled]="deleting()" (click)="confirmBulkDelete()">
                  {{ deleting() ? t('talenthub.capture.deleting') : t('common.delete') }}
                </button>
              </div>
            </div>
          </div>
        }
      }
    </div>
  `,
  styles: [
    `
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
      .hidden {
        display: none;
      }
      .grid-title {
        color: var(--portal-text-primary);
        font-weight: var(--portal-font-weight-medium);
        text-align: left;
        background: none;
        border: none;
        padding: 0;
        cursor: pointer;
        transition: color var(--portal-transition-fast);
      }
      .grid-title:hover {
        color: var(--portal-text-link);
      }
      .detail-title-text {
        color: var(--portal-text-primary);
        font-weight: var(--portal-font-weight-medium);
      }
      .field-split {
        display: grid;
        grid-template-columns: 1fr 1fr 1fr;
        gap: 12px;
      }
      .field-split .ds-form-group {
        margin: 0;
      }
      .attach-row {
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 2px 0;
      }
      .attach-name {
        color: var(--portal-text-primary);
        font-size: var(--portal-font-size-sm);
      }
      .attach-remove {
        border: none;
        background: none;
        color: var(--portal-text-muted);
        cursor: pointer;
        font-size: var(--portal-font-size-lg);
        line-height: 1;
      }
      .attach-remove:hover {
        color: var(--portal-status-danger);
      }
      .detail-meta {
        display: flex;
        align-items: center;
        gap: 10px;
        margin-bottom: 12px;
        flex-wrap: wrap;
      }
      .row-headers {
        display: grid;
        grid-template-columns: 1fr 88px;
        gap: 8px;
        margin: 8px 0 2px;
      }
      .row-edit {
        display: grid;
        grid-template-columns: 1fr 88px;
        gap: 8px;
        margin-bottom: 6px;
      }
    `,
  ],
})
export class CaptureComponent implements OnInit, OnDestroy {
  private readonly client = inject(ModuleClient);
  private readonly signalReady = mfeReadyDispatcher();
  private readonly kit = inject(UI_I18N);
  private readonly mfeI18n = inject(MfeI18n);

  /** Escape closes the topmost modal (document-level; cleaned up on destroy). */
  private readonly onEscape = (event: KeyboardEvent): void => {
    if (event.key !== 'Escape') return;
    if (this.mode() === 'create') this.closeCreate();
    else if (this.mode() === 'detail') this.closeDetail();
  };

  /** Template convenience; kit labels are rendered for every shared string. */
  protected t(key: string, params?: Record<string, string | number>): string {
    return this.kit.t(key, params);
  }

  readonly mode = signal<'list' | 'create' | 'detail'>('list');
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);

  readonly drafts = signal<DraftItem[]>([]);
  readonly clients = signal<{ id: string; name: string }[]>([]);

  readonly clientChoice = signal<'pick' | 'new'>('new');
  readonly clientRef = signal('');
  readonly clientName = signal('');
  readonly title = signal('');
  readonly kind = signal<'rfp' | 'rfq'>('rfp');
  readonly serviceType = signal<'tm' | 'direct'>('tm');
  readonly description = signal('');
  readonly deadline = signal('');
  readonly attachments = signal<DocumentMeta[]>([]);
  readonly attaching = signal(0);
  readonly saving = signal(false);

  readonly selected = signal<RfpDetail | null>(null);
  readonly editedTitle = signal('');
  readonly rows = signal<PositionRow[]>([]);
  readonly approving = signal(false);

  /** Ids selected in the drafts grid (via the grid's trackBy). */
  readonly selection = signal<string[]>([]);
  /** Ids awaiting delete confirmation. */
  readonly pendingDelete = signal<string[]>([]);
  readonly deleting = signal(false);

  readonly bulkActionDefs: GridBulkAction[] = [
    { id: 'delete', label: 'Delete', labelKey: 'common.delete', danger: true },
  ];

  private readonly grid = viewChild(DataGrid);

  readonly draftColumns: GridColumn<DraftItem>[] = [
    {
      key: 'title',
      header: 'Title',
      headerKey: 'talenthub.capture.col.title',
      accessor: (d) => d.rfp.title,
    },
    {
      key: 'client',
      header: 'Client',
      headerKey: 'talenthub.capture.col.client',
      accessor: (d) => d.clientName ?? '',
    },
    {
      key: 'kind',
      header: 'Kind',
      headerKey: 'talenthub.capture.col.kind',
      accessor: (d) => d.rfp.kind,
      format: (v) => this.t('talenthub.capture.kind.' + String(v)),
    },
    {
      key: 'serviceType',
      header: 'Service',
      headerKey: 'talenthub.capture.col.service',
      accessor: (d) => d.rfp.serviceType,
      format: (v) => this.t('talenthub.capture.service.' + String(v)),
    },
    {
      key: 'origin',
      header: 'Origin',
      headerKey: 'talenthub.capture.col.origin',
      accessor: (d) => d.rfp.source,
      format: (v) => this.t('talenthub.capture.origin.' + String(v)),
      width: '110px',
    },
    {
      key: 'analysis',
      header: 'Analysis',
      headerKey: 'talenthub.capture.col.analysis',
      accessor: (d) => analysisStateOf(d.rfp),
      sortable: false,
      searchable: false,
      width: '120px',
    },
    {
      key: 'deadline',
      header: 'Deadline',
      headerKey: 'talenthub.capture.col.deadline',
      accessor: (d) => d.rfp.deadline ?? '',
      format: (v) => (v ? this.kit.formatDate(String(v), { dateStyle: 'medium' }) : ''),
      width: '130px',
    },
    {
      key: 'created',
      header: 'Created',
      headerKey: 'talenthub.capture.col.created',
      accessor: (d) => d.rfp.createdAt,
      format: (v) => this.kit.formatDate(String(v), { dateStyle: 'medium', timeStyle: 'short' }),
      width: '170px',
    },
  ];

  private readonly filePicker = viewChild.required<HTMLInputElement>('files');

  /**
   * Identity/language arrive via the portal document (same-origin session):
   * Angular Elements exposes no component methods, so the old mount() contract
   * never fired — the element loads its own /api/config context instead.
   */
  private readonly portalUser = signal<PortalUserContext | null>(null);

  readonly canManage = computed(() => canManage(this.portalUser()));

  readonly trackDraft = (d: DraftItem, _index: number): string => d.rfp.id;

  // ── Selection + bulk delete ──────────────────────────────────────────

  protected onSelection(ids: string[]): void {
    this.selection.set(ids);
  }

  protected onBulk(event: { id: string; ids: string[] }): void {
    if (event.id !== 'delete' || event.ids.length === 0 || !this.canManage()) {
      return;
    }
    this.error.set(null);
    this.pendingDelete.set(event.ids);
  }

  protected hasPendingDelete(): boolean {
    return this.pendingDelete().length > 0;
  }

  protected closeConfirmDelete(): void {
    this.pendingDelete.set([]);
    this.error.set(null);
  }

  protected async confirmBulkDelete(): Promise<void> {
    const ids = this.pendingDelete();
    if (ids.length === 0 || !this.canManage()) {
      return;
    }
    this.deleting.set(true);
    try {
      for (const id of ids) {
        await this.client.delete('/api/rfps/' + id);
      }
      this.pendingDelete.set([]);
      this.selection.set([]);
      this.grid()?.clearSelection();
      void this.refresh();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    } finally {
      this.deleting.set(false);
    }
  }

  protected openDetailRow(item: DraftItem): void {
    void this.openDetail(item.rfp);
  }

  ngOnInit(): void {
    document.addEventListener('keydown', this.onEscape);
    void this.refresh();
    void this.loadPortalContext();
  }

  ngOnDestroy(): void {
    document.removeEventListener('keydown', this.onEscape);
  }

  // ── List / refresh ─────────────────────────────────────────────────────

  /** Reads the portal session context (user roles + language) from /api/config. */
  private async loadPortalContext(): Promise<void> {
    try {
      const res = await fetch('/api/config');
      if (res.ok) {
        const config = (await res.json()) as { user?: PortalUserContext | null; preferences?: { language?: string } };
        if (config.user) {
          this.portalUser.set(config.user);
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
      const draftItems = await this.client.get<{ rfps: DraftItem[] }>('/api/rfps/drafts');
      this.drafts.set(draftItems.rfps ?? []);
      this.error.set(null);
      this.loading.set(false);
      this.signalReady();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
      this.loading.set(false);
      this.signalReady();
    }
  }

  // ── Create modal ───────────────────────────────────────────────────────

  protected async openCreate(): Promise<void> {
    this.error.set(null);
    this.attachments.set([]);
    this.mode.set('create');
    try {
      const list = await this.client.get<{ clients: { id: string; name: string }[] }>('/api/clients');
      this.clients.set(list.clients ?? []);
      if (this.clients().length > 0) {
        this.clientChoice.set('pick');
        this.clientRef.set(this.clients()[0]!.id);
      } else {
        this.clientChoice.set('new');
        this.clientRef.set('');
      }
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }

  protected closeCreate(): void {
    this.mode.set('list');
    this.error.set(null);
  }

  protected onClientChoice(event: Event): void {
    this.clientChoice.set((event.target as HTMLSelectElement).value as 'pick' | 'new');
  }

  protected onClientRef(event: Event): void {
    this.clientRef.set((event.target as HTMLSelectElement).value);
  }

  protected onClientName(event: Event): void {
    this.clientName.set((event.target as HTMLInputElement).value);
  }

  protected onTitle(event: Event): void {
    this.title.set((event.target as HTMLInputElement).value);
  }

  protected onKind(event: Event): void {
    this.kind.set((event.target as HTMLSelectElement).value as 'rfp' | 'rfq');
  }

  protected onServiceType(event: Event): void {
    this.serviceType.set((event.target as HTMLSelectElement).value as 'tm' | 'direct');
  }

  protected onDescription(event: Event): void {
    this.description.set((event.target as HTMLTextAreaElement).value);
  }

  protected onDeadline(event: Event): void {
    this.deadline.set((event.target as HTMLInputElement).value);
  }

  protected pickFiles(): void {
    this.filePicker().click();
  }

  protected async onFiles(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    for (const file of files) {
      this.attaching.update((n) => n + 1);
      try {
        const stored = await this.client.upload<{
          ref: string;
          name: string;
          mimeType: string;
          size: number;
        }>('/api/documents', file);
        this.attachments.update((list) =>
          list.concat({
            ref: stored.ref,
            name: stored.name,
            mimeType: stored.mimeType,
            size: stored.size,
          }),
        );
      } catch (err) {
        this.error.set(
          this.t('talenthub.capture.attachments.failed', { name: file.name }) +
            ' — ' +
            (err instanceof Error ? err.message : String(err)),
        );
      } finally {
        this.attaching.update((n) => n - 1);
      }
    }
  }

  protected removeAttachment(ref: string): void {
    this.attachments.update((list) => list.filter((a) => a.ref !== ref));
  }

  protected async submitCreate(): Promise<void> {
    this.error.set(null);
    const description = this.description().trim();
    if (!description) {
      this.error.set(this.t('talenthub.capture.error.description'));
      return;
    }
    const payload = this.buildPayload(description);
    if (payload.clientRef == null && payload.newClient == null) {
      this.error.set(this.t('talenthub.capture.error.client'));
      return;
    }
    this.saving.set(true);
    try {
      await this.client.post('/api/rfps/capture', payload);
      this.closeCreate();
      void this.refresh();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    } finally {
      this.saving.set(false);
    }
  }

  private buildPayload(description: string): CreatePayload {
    const base = {
      kind: this.kind(),
      serviceType: this.serviceType(),
      title: this.title().trim() || null,
      deadline: this.deadlineIso(),
      description,
      documentRefs: this.attachments().map((a) => a.ref),
    };
    if (this.clientChoice() === 'pick' && this.clientRef()) {
      return { ...base, clientRef: this.clientRef(), newClient: null };
    }
    const name = this.clientName().trim();
    return { ...base, clientRef: null, newClient: name ? { name } : null };
  }

  /** Date-only input → end-of-day UTC instant (server validates ISO-8601 instants). */
  private deadlineIso(): string | null {
    const raw = this.deadline();
    return raw ? new Date(raw + 'T23:59:59.999Z').toISOString() : null;
  }

  // ── Detail modal ───────────────────────────────────────────────────────

  protected async openDetail(rfp: Rfp): Promise<void> {
    this.error.set(null);
    this.selected.set(null);
    this.rows.set(positionsOf(rfp));
    this.editedTitle.set('');
    this.mode.set('detail');
    try {
      const detail = await this.client.get<RfpDetail>('/api/rfps/' + rfp.id);
      this.selected.set(detail);
      this.rows.set(
        detail.positions.length > 0
          ? detail.positions.map((p) => ({ title: p.title, headcount: p.headcount }))
          : positionsOf(detail.rfp),
      );
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
      this.closeDetail();
    }
  }

  protected closeDetail(): void {
    this.mode.set('list');
    this.error.set(null);
  }

  protected onEditTitle(event: Event): void {
    this.editedTitle.set((event.target as HTMLInputElement).value);
  }

  protected onRowTitle(event: Event, index: number): void {
    const value = (event.target as HTMLInputElement).value;
    this.rows.update((rows) => rows.map((row, i) => (i === index ? { ...row, title: value } : row)));
  }

  protected onRowCount(event: Event, index: number): void {
    const value = Number((event.target as HTMLInputElement).value);
    if (!Number.isFinite(value) || value < 1) {
      return;
    }
    this.rows.update((rows) =>
      rows.map((row, i) => (i === index ? { ...row, headcount: Math.trunc(value) } : row)),
    );
  }

  protected async approve(): Promise<void> {
    const detail = this.selected();
    if (!detail) {
      return;
    }
    this.approving.set(true);
    try {
      await this.client.post('/api/rfps/' + detail.rfp.id + '/approve', {
        title: this.editedTitle().trim() || detail.rfp.title || null,
        positions: this.rows(),
      });
      this.closeDetail();
      void this.refresh();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    } finally {
      this.approving.set(false);
    }
  }

  // ── Display helpers ────────────────────────────────────────────────────

  protected analysisState(rfp: Rfp): string {
    return analysisStateOf(rfp);
  }

  protected analysisBadge(value: unknown): string {
    return this.t('talenthub.capture.analysis.' + String(value));
  }

  protected documentsOf(detail: RfpDetail): DocumentMeta[] {
    if (detail.documents.length > 0) return detail.documents;
    return detail.rfp.documentRefs.map((ref) => ({ ref, name: ref, mimeType: null, size: null }));
  }

  protected tDate(value: string): string {
    return this.kit.formatDate(value, { dateStyle: 'medium' });
  }

  protected formatSize(bytes: number | null): string {
    if (bytes == null) return '';
    if (bytes < 1024) return String(bytes) + ' B';
    const kb = bytes / 1024;
    if (kb < 1024) return this.kit.formatNumber(Math.round(kb * 10) / 10) + ' KB';
    return this.kit.formatNumber(Math.round((kb / 1024) * 10) / 10) + ' MB';
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

/** `analysis.status` when present, else "configured" (the happy default). */
function analysisStateOf(rfp: Rfp): string {
  const status = (rfp.analysis ?? {})['status'];
  return typeof status === 'string' ? status : 'configured';
}
