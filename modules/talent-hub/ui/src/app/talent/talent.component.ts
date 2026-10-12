import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  ViewEncapsulation,
  computed,
  inject,
  signal,
} from '@angular/core';

import { ModuleClient, canRecruit } from '../shared/module-client';
import { SHARED_STYLES } from '../shared/styles';
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

/** Talent pool (P2.8): directory + filters + detail + sourcing inbox + manual add. */
@Component({
  selector: 'th-talent',
  encapsulation: ViewEncapsulation.ShadowDom,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      <div class="head">
        <h2>Talent Pool</h2>
        <select [value]="mode()" (change)="onMode($event)">
          <option value="directory">directory</option>
          <option value="inbox">sourcing inbox</option>
        </select>
      </div>

      @if (error(); as err) {
        <div class="card error">{{ err }}</div>
      } @else if (loading()) {
        <div class="muted">Loading…</div>
      } @else if (mode() === 'inbox') {
        @for (item of inbox(); track item.ref) {
          <div class="card">
            <div class="row">
              <strong>{{ item.name }}</strong>
              <span class="muted">{{ item.headline }}</span>
            </div>
            <div class="row small">
              @if (canWrite()) {
                <button class="primary" (click)="claim(item.ref)">claim</button>
              }
              <span class="muted">ref {{ item.ref }}</span>
            </div>
          </div>
        }
        @if (inbox().length === 0) {
          <div class="muted">Inbox is empty.</div>
        }
        <div class="row actions">
          @if (canWrite()) {
            <input placeholder="document ref (fake:cv-…)" [value]="scanRef()" (input)="onScanRef($event)" />
            <button [disabled]="scanRef().trim().length === 0" (click)="scan()">add by CV scan</button>
          }
        </div>
      } @else if (detailId() !== null) {
        @if (detail(); as d) {
          <div class="card">
            <div class="head">
              <strong>{{ d.candidate.name }}</strong>
              <span class="badge">{{ d.candidate.kind }}</span>
              @if (d.candidate.stale) {
                <span class="badge warn">stale</span>
              }
              <button (click)="backToDirectory()">Back</button>
            </div>
            @if (d.candidate.headline; as headline) {
              <div class="muted">{{ headline }}</div>
            }
            @if (d.candidate.skills.length > 0) {
              <div class="row">
                @for (skill of d.candidate.skills; track skill) {
                  <span class="badge">{{ skill }}</span>
                }
              </div>
            }
            <div class="small">
              seniority: {{ d.candidate.seniority ?? '—' }} · languages:
              {{ d.candidate.languages.join(', ') || '—' }} · availability:
              {{ d.candidate.availability ?? '—' }}
            </div>
            @if (d.candidate.assignment !== null) {
              <div class="small assignment">
                {{ stringify(d.candidate.assignment) }}
              </div>
            }
            <h3>Process history</h3>
            @for (h of d.history; track h.stage + h.positionTitle) {
              <div class="row">
                <span>{{ h.positionTitle ?? '—' }}</span>
                <span class="muted">in {{ h.rfpTitle ?? '—' }}</span>
                <span class="badge">{{ h.stage }}</span>
              </div>
            }
            @if (d.history.length === 0) {
              <div class="muted">No processes yet.</div>
            }
          </div>
        }
      } @else {
        <div class="row">
          <input placeholder="search" [value]="term()" (input)="onTerm($event)" />
          <select [value]="kindFilter()" (change)="onKind($event)">
            <option value="">all kinds</option>
            <option value="internal">internal</option>
            <option value="external">external</option>
          </select>
          <label class="small"><input type="checkbox" (change)="onStale($event)" /> stale only</label>
        </div>
        @for (c of filtered(); track c.id) {
          <div class="card clickable" (click)="openDetail(c.id)">
            <div class="row">
              <strong>{{ c.name }}</strong>
              <span class="badge">{{ c.kind }}</span>
              @if (c.stale) {
                <span class="badge warn">stale</span>
              }
            </div>
            @if (c.headline; as headline) {
              <div class="muted">{{ headline }}</div>
            }
            @if (c.skills.length > 0) {
              <span class="muted">{{ c.skills.slice(0, 6).join(' · ') }}</span>
            }
          </div>
        }
        @if (filtered().length === 0) {
          <div class="muted">No candidates match.</div>
        }
        @if (canWrite()) {
          <div class="card">
            <h3>Manual add</h3>
            <div class="row">
              <input placeholder="name" [value]="newName()" (input)="onName($event)" />
              <input placeholder="headline" [value]="newHeadline()" (input)="onHeadline($event)" />
              <select [value]="newKind()" (change)="onKindPick($event)">
                <option value="external">external</option>
                <option value="internal">internal</option>
              </select>
              <button [disabled]="newName().trim().length === 0" (click)="create()">add</button>
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
      .head,
      .row,
      .actions {
        display: flex;
        gap: 8px;
        align-items: center;
        flex-wrap: wrap;
      }
      .small {
        font-size: 12px;
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
      .clickable {
        cursor: pointer;
      }
      .assignment {
        white-space: pre-wrap;
      }
    `,
  ],
})
export class TalentComponent implements OnInit {
  private readonly client = inject(ModuleClient);
  private readonly signalReady = mfeReadyDispatcher();

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

  private user: { sub: string; name?: string | null; roles: string[] } | null = null;

  readonly canWrite = computed(() => canRecruit(this.user));
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

  stringify(value: Record<string, unknown> | null): string {
    return value === null ? '' : JSON.stringify(value);
  }

  private onMode(event: Event): void {
    this.mode.set((event.target as HTMLSelectElement).value as 'directory' | 'inbox');
    this.error.set(null);
    void this.refresh();
  }

  private onTerm(event: Event): void {
    this.term.set((event.target as HTMLInputElement).value);
  }

  private onKind(event: Event): void {
    this.kindFilter.set((event.target as HTMLSelectElement).value);
  }

  private onStale(event: Event): void {
    this.staleOnly.set((event.target as HTMLInputElement).checked);
  }

  private openDetail(id: string): void {
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

  private backToDirectory(): void {
    this.detailId.set(null);
    this.detail.set(null);
  }

  private onName(event: Event): void {
    this.newName.set((event.target as HTMLInputElement).value);
  }

  private onHeadline(event: Event): void {
    this.newHeadline.set((event.target as HTMLInputElement).value);
  }

  private onKindPick(event: Event): void {
    this.newKind.set((event.target as HTMLSelectElement).value as 'external' | 'internal');
  }

  private async create(): Promise<void> {
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

  private onScanRef(event: Event): void {
    this.scanRef.set((event.target as HTMLInputElement).value);
  }

  private async scan(): Promise<void> {
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

  private async claim(ref: string): Promise<void> {
    try {
      await this.client.post('/api/sourcing/inbox/' + ref + '/claim', {});
      await this.refresh();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }
}
