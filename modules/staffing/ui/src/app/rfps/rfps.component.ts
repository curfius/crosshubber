import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ViewEncapsulation } from '@angular/core';
import { ModuleClient, RFP_STATUSES, Rfp } from '../shared/module-client';
import { SHARED_STYLES } from '../shared/styles';
import { RfpDetailComponent } from './rfp-detail.component';

/**
 * RFP matcher surface (plan §Module 2): RFP list with status grouping, detail
 * with requirements/match runs/shortlists, candidates + CV scan. v1 read +
 * match-run slice; requirements extraction stays LLM-assisted (H5 polish).
 */
@Component({
  selector: 'stf-rfps',
  encapsulation: ViewEncapsulation.ShadowDom,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (selected(); as rfp) {
      <stf-rfp-detail [rfp]="rfp" (back)="selected.set(null)" />
    } @else {
      <div class="wrap">
        @if (error(); as err) {
          <div class="card error">{{ err }}</div>
        } @else if (loading()) {
          <div class="muted">Loading RFPs…</div>
        } @else {
          <div class="head">
            <h2>RFP Matcher</h2>
            <span class="muted">{{ total() }} RFP(s) · {{ candidateCount() }} candidate(s)</span>
          </div>
          <div class="board">
            @for (group of groups(); track group.status) {
              @if (group.rfps.length > 0) {
                <section class="card">
                  <h3>{{ group.status }}</h3>
                  <table>
                    <tbody>
                      @for (r of group.rfps; track r.id) {
                        <tr>
                          <td class="name clickable" (click)="selected.set(r)">{{ r.title }}</td>
                          <td class="muted">{{ r.client }}</td>
                          <td><span class="badge">{{ r.kind }}</span></td>
                          <td class="muted deadline">{{ dateOf(r.deadline) }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </section>
              }
            }
          </div>
          @if (total() === 0) {
            <div class="muted">No RFPs registered yet.</div>
          }
        }
      </div>
    }
  `,
  imports: [RfpDetailComponent],
  styles: [
    SHARED_STYLES +
      /* css */ `
      .wrap { padding: 16px; height: 100%; overflow: auto; }
      .head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 12px; }
      .head h2 { margin: 0; }
      .board { display: grid; gap: 12px; }
      .name { font-weight: 500; }
      .clickable { cursor: pointer; color: var(--portal-accent-primary, #2563eb); }
      .deadline { text-align: right; }
    `,
  ],
})
export class RfpsComponent implements OnInit {
  private readonly client = inject(ModuleClient);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly groups = signal<{ status: string; rfps: Rfp[] }[]>([]);
  readonly total = signal(0);
  readonly candidateCount = signal(0);
  readonly selected = signal<Rfp | null>(null);

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      const [rfps, candidates] = await Promise.all([
        this.client.get<{ rfps: Rfp[] }>('/api/rfps'),
        this.client.get<{ candidates: import('../shared/module-client').Candidate[] }>('/api/candidates'),
      ]);
      this.groups.set(
        RFP_STATUSES.map((status) => ({
          status,
          rfps: rfps.rfps.filter((r) => (r.status ?? '').toLowerCase() === status),
        })),
      );
      this.total.set(rfps.rfps.length);
      this.candidateCount.set(candidates.candidates.length);
      this.loading.set(false);
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
      this.loading.set(false);
    }
  }

  dateOf(iso: string): string {
    return new Date(iso).toLocaleDateString();
  }
}
