import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ViewEncapsulation } from '@angular/core';
import { ModuleClient } from '../shared/module-client';
import { SHARED_STYLES } from '../shared/styles';
import { mfeReadyDispatcher } from '../shared/ready';

interface AgentView {
  enabled: boolean;
  systemPrompt: string;
  providerBaseUrl: string;
  hasApiKey: boolean;
  providerModel: string;
}

interface SettingsView {
  agent: AgentView;
  tools: Record<string, boolean>;
  rag: { maxDocsPerQuery: number; truncationBudget: number };
}

const TOOL_LABELS: Record<string, string> = {
  list_projects: 'List projects',
  get_project: 'Get project',
  get_stage_history: 'Stage history',
  update_project_stage: 'Update project stage',
  search_project_docs: 'Search project docs',
};

/**
 * Solutions settings surface (module-owned, plan §Module 1 "Settings page"):
 * agent config (enabled, system prompt, LLM provider credentials + model —
 * encrypted module-side), tool toggles, document-source selection, RAG limits.
 * Served by the Solutions module backend; both routes require solutions-admin.
 */
@Component({
  selector: 'sol-settings',
  encapsulation: ViewEncapsulation.ShadowDom,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      @if (error(); as err) {
        <div class="card error">{{ err }}</div>
      } @else if (loading()) {
        <div class="muted">Loading settings…</div>
      } @else if (draft(); as view) {
        <div class="head">
          <h2>Solutions — Settings</h2>
          @if (saved()) {
            <span class="badge ok">Saved</span>
          }
        </div>

        <section class="card">
          <h3>Projects Agent</h3>
          <label class="row">
            <input type="checkbox" [checked]="view.agent.enabled" (change)="setAgentEnabled($any($event.target).checked)" />
            Agent enabled
          </label>
          <label class="field">
            <span>System prompt</span>
            <textarea
              rows="3"
              [value]="view.agent.systemPrompt"
              (input)="setAgent('systemPrompt', $any($event.target).value)"></textarea>
          </label>
          <label class="field">
            <span>Provider base URL</span>
            <input
              type="text"
              [value]="view.agent.providerBaseUrl"
              (input)="setAgent('providerBaseUrl', $any($event.target).value)" />
          </label>
          <label class="field">
            <span>API key @if (view.agent.hasApiKey) { (configured — type to replace) }</span>
            <input
              type="password"
              autocomplete="off"
              [value]="apiKeyDraft()"
              (input)="apiKeyDraft.set($any($event.target).value)" />
          </label>
          <label class="field">
            <span>Model</span>
            <input
              type="text"
              [value]="view.agent.providerModel"
              (input)="setAgent('providerModel', $any($event.target).value)" />
          </label>
        </section>

        <section class="card">
          <h3>Agent tools</h3>
          @for (tool of toolNames; track tool) {
            <label class="row">
              <input
                type="checkbox"
                [checked]="view.tools[tool]"
                (change)="setTool(tool, $any($event.target).checked)" />
              {{ toolLabels[tool] }}
              <span class="mono muted">{{ tool }}</span>
            </label>
          }
        </section>

        <section class="card">
          <h3>RAG limits</h3>
          <div class="grid2">
            <label class="field">
              <span>Max docs per query</span>
              <input
                type="number"
                min="1"
                max="20"
                [value]="view.rag.maxDocsPerQuery"
                (input)="setRag('maxDocsPerQuery', $any($event.target).value)" />
            </label>
            <label class="field">
              <span>Snippet truncation budget</span>
              <input
                type="number"
                min="200"
                max="20000"
                [value]="view.rag.truncationBudget"
                (input)="setRag('truncationBudget', $any($event.target).value)" />
            </label>
          </div>
          <p class="muted hint">
            Document source selection and credentials arrive with the OneDrive adapter
            (Azure app registration pending).
          </p>
        </section>

        @if (saveError(); as se) {
          <div class="card error">{{ se }}</div>
        }
        <div class="actions">
          <button class="btn accent" (click)="save()" [disabled]="saving()">
            @if (saving()) {
              Saving…
            } @else {
              Save settings
            }
          </button>
        </div>
      }
    </div>
  `,
  styles: [
    SHARED_STYLES +
      /* css */ `
      .wrap { padding: 16px; height: 100%; overflow: auto; display: flex; flex-direction: column; gap: 12px; max-width: 720px; }
      .head { display: flex; align-items: center; gap: 10px; }
      .field, .row { display: flex; flex-direction: column; gap: 4px; margin-top: 10px; font-size: 13px; }
      .row { flex-direction: row; align-items: center; gap: 8px; }
      .row .mono { margin-left: auto; }
      .field > span { font-weight: 500; }
      input[type='text'], input[type='password'], input[type='number'], textarea {
        padding: 6px 8px;
        font-size: 13px;
        border-radius: 8px;
        border: 1px solid var(--portal-modal-border, #d1d5db);
        background: var(--portal-modal-bg, #fff);
        color: inherit;
        font-family: inherit;
      }
      .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
      .hint { margin: 10px 0 0; font-size: 12px; }
      .actions { display: flex; justify-content: flex-end; }
      .btn {
        border-radius: 8px;
        border: none;
        padding: 8px 14px;
        font-size: 13px;
        cursor: pointer;
      }
      .btn.accent { background: var(--portal-accent-primary, #2563eb); color: #fff; }
      .btn:disabled { opacity: 0.6; cursor: default; }
      .mono { font-family: ui-monospace, monospace; font-size: 11px; }
    `,
  ],
})
export class SettingsComponent implements OnInit {
  private readonly client = inject(ModuleClient);
  private readonly signalMfeReady = mfeReadyDispatcher();

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly saving = signal(false);
  readonly saved = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly draft = signal<SettingsView | null>(null);

  readonly toolNames = Object.keys(TOOL_LABELS);
  readonly toolLabels = TOOL_LABELS;
  /** Typed-but-unsaved API key; empty = keep the stored key (server null = keep). */
  readonly apiKeyDraft = signal('');

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      const view = await this.client.get<SettingsView>('/api/settings');
      this.draft.set(view);
      this.loading.set(false);
      this.signalMfeReady();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
      this.loading.set(false);
      this.signalMfeReady();
    }
  }

  setAgentEnabled(enabled: boolean): void {
    this.updateAgent({ enabled });
  }

  setAgent(
    field: 'systemPrompt' | 'providerBaseUrl' | 'providerModel',
    value: string,
  ): void {
    this.updateAgent({ [field]: value });
  }

  setTool(tool: string, enabled: boolean): void {
    this.updateDraft({ ...this.draft()!, tools: { ...this.draft()!.tools, [tool]: enabled } });
  }

  setRag(field: 'maxDocsPerQuery' | 'truncationBudget', raw: string): void {
    const value = Number(raw);
    if (!Number.isFinite(value)) return;
    this.updateDraft({ ...this.draft()!, rag: { ...this.draft()!.rag, [field]: value } });
  }

  private updateAgent(patch: Partial<AgentView>): void {
    this.updateDraft({ ...this.draft()!, agent: { ...this.draft()!.agent, ...patch } });
  }

  private updateDraft(next: SettingsView): void {
    this.draft.set(next);
    this.saved.set(false);
  }

  async save(): Promise<void> {
    const view = this.draft();
    if (!view) return;
    this.saving.set(true);
    this.saveError.set(null);
    try {
      const key = this.apiKeyDraft().trim();
      const saved = await this.client.put<SettingsView>('/api/settings', {
        agent: {
          enabled: view.agent.enabled,
          systemPrompt: view.agent.systemPrompt,
          providerBaseUrl: view.agent.providerBaseUrl,
          providerApiKey: key.length > 0 ? key : null,
          providerModel: view.agent.providerModel,
        },
        tools: view.tools,
        rag: view.rag,
      });
      this.draft.set(saved);
      this.apiKeyDraft.set('');
      this.saved.set(true);
    } catch (err) {
      this.saveError.set(err instanceof Error ? err.message : String(err));
    } finally {
      this.saving.set(false);
    }
  }
}
