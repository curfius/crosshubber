import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { ViewEncapsulation } from '@angular/core';
import {
  DocumentLink,
  ModuleClient,
  Note,
  ProjectSummary,
  StageEvent,
} from '../shared/module-client';
import { SHARED_STYLES } from '../shared/styles';

/** Fake docsource refs known to the module backend (browse UI arrives with the Azure adapter). */
const KNOWN_REFS = ['fake:proposal-scope', 'fake:architecture-notes', 'fake:steering-minutes'];

interface FullProject {
  id: string;
  name: string;
  owner: string;
  stage: string;
  health: string | null;
  budget: number | string | null;
  client: { name: string } | null;
}

/**
 * Project detail for the pipeline board (plan §Module 1): info, immutable stage
 * history, linked documents with RAG toggles, notes. Mutations go through the
 * module backend with the element's agent-call token.
 */
@Component({
  selector: 'sol-project-detail',
  encapsulation: ViewEncapsulation.ShadowDom,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      <button class="linkbtn" (click)="back.emit()">← Board</button>
      @if (error(); as err) {
        <div class="card error">{{ err }}</div>
      } @else {
        <div class="head">
          <h2>{{ project().name }}</h2>
          <span class="badge stage">{{ project().stage.toLowerCase() }}</span>
          @if (project().health; as h) {
            <span class="badge {{ tone(h) }}">{{ h }}</span>
          }
        </div>
        <p class="muted">
          Owner {{ project().owner }} · {{ clientName() }}@if (project().budget) { · budget
          {{ project().budget }}}
        </p>

        <section class="card">
          <h3>Stage history</h3>
          @if (history().length === 0) {
            <div class="muted">No transitions yet.</div>
          }
          <ul class="events">
            @for (ev of history(); track ev.id) {
              <li>
                <span class="mono"
                  >{{ ev.fromStage.toLowerCase() }} → {{ ev.toStage.toLowerCase() }}</span
                >
                <span class="muted">{{ ev.actor }} · {{ dateOf(ev.occurredAt) }}</span>
                @if (ev.note) {
                  <div class="muted note">{{ ev.note }}</div>
                }
              </li>
            }
          </ul>
        </section>

        <section class="card">
          <h3>Documents (RAG)</h3>
          @if (documents().length === 0) {
            <div class="muted">No documents linked.</div>
          }
          <ul class="docs">
            @for (doc of documents(); track doc.id) {
              <li>
                <span class="doc-title">{{ doc.title }}</span>
                <span class="mono muted">{{ doc.documentRef }}</span>
                <label class="rag">
                  <input
                    type="checkbox"
                    [checked]="doc.ragEnabled"
                    (change)="toggleRag(doc, $any($event.target).checked)"
                  />
                  RAG
                </label>
              </li>
            }
          </ul>
          <div class="addrow">
            <select #refSel class="select slim" (change)="titleIn.value = titleFor(refSel.value)">
              @for (ref of knownRefs; track ref) {
                <option [value]="ref">{{ ref }}</option>
              }
            </select>
            <input #titleIn class="input slim grow" placeholder="Document title" />
            <button
              class="btn accent"
              (click)="addDocument(refSel.value, titleIn.value); titleIn.value = ''"
            >
              Link document
            </button>
          </div>
          @if (docError(); as de) {
            <div class="errorline">{{ de }}</div>
          }
        </section>

        <section class="card">
          <h3>Notes</h3>
          @if (notes().length === 0) {
            <div class="muted">No notes yet.</div>
          }
          <ul class="notes">
            @for (note of notes(); track note.id) {
              <li>
                <span class="author">{{ note.author }}</span>
                <span class="muted">{{ dateOf(note.createdAt) }}</span>
                <div>{{ note.body }}</div>
              </li>
            }
          </ul>
          <div class="addrow">
            <textarea #noteIn class="input slim grow" rows="2" placeholder="Add a note…"></textarea>
            <button class="btn accent" (click)="addNote(noteIn.value); noteIn.value = ''">
              Add note
            </button>
          </div>
          @if (noteError(); as ne) {
            <div class="errorline">{{ ne }}</div>
          }
        </section>
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
        display: flex;
        flex-direction: column;
        gap: 12px;
      }
      .linkbtn {
        align-self: flex-start;
        background: none;
        border: none;
        color: var(--portal-accent-primary, #2563eb);
        font-size: 13px;
        cursor: pointer;
        padding: 0;
      }
      .head {
        display: flex;
        align-items: center;
        gap: 10px;
      }
      .badge.stage {
        background: var(--portal-accent-primary, #2563eb);
        color: #fff;
        border-color: transparent;
        text-transform: lowercase;
      }
      section.card {
        margin-bottom: 0;
      }
      .events,
      .docs,
      .notes {
        list-style: none;
        margin: 8px 0 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 8px;
      }
      .events li {
        display: flex;
        flex-direction: column;
        gap: 2px;
        font-size: 13px;
        border-bottom: 1px solid var(--portal-bg-hover, #f1f5f9);
        padding-bottom: 6px;
      }
      .events li:last-child {
        border-bottom: none;
      }
      .mono {
        font-family: ui-monospace, monospace;
        font-size: 12px;
      }
      .note {
        white-space: pre-wrap;
      }
      .docs li {
        display: flex;
        align-items: center;
        gap: 10px;
        font-size: 13px;
      }
      .doc-title {
        font-weight: 500;
      }
      .rag {
        display: flex;
        align-items: center;
        gap: 4px;
        font-size: 12px;
        color: var(--portal-text-secondary, #4b5563);
        margin-left: auto;
      }
      .notes li {
        font-size: 13px;
        display: flex;
        flex-direction: column;
      }
      .author {
        font-weight: 600;
      }
      .addrow {
        display: flex;
        gap: 8px;
        align-items: center;
        margin-top: 10px;
      }
      .input,
      .select {
        padding: 6px 8px;
        font-size: 13px;
        border-radius: 8px;
        border: 1px solid var(--portal-modal-border, #d1d5db);
        background: var(--portal-modal-bg, #fff);
        color: inherit;
      }
      .select {
        max-width: 220px;
      }
      .grow {
        flex: 1;
      }
      .btn {
        border-radius: 8px;
        border: none;
        padding: 6px 10px;
        font-size: 13px;
        cursor: pointer;
      }
      .btn.accent {
        background: var(--portal-accent-primary, #2563eb);
        color: #fff;
      }
      .errorline {
        color: var(--portal-status-danger, #dc2626);
        font-size: 12px;
        margin-top: 6px;
      }
    `,
  ],
})
export class ProjectDetailComponent implements OnInit {
  private readonly client = inject(ModuleClient);

  readonly project = input.required<ProjectSummary>();
  readonly back = output<void>();

  readonly error = signal<string | null>(null);
  readonly clientName = signal('');
  readonly history = signal<StageEvent[]>([]);
  readonly documents = signal<DocumentLink[]>([]);
  readonly notes = signal<Note[]>([]);
  readonly docError = signal<string | null>(null);
  readonly noteError = signal<string | null>(null);
  readonly knownRefs = KNOWN_REFS;

  private readonly titles = new Map<string, string>([
    ['fake:proposal-scope', 'Proposal - scope'],
    ['fake:architecture-notes', 'Architecture notes'],
    ['fake:steering-minutes', 'Steering minutes'],
  ]);

  ngOnInit(): void {
    void this.reloadAll();
  }

  private async reloadAll(): Promise<void> {
    try {
      const [proj, hist, docs, notes] = await Promise.all([
        this.client.get<FullProject>(`/api/projects/${this.project().id}`),
        this.client.get<{ events: StageEvent[] }>(`/api/projects/${this.project().id}/history`),
        this.client.get<{ documents: DocumentLink[] }>(`/api/projects/${this.project().id}/documents`),
        this.client.get<{ notes: Note[] }>(`/api/projects/${this.project().id}/notes`),
      ]);
      this.clientName.set(proj.client?.name ?? '');
      this.history.set(hist.events);
      this.documents.set(docs.documents);
      this.notes.set(notes.notes);
      this.error.set(null);
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    }
  }

  titleFor(ref: string): string {
    return this.titles.get(ref) ?? '';
  }

  async toggleRag(doc: DocumentLink, enabled: boolean): Promise<void> {
    const previous = doc.ragEnabled;
    doc.ragEnabled = enabled;
    this.documents.update((list) => [...list]);
    try {
      await this.client.patch(
        `/api/projects/${this.project().id}/documents/${doc.id}`,
        { ragEnabled: enabled },
      );
    } catch (err) {
      doc.ragEnabled = previous;
      this.documents.update((list) => [...list]);
      this.docError.set(err instanceof Error ? err.message : String(err));
    }
  }

  async addDocument(ref: string, title: string): Promise<void> {
    if (!ref) return;
    this.docError.set(null);
    try {
      await this.client.post(`/api/projects/${this.project().id}/documents`, {
        documentRef: ref,
        title: title || this.titleFor(ref) || ref,
        ragEnabled: true,
      });
      const docs = await this.client.get<{ documents: DocumentLink[] }>(
        `/api/projects/${this.project().id}/documents`,
      );
      this.documents.set(docs.documents);
    } catch (err) {
      this.docError.set(err instanceof Error ? err.message : String(err));
    }
  }

  async addNote(body: string): Promise<void> {
    if (!body.trim()) return;
    this.noteError.set(null);
    try {
      await this.client.post(`/api/projects/${this.project().id}/notes`, { body });
      const notes = await this.client.get<{ notes: Note[] }>(
        `/api/projects/${this.project().id}/notes`,
      );
      this.notes.set(notes.notes);
    } catch (err) {
      this.noteError.set(err instanceof Error ? err.message : String(err));
    }
  }

  dateOf(iso: string): string {
    return new Date(iso).toLocaleString();
  }

  tone(health: string): string {
    if (health === 'on-track') return 'ok';
    if (health === 'at-risk') return 'warn';
    if (health === 'delayed') return 'danger';
    return '';
  }
}
