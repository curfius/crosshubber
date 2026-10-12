import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  ViewEncapsulation,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ModuleClient, hasRole } from '../shared/module-client';
import { SHARED_STYLES } from '../shared/styles';
import { mfeReadyDispatcher } from '../shared/ready';

type LlmView = { baseUrl: string; hasApiKey: boolean; model: string };
type MatchingView = {
  engine: string;
  jevBaseUrl: string;
  hasApiKey: boolean;
  jevTimeoutMs: number;
};
type SettingsView = {
  llm: LlmView;
  matching: MatchingView;
  tools: Record<string, boolean>;
  connectors: Record<string, Record<string, unknown> | null>;
};
type CategoryRow = {
  category: string;
  kinds: string[];
  activeKind: string | null;
  masked: { key: string; value: string }[];
};
type OutboxRow = {
  id: string;
  kind: string;
  subject: string;
  body: Record<string, unknown>;
  at: string;
};

type ConnectorRow = CategoryRow & { draft: string };

/** Module settings (P3.5/P4.3): tools + llm + matching cards, connectors card, outbox view. */
@Component({
  selector: 'th-settings',
  encapsulation: ViewEncapsulation.ShadowDom,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      <div class="head">
        <h2>Talent Hub Settings</h2>
        <select [value]="section()" (change)="onSection($event)">
          <option value="general">general</option>
          <option value="connectors">connectors</option>
          <option value="outbox">outbox</option>
        </select>
      </div>

      @if (error(); as err) {
        <div class="card error">{{ err }}</div>
      } @else if (section() === 'outbox') {
        @if (!isAdmin()) {
          <div class="card muted">staffing-admin role required.</div>
        } @else if (outbox().length === 0) {
          <div class="muted">Outbox is empty.</div>
        }
        @for (row of outbox(); track row.id) {
          <div class="card">
            <div class="row">
              <span class="badge">{{ row.kind }}</span>
              <strong>{{ row.subject }}</strong>
              <span class="muted">{{ shortAt(row.at) }}</span>
            </div>
            <div class="muted small">{{ stringify(row.body) }}</div>
          </div>
        }
      } @else {
        @if (!isAdmin()) {
          <div class="card muted">staffing-admin role required.</div>
        }
        @if (view(); as s) {
          @if (section() === 'general') {
            <div class="card">
              <h3>Tools</h3>
              @for (entry of toolRows(); track entry.name) {
                <label class="row">
                  <input
                    type="checkbox"
                    [checked]="entry.enabled"
                    [disabled]="!isAdmin()"
                    (change)="onToggle(entry.name, $event)"
                  />
                  <span>{{ entry.name }}</span>
                </label>
              }
            </div>
            <div class="card">
              <h3>LLM (capture analysis + llm engine)</h3>
              <div class="field">
                <label>Base URL</label>
                <input [value]="llmBaseUrl()" (input)="onLlmUrl($event)" [disabled]="!isAdmin()" />
              </div>
              <div class="field">
                <label>API key {{ s.llm.hasApiKey ? '(stored — blank keeps it)' : '' }}</label>
                <input type="password" (input)="onLlmKey($event)" [disabled]="!isAdmin()" />
              </div>
              <div class="field">
                <label>Model</label>
                <input [value]="llmModel()" (input)="onLlmModel($event)" [disabled]="!isAdmin()" />
              </div>
            </div>
            <div class="card">
              <h3>Matching</h3>
              <div class="field">
                <label>Engine</label>
                <select [value]="engine()" [disabled]="!isAdmin()" (change)="onEngine($event)">
                  <option value="rules">rules (deterministic)</option>
                  <option value="jev">jev (external API)</option>
                  <option value="llm">llm</option>
                </select>
              </div>
              <div class="field">
                <label>JEV base URL</label>
                <input [value]="jevUrl()" (input)="onJevUrl($event)" [disabled]="!isAdmin()" />
              </div>
              <div class="field">
                <label>JEV API key {{ s.matching.hasApiKey ? '(stored — blank keeps it)' : '' }}</label>
                <input type="password" (input)="onJevKey($event)" [disabled]="!isAdmin()" />
              </div>
              <div class="field">
                <label>JEV timeout (ms)</label>
                <input [value]="jevTimeout()" (input)="onJevTimeout($event)" [disabled]="!isAdmin()" />
              </div>
            </div>
          } @else {
            <div class="card">
              <h3>Connector registry</h3>
              @for (cat of rows(); track cat.category) {
                <div class="field">
                  <label>{{ cat.category }} — active: {{ cat.activeKind ?? '—' }}</label>
                  <div class="row">
                    <select
                      [value]="cat.draft"
                      (change)="onKindDraft(cat.category, $event)"
                    >
                      @for (kind of cat.kinds; track kind) {
                        <option [value]="kind">{{ kind }}</option>
                      }
                    </select>
                    <button (click)="testConnector(cat.category)">test</button>
                  </div>
                  @if (cat.masked.length > 0) {
                    <div class="small">
                      @for (entry of cat.masked; track entry.key) {
                        <span>{{ entry.key }}={{ entry.value }} </span>
                      }
                    </div>
                  }
                </div>
              }
              <div class="row">
                <button class="primary" [disabled]="saving()" (click)="saveConnectors()">
                  Save connectors
                </button>
              </div>
            </div>
          }
          @if (savedNote(); as note) {
            <div class="muted">{{ note }}</div>
          }
          @if (isAdmin()) {
            <button
              class="primary"
              [disabled]="saving()"
              (click)="section() === 'general' ? saveGeneral() : null"
              [hidden]="section() !== 'general'"
            >
              {{ saving() ? 'saving…' : 'Save general settings' }}
            </button>
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
        gap: 12px;
        align-items: center;
        margin-bottom: 12px;
      }
      .head h2 {
        margin: 0;
      }
      .field {
        display: flex;
        flex-direction: column;
        gap: 4px;
        margin-bottom: 10px;
      }
      .field label {
        font-size: 11px;
        text-transform: uppercase;
        letter-spacing: 0.08em;
      }
      .row {
        display: flex;
        gap: 10px;
        align-items: center;
        margin-bottom: 6px;
        font-size: 13px;
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
        cursor: pointer;
      }
      .error {
        color: var(--portal-status-danger, #dc2626);
        border-color: var(--portal-status-danger, #dc2626);
      }
    `,
  ],
})
export class SettingsComponent implements OnInit {
  private readonly client = inject(ModuleClient);
  private readonly signalReady = mfeReadyDispatcher();

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly saving = signal(false);
  readonly savedNote = signal<string | null>(null);
  readonly section = signal<'general' | 'connectors' | 'outbox'>('general');
  readonly view = signal<SettingsView | null>(null);

  readonly toolToggles = signal<Record<string, boolean>>({});
  readonly llmBaseUrl = signal('');
  readonly llmKey = signal('');
  readonly llmModel = signal('');
  readonly engine = signal('rules');
  readonly jevUrl = signal('');
  readonly jevKey = signal('');
  readonly jevTimeout = signal(5000);

  readonly categories = signal<CategoryRow[]>([]);
  readonly kindDrafts = signal<Record<string, string>>({});
  readonly outbox = signal<OutboxRow[]>([]);

  private user: { sub: string; name?: string | null; roles: string[] } | null = null;

  readonly isAdmin = computed(() => hasRole(this.user, 'staffing-admin'));

  readonly toolRows = computed(() =>
    Object.entries(this.toolToggles()).map(([name, enabled]) => ({ name, enabled })),
  );

  readonly rows = computed<ConnectorRow[]>(() => {
    const drafts = this.kindDrafts();
    return this.categories().map((row) => ({
      ...row,
      draft: drafts[row.category] ?? row.activeKind ?? row.kinds[0] ?? 'emulated',
    }));
  });

  ngOnInit(): void {
    void this.refresh();
  }

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
      if (this.section() === 'outbox') {
        const res = await this.client.get<{ outbox: OutboxRow[] }>('/api/settings/outbox');
        this.outbox.set(res.outbox ?? []);
      } else if (this.section() === 'connectors') {
        await this.refreshSettingsView();
        const res = await this.client.get<{
          categories: Record<string, { kinds: string[]; kind: string | null }>;
        }>('/api/settings/connectors');
        const masked = this.view()?.connectors ?? {};
        const rows: CategoryRow[] = Object.entries(res.categories ?? {}).map(
          ([category, info]) => {
            const config = (masked[category] ?? {}) as Record<string, unknown>;
            const rawKind = config['kind'];
            const activeKind = typeof rawKind === 'string' ? rawKind : info.kind ?? null;
            return {
              category,
              kinds: info.kinds ?? [],
              activeKind,
              masked: Object.entries(config)
                .filter(([key]) => key !== 'kind')
                .map(([key, value]) => ({ key, value: String(value) })),
            };
          },
        );
        this.categories.set(rows);
      } else {
        await this.refreshSettingsView();
      }
      this.loading.set(false);
      this.signalReady();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
      this.loading.set(false);
      this.signalReady();
    }
  }

  private async refreshSettingsView(): Promise<void> {
    const view = await this.client.get<SettingsView>('/api/settings');
    this.view.set(view);
    this.toolToggles.set({ ...(view.tools ?? {}) });
    this.llmBaseUrl.set(view.llm?.baseUrl ?? '');
    this.llmModel.set(view.llm?.model ?? '');
    this.engine.set(view.matching?.engine ?? 'rules');
    this.jevUrl.set(view.matching?.jevBaseUrl ?? '');
    this.jevTimeout.set(view.matching?.jevTimeoutMs ?? 5000);
  }

  onSection(event: Event): void {
    this.section.set((event.target as HTMLSelectElement).value as 'general' | 'connectors' | 'outbox');
    this.error.set(null);
    void this.refresh();
  }

  onToggle(name: string, event: Event): void {
    this.toolToggles.update((rows) => ({
      ...rows,
      [name]: (event.target as HTMLInputElement).checked,
    }));
  }

  onLlmUrl(event: Event): void {
    this.llmBaseUrl.set((event.target as HTMLInputElement).value);
  }

  onLlmKey(event: Event): void {
    this.llmKey.set((event.target as HTMLInputElement).value);
  }

  onLlmModel(event: Event): void {
    this.llmModel.set((event.target as HTMLInputElement).value);
  }

  onEngine(event: Event): void {
    this.engine.set((event.target as HTMLSelectElement).value);
  }

  onJevUrl(event: Event): void {
    this.jevUrl.set((event.target as HTMLInputElement).value);
  }

  onJevKey(event: Event): void {
    this.jevKey.set((event.target as HTMLInputElement).value);
  }

  onJevTimeout(event: Event): void {
    const value = Number((event.target as HTMLInputElement).value);
    if (Number.isFinite(value) && value > 0) {
      this.jevTimeout.set(Math.trunc(value));
    }
  }

  onKindDraft(category: string, event: Event): void {
    this.kindDrafts.update((drafts) => ({
      ...drafts,
      [category]: (event.target as HTMLSelectElement).value,
    }));
  }

  private async saveGeneral(): Promise<void> {
    this.saving.set(true);
    this.savedNote.set(null);
    const body: Record<string, Record<string, unknown>> = {
      llm: {},
      matching: {},
      tools: { ...this.toolToggles() },
    };
    body['llm'] = {
      baseUrl: this.llmBaseUrl().trim() || null,
      apiKey: this.llmKey().trim() || null,
      model: this.llmModel().trim() || null,
    };
    body['matching'] = {
      engine: this.engine(),
      jevBaseUrl: this.jevUrl().trim() || null,
      jevApiKey: this.jevKey().trim() || null,
      jevTimeoutMs: this.jevTimeout(),
    };
    // Omitted apiKey keeps the stored one; empty string would clear it.
    if (this.view()?.llm?.hasApiKey === true) {
      delete body['llm']['apiKey'];
    }
    if (this.view()?.matching?.hasApiKey === true) {
      delete body['matching']['jevApiKey'];
    }
    try {
      const updated = await this.client.put<SettingsView>('/api/settings', body);
      this.view.set(updated);
      this.llmKey.set('');
      this.jevKey.set('');
      this.savedNote.set('saved.');
      this.error.set(null);
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    } finally {
      this.saving.set(false);
    }
  }

  private async saveConnectors(): Promise<void> {
    this.saving.set(true);
    this.savedNote.set(null);
    const drafts = this.kindDrafts();
    const connectors: Record<string, { kind: string }> = {};
    for (const row of this.rows()) {
      connectors[row.category] = { kind: row.draft };
    }
    try {
      await this.client.put('/api/settings', { connectors });
      this.kindDrafts.set({});
      this.savedNote.set('connectors saved.');
      this.error.set(null);
      void this.refresh();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    } finally {
      this.saving.set(false);
    }
  }

  private testConnector(category: string): void {
    void this.client
      .post<{ kind: string; ok: boolean; detail: string }>(
        '/api/settings/connectors/' + category + '/test',
        {},
      )
      .then(
        (result) =>
          this.savedNote.set(category + ' [' + result.kind + ']: ' + result.detail),
        (err) => this.error.set(err instanceof Error ? err.message : String(err)),
      );
  }

  stringify(value: Record<string, unknown> | null): string {
    return value === null ? '' : JSON.stringify(value);
  }

  shortAt(at: string): string {
    return new Date(at).toLocaleString();
  }
}
