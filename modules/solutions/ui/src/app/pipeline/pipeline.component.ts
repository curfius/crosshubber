import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ViewEncapsulation } from '@angular/core';
import {
  ClientSummary,
  ModuleClient,
  PIPELINE_STAGES,
  ProjectSummary,
  Stage,
} from '../shared/module-client';
import { SHARED_STYLES } from '../shared/styles';

interface StageGroup {
  stage: Stage;
  projects: ProjectSummary[];
}

/**
 * Project pipeline board grouped by stage (plan §Module 1 "UI (MFE, custom
 * element)"). v1 read slice: clients + projects from the module backend via
 * the portal MFE proxy; stage transitions arrive with the detail view.
 */
@Component({
  selector: 'sol-pipeline',
  encapsulation: ViewEncapsulation.ShadowDom,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      @if (error(); as err) {
        <div class="card error">{{ err }}</div>
      } @else if (loading()) {
        <div class="muted">Loading projects…</div>
      } @else {
        <div class="head">
          <h2>Project Pipeline</h2>
          <span class="muted">{{ total() }} project(s) · {{ clientCount() }} client(s)</span>
        </div>
        <div class="board">
          @for (group of groups(); track group.stage) {
            @if (group.projects.length > 0) {
              <section class="card">
                <h3>{{ group.stage }}</h3>
                <table>
                  <tbody>
                    @for (p of group.projects; track p.id) {
                      <tr>
                        <td class="name">{{ p.name }}</td>
                        <td class="owner muted">{{ p.owner }}</td>
                        <td>
                          @if (p.health; as h) {
                            <span class="badge {{ healthTone(h) }}">{{ h }}</span>
                          }
                        </td>
                        <td class="budget muted">
                          @if (p.budget) {
                            {{ p.budget }}
                          }
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </section>
            }
          }
        </div>
        @if (total() === 0) {
          <div class="muted">No projects yet.</div>
        }
      }
    </div>
  `,
  styles: [
    SHARED_STYLES +
      /* css */ `
      .wrap { padding: 16px; height: 100%; overflow: auto; }
      .head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 12px; }
      .head h2 { margin: 0; }
      .board { display: grid; gap: 12px; }
      .name { font-weight: 500; }
      .budget { text-align: right; }
    `,
  ],
})
export class PipelineComponent implements OnInit {
  private readonly client = inject(ModuleClient);

  readonly loading = signal(true);
  readonly error = signal<string | null>(null);
  readonly groups = signal<StageGroup[]>([]);
  readonly total = signal(0);
  readonly clientCount = signal(0);

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      const [projects, clients] = await Promise.all([
        this.client.get<{ projects: ProjectSummary[] }>('/api/projects'),
        this.client.get<{ clients: ClientSummary[] }>('/api/clients'),
      ]);
      this.groups.set(
        PIPELINE_STAGES.map((stage) => ({
          stage,
          projects: projects.projects.filter(
            (p) => (p.stage ?? '').toLowerCase() === stage,
          ),
        })),
      );
      this.total.set(projects.projects.length);
      this.clientCount.set(clients.clients.length);
      this.loading.set(false);
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
      this.loading.set(false);
    }
  }

  healthTone(health: string): string {
    if (health === 'on-track') return 'ok';
    if (health === 'at-risk') return 'warn';
    if (health === 'delayed') return 'danger';
    return '';
  }
}
