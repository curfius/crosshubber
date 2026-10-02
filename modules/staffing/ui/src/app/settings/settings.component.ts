import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ViewEncapsulation } from '@angular/core';
import { ModuleClient } from '../shared/module-client';
import { SHARED_STYLES } from '../shared/styles';
import { mfeReadyDispatcher } from '../shared/ready';

interface SettingsView {
  tools: Record<string, boolean>;
  match: { topN: number };
}

const TOOL_LABELS: Record<string, string> = {
  list_rfps: 'List RFPs',
  get_rfp: 'Get RFP',
  search_cvs: 'Search CVs',
  match_candidates: 'Match candidates',
  create_match_run: 'Create match run',
};

/**
 * Staffing settings surface (module-owned, plan §Module 2): tool toggles and
 * matching params. CV library container + Graph credentials and the extraction
 * model arrive with the OneDrive adapter slice (Azure pending).
 */
@Component({
  selector: 'stf-settings',
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
          <h2>Staffing — Settings</h2>
          @if (saved()) {
            <span class="badge ok">Saved</span>
          }
        </div>

        <section class="card">
          <h3>Agent tools</h3>
          @for (tool of toolNames; track tool) {
            <label class="row">
              <input
                type="checkbox"
                [checked]="view.tools[tool]"
                (change)="setTool(tool, $any($event.target).checked)"
              />
              {{ toolLabels[tool] }}
              <span class="mono muted">{{ tool }}</span>
            </label>
          }
        </section>

        <section class="card">
          <h3>Matching</h3>
          <label class="field">
            <span>Default shortlist size (topN)</span>
            <input
              type="number"
              min="1"
              max="50"
              [value]="view.match.topN"
              (input)="setTopN($any($event.target).value)"
            />
          </label>
          <p class="muted hint">
            CV library container, Graph credentials and the LLM extraction model arrive
            with the OneDrive adapter slice (Azure app registration pending).
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
      input[type='number'] {
        padding: 6px 8px;
        font-size: 13px;
        border-radius: 8px;
        border: 1px solid var(--portal-modal-border, #d1d5db);
        background: var(--portal-modal-bg, #fff);
        color: inherit;
      }
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

  setTool(tool: string, enabled: boolean): void {
    this.updateDraft({ ...this.draft()!, tools: { ...this.draft()!.tools, [tool]: enabled } });
  }

  setTopN(raw: string): void {
    const value = Number(raw);
    if (!Number.isFinite(value)) return;
    this.updateDraft({
      ...this.draft()!,
      match: { ...this.draft()!.match, topN: value },
    });
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
      const saved = await this.client.put<SettingsView>('/api/settings', {
        tools: view.tools,
        match: view.match,
      });
      this.draft.set(saved);
      this.saved.set(true);
    } catch (err) {
      this.saveError.set(err instanceof Error ? err.message : String(err));
    } finally {
      this.saving.set(false);
    }
  }
}
