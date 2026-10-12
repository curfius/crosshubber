import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  ViewEncapsulation,
  inject,
  signal,
} from '@angular/core';

import { ModuleClient } from '../shared/module-client';
import { SHARED_STYLES } from '../shared/styles';
import { mfeReadyDispatcher } from '../shared/ready';

type Row = { label: string; count: number };
type Summary = {
  period: string;
  rfpCountsByPhase: Record<string, number>;
  outcomeCounts: Record<string, number>;
  topClients: { clientId: string; rfpCount: number }[];
  timeInPhaseHours: Record<string, number>;
  funnel: Record<string, number>;
  placements: number;
  placementsBySource: Record<string, number>;
  verdictOutcomes: Record<string, Record<string, number>>;
};

/** RFP analytics (P3.4): light cards + CSS bars only (no chart lib — bundle budget). */
@Component({
  selector: 'th-analytics',
  encapsulation: ViewEncapsulation.ShadowDom,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      <div class="head">
        <h2>RFP Analytics</h2>
        <select [value]="period()" (change)="onPeriod($event)">
          <option value="all">all time</option>
          <option value="90d">last 90 days</option>
          <option value="30d">last 30 days</option>
        </select>
      </div>

      @if (error(); as err) {
        <div class="card error">{{ err }}</div>
      } @else if (loading()) {
        <div class="muted">Loading…</div>
      } @else if (summary(); as s) {
        <div class="cards">
          <div class="card half">
            <h3>RFPs by phase</h3>
            @for (row of bars(s.rfpCountsByPhase); track row.label) {
              <div class="barrow">
                <span class="label">{{ row.label }}</span>
                <span class="bar" [style.width.%]="row.pct"></span>
                <span class="count">{{ row.count }}</span>
              </div>
            }
            @if (empty(s.rfpCountsByPhase)) {
              <div class="muted">No RFPs yet.</div>
            }
          </div>
          <div class="card half">
            <h3>Outcomes</h3>
            <div class="kv"><span>active</span><strong>{{ s.outcomeCounts['active'] ?? 0 }}</strong></div>
            <div class="kv"><span>closed</span><strong>{{ s.outcomeCounts['closed'] ?? 0 }}</strong></div>
            <div class="kv"><span>rejected</span><strong>{{ s.outcomeCounts['rejected'] ?? 0 }}</strong></div>
            <div class="kv"><span>archived</span><strong>{{ s.outcomeCounts['archived'] ?? 0 }}</strong></div>
          </div>
          <div class="card half">
            <h3>Process funnel</h3>
            @for (row of bars(s.funnel); track row.label) {
              <div class="barrow">
                <span class="label">{{ row.label }}</span>
                <span class="bar" [style.width.%]="row.pct"></span>
                <span class="count">{{ row.count }}</span>
              </div>
            }
            @if (empty(s.funnel)) {
              <div class="muted">No processes yet.</div>
            }
          </div>
          <div class="card half">
            <h3>Placements</h3>
            <div class="kv"><span>total onboarded</span><strong>{{ s.placements }}</strong></div>
            @for (row of bars(s.placementsBySource); track row.label) {
              <div class="barrow">
                <span class="label">{{ row.label }}</span>
                <span class="bar" [style.width.%]="row.pct"></span>
                <span class="count">{{ row.count }}</span>
              </div>
            }
          </div>
          <div class="card half">
            <h3>Time in phase (avg hours)</h3>
            @for (row of bars(s.timeInPhaseHours); track row.label) {
              <div class="barrow">
                <span class="label">{{ row.label }}</span>
                <span class="count">{{ row.count }}</span>
              </div>
            }
            @if (empty(s.timeInPhaseHours)) {
              <div class="muted">No phase history yet.</div>
            }
          </div>
          <div class="card half">
            <h3>Assessment verdicts → outcomes</h3>
            @for (entry of verdictRows(s.verdictOutcomes); track entry.verdict) {
              <div class="kv">
                <span>{{ entry.verdict }}</span>
                <span class="muted">
                  {{ entry.outcomes }}
                </span>
              </div>
            }
            @if (verdictRows(s.verdictOutcomes).length === 0) {
              <div class="muted">No assessed outcomes yet.</div>
            }
          </div>
          <div class="card wide">
            <h3>Top clients</h3>
            @for (client of s.topClients; track client.clientId) {
              <div class="kv">
                <span class="muted">{{ client.clientId.slice(0, 8) }}…</span>
                <strong>{{ client.rfpCount }} RFP(s)</strong>
              </div>
            }
            @if (s.topClients.length === 0) {
              <div class="muted">No clients yet.</div>
            }
          </div>
        </div>
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
      select {
        border: 1px solid var(--portal-badge-border, #d1d5db);
        border-radius: 8px;
        padding: 5px 10px;
        font: inherit;
        color: inherit;
        background: transparent;
      }
      .cards {
        display: flex;
        flex-wrap: wrap;
        gap: 12px;
      }
      .half {
        width: calc(50% - 6px);
        min-width: 280px;
      }
      .wide {
        width: 100%;
      }
      .barrow,
      .kv {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 6px;
        font-size: 13px;
      }
      .barrow .label {
        width: 120px;
        color: var(--portal-text-secondary, #6b7280);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .bar {
        height: 10px;
        border-radius: 5px;
        background: var(--portal-accent-primary, #2563eb);
        min-width: 2px;
      }
      .kv {
        justify-content: space-between;
      }
      .error {
        color: var(--portal-status-danger, #dc2626);
        border-color: var(--portal-status-danger, #dc2626);
      }
    `,
  ],
})
export class AnalyticsComponent implements OnInit {
  private readonly client = inject(ModuleClient);
  private readonly signalReady = mfeReadyDispatcher();

  readonly period = signal('all');
  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly summary = signal<Summary | null>(null);

  ngOnInit(): void {
    void this.refresh();
  }

  mount(context: {
    user: { sub: string; name?: string | null; roles: string[] } | null;
    language: string;
  }): void {
    // Analytics visibility is server-gated (manager+admin); UI shows the error otherwise.
  }

  unmount(): void {}

  private async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const summary = await this.client.get<Summary>('/api/analytics/summary?period=' + this.period());
      this.summary.set(summary);
      this.loading.set(false);
      this.signalReady();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
      this.loading.set(false);
      this.signalReady();
    }
  }

  onPeriod(event: Event): void {
    this.period.set((event.target as HTMLSelectElement).value);
    void this.refresh();
  }

  bars(map: Record<string, number> | undefined): { label: string; count: number; pct: number }[] {
    const entries = Object.entries(map ?? {});
    const max = Math.max(1, ...entries.map(([, count]) => count));
    return entries.map(([label, count]) => ({ label, count, pct: (count / max) * 100 }));
  }

  empty(map: Record<string, number> | undefined): boolean {
    return Object.keys(map ?? {}).length === 0;
  }

  verdictRows(verdicts: Summary['verdictOutcomes']): { verdict: string; outcomes: string }[] {
    return Object.entries(verdicts ?? {}).map(([verdict, outcomes]) => ({
      verdict,
      outcomes: Object.entries(outcomes ?? {})
        .map(([outcome, count]) => `${outcome}=${count}`)
        .join(', '),
    }));
  }
}
