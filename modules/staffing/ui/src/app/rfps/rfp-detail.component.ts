import { ChangeDetectionStrategy, Component, OnInit, inject, input, output, signal } from '@angular/core';
import { ViewEncapsulation } from '@angular/core';
import {
  Candidate,
  MatchRun,
  ModuleClient,
  Rfp,
  Shortlist,
} from '../shared/module-client';
import { SHARED_STYLES } from '../shared/styles';

/**
 * RFP detail (plan §Module 2): requirements, match runs (with ranked results +
 * rationale), shortlists, CV scan. Match runs are the mutating path — the UI
 * confirm mirrors the portal agent's confirmation discipline.
 */
@Component({
  selector: 'stf-rfp-detail',
  encapsulation: ViewEncapsulation.ShadowDom,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      <button class="linkbtn" (click)="back.emit()">← RFPs</button>
      @if (error(); as err) {
        <div class="card error">{{ err }}</div>
      } @else {
        <div class="head">
          <h2>{{ rfp().title }}</h2>
          <span class="badge stage">{{ rfp().status.toLowerCase() }}</span>
          <span class="badge">{{ rfp().kind }}</span>
        </div>
        <p class="muted">{{ rfp().client }} · deadline {{ dateOf(rfp().deadline) }}</p>

        <section class="card">
          <h3>Requirements</h3>
          <div class="mono req">{{ reqText() }}</div>
        </section>

        <section class="card">
          <h3>Candidates</h3>
          @if (candidates().length === 0) {
            <div class="muted">No candidates yet.</div>
          }
          <ul class="list">
            @for (c of candidates(); track c.id) {
              <li>
                <span class="doc-title">{{ c.name }}</span>
                <span class="muted">{{ c.headline }}</span>
                <span class="mono muted">{{ joinSkills(c) }}</span>
                <span class="badge ok slim-badge">{{ c.status.toLowerCase() }}</span>
              </li>
            }
          </ul>
          <div class="addrow">
            <select #refSel class="select slim">
              @for (ref of knownRefs; track ref) {
                <option [value]="ref">{{ ref }}</option>
              }
            </select>
            <input #nameIn class="input slim grow" placeholder="Candidate title" />
            <button class="btn accent" (click)="scanCv(refSel.value, nameIn.value); nameIn.value = ''">
              Scan CV
            </button>
          </div>
          @if (scanError(); as se) {
            <div class="errorline">{{ se }}</div>
          }
        </section>

        <section class="card">
          <h3>Match runs</h3>
          @if (runs().length === 0) {
            <div class="muted">No matching runs yet.</div>
          }
          <ul class="list">
            @for (run of runs(); track run.id) {
              <li>
                <span class="muted">{{ dateOf(run.createdAt) }}</span>
                <span class="mono muted">top {{ run.results.length }}</span>
                <ol class="results">
                  @for (r of run.results; track r.candidateId) {
                    <li>
                      <span class="doc-title">{{ r.name }}</span>
                      <span class="badge slim-badge">{{ r.score }}</span>
                      <div class="muted note">{{ r.rationale }}</div>
                    </li>
                  }
                </ol>
              </li>
            }
          </ul>
          <div class="addrow">
            <button class="btn accent" (click)="runMatching()" [disabled]="matching()">
              @if (matching()) {
                Running…
              } @else {
                Run matching
              }
            </button>
          </div>
          @if (matchError(); as me) {
            <div class="errorline">{{ me }}</div>
          }
        </section>

        <section class="card">
          <h3>Shortlists</h3>
          @if (shortlists().length === 0) {
            <div class="muted">No shortlists yet.</div>
          }
          <ul class="list">
            @for (sl of shortlists(); track sl.id) {
              <li>
                <span class="muted">{{ dateOf(sl.createdAt) }}</span>
                <span class="mono muted">{{ sl.candidateIds.length }} candidate(s)</span>
                <span class="muted">by {{ sl.createdBy }}</span>
              </li>
            }
          </ul>
          <div class="addrow">
            <button class="btn ghost" (click)="createShortlist()" [disabled]="runs().length === 0">
              Shortlist latest top candidates
            </button>
          </div>
          @if (shortlistError(); as se) {
            <div class="errorline">{{ se }}</div>
          }
        </section>
      }
    </div>
  `,
  styles: [
    SHARED_STYLES +
      /* css */ `
      .wrap { padding: 16px; height: 100%; overflow: auto; display: flex; flex-direction: column; gap: 12px; }
      .linkbtn {
        align-self: flex-start;
        background: none;
        border: none;
        color: var(--portal-accent-primary, #2563eb);
        font-size: 13px;
        cursor: pointer;
        padding: 0;
      }
      .head { display: flex; align-items: center; gap: 10px; }
      .badge.stage { background: var(--portal-accent-primary, #2563eb); color: #fff; border-color: transparent; text-transform: lowercase; }
      .mono { font-family: ui-monospace, monospace; font-size: 12px; }
      .req { white-space: pre-wrap; background: var(--portal-bg-hover, #f3f4f6); border-radius: 8px; padding: 10px; font-size: 12px; }
      .list { list-style: none; margin: 8px 0 0; padding: 0; display: flex; flex-direction: column; gap: 8px; font-size: 13px; }
      .list li { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
      .doc-title { font-weight: 500; }
      .note { white-space: pre-wrap; width: 100%; }
      .results { margin: 4px 0 0; padding-left: 18px; font-size: 13px; display: flex; flex-direction: column; gap: 4px; width: 100%; }
      .slim-badge { padding: 0 6px; font-size: 10px; }
      .addrow { display: flex; gap: 8px; align-items: center; margin-top: 10px; }
      .input, .select {
        padding: 6px 8px;
        font-size: 13px;
        border-radius: 8px;
        border: 1px solid var(--portal-modal-border, #d1d5db);
        background: var(--portal-modal-bg, #fff);
        color: inherit;
      }
      .select { max-width: 220px; }
      .grow { flex: 1; }
      .btn { border-radius: 8px; border: none; padding: 6px 10px; font-size: 13px; cursor: pointer; }
      .btn.accent { background: var(--portal-accent-primary, #2563eb); color: #fff; }
      .btn.ghost { background: none; border: 1px solid var(--portal-modal-border, #d1d5db); color: inherit; }
      .btn:disabled { opacity: 0.6; cursor: default; }
      .errorline { color: var(--portal-status-danger, #dc2626); font-size: 12px; margin-top: 6px; }
    `,
  ],
})
export class RfpDetailComponent implements OnInit {
  private readonly client = inject(ModuleClient);

  readonly rfp = input.required<Rfp>();
  readonly back = output<void>();

  readonly error = signal<string | null>(null);
  readonly candidates = signal<Candidate[]>([]);
  readonly runs = signal<MatchRun[]>([]);
  readonly shortlists = signal<Shortlist[]>([]);
  readonly matching = signal(false);
  readonly matchError = signal<string | null>(null);
  readonly scanError = signal<string | null>(null);
  readonly shortlistError = signal<string | null>(null);

  /** Fake docsource refs known to the module backend (browse UI arrives with the Azure adapter). */
  readonly knownRefs = ['fake:cv-ana', 'fake:cv-bruno', 'fake:cv-carla'];

  ngOnInit(): void {
    void this.reloadAll();
  }

  private async reloadAll(): Promise<void> {
    try {
      const [cands, runs, shortlists] = await Promise.all([
        this.client.get<{ candidates: Candidate[] }>('/api/candidates'),
        this.client.get<{ runs: MatchRun[] }>(`/api/rfps/${this.rfp().id}/runs`).catch(() => ({ runs: [] as MatchRun[] })),
        this.client.get<{ shortlists: Shortlist[] }>(`/api/rfps/${this.rfp().id}/shortlists`),
      ]);
      this.candidates.set(cands.candidates);
      this.runs.set(runs.runs);
      this.shortlists.set(shortlists.shortlists);
      this.error.set(null);
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }

  reqText(): string {
    return JSON.stringify(this.rfp().requirements, null, 1);
  }

  joinSkills(c: Candidate): string {
    return c.skills.join(', ');
  }

  async runMatching(): Promise<void> {
    this.matching.set(true);
    this.matchError.set(null);
    try {
      // create_match_run runs the full ranking server-side and persists the run
      const run = await this.client.post<{
        runId: string;
        results: MatchRun['results'];
      }>(`/agent/tools/create_match_run`, {
        tool: 'create_match_run',
        arguments: { rfpId: this.rfp().id },
      });
      this.runs.update((list) => [
        {
          id: run.runId,
          rfpId: this.rfp().id,
          status: 'done',
          results: run.results,
          createdAt: new Date().toISOString(),
        },
        ...list,
      ]);
    } catch (err) {
      this.matchError.set(err instanceof Error ? err.message : String(err));
    } finally {
      this.matching.set(false);
    }
  }

  async createShortlist(): Promise<void> {
    const latest = this.runs()[0];
    if (!latest || latest.results.length === 0) return;
    this.shortlistError.set(null);
    try {
      await this.client.post(`/api/rfps/${this.rfp().id}/shortlists`, {
        candidateIds: latest.results.map((r) => r.candidateId),
      });
      const shortlists = await this.client.get<{ shortlists: Shortlist[] }>(
        `/api/rfps/${this.rfp().id}/shortlists`,
      );
      this.shortlists.set(shortlists.shortlists);
    } catch (err) {
      this.shortlistError.set(err instanceof Error ? err.message : String(err));
    }
  }

  async scanCv(ref: string, title: string): Promise<void> {
    if (!ref) return;
    this.scanError.set(null);
    try {
      await this.client.post('/api/candidates/scan', { documentRef: ref, title: title || ref });
      const cands = await this.client.get<{ candidates: Candidate[] }>('/api/candidates');
      this.candidates.set(cands.candidates);
    } catch (err) {
      this.scanError.set(err instanceof Error ? err.message : String(err));
    }
  }

  dateOf(iso: string): string {
    return new Date(iso).toLocaleString();
  }
}
