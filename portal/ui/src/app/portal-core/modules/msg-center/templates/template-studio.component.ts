import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { I18nService } from '../../../../core/i18n/i18n.service';
import { apiFetch, ApiError } from '../../../../core/http/api-fetch';

interface TemplateVersionRow {
  version: number;
  status: string;
  kind: string;
  completion: string;
  fields: string;
  sections: string | null;
  createdBy: string | null;
  createdAt: string;
}

interface TemplateDetail {
  key: string;
  name: string;
  retired: boolean;
  versions: TemplateVersionRow[];
}

interface DraftField {
  name: string;
  required: boolean;
  multiline: boolean;
  type: string;
  labelEn: string;
  minimum: number | null;
  maximum: number | null;
  maxLength: number | null;
  pattern: string;
  enumText: string;
  format: string;
}

const FIELD_TYPES = ['string', 'number', 'integer', 'boolean'] as const;

const STARTER_PRESETS: Record<string, DraftField[]> = {
  '1-level approval': [
    {
      name: 'note',
      required: false,
      multiline: true,
      type: 'string',
      labelEn: 'Note',
      minimum: null,
      maximum: null,
      maxLength: 500,
      pattern: '',
      enumText: '',
      format: '',
    },
  ],
  'comments/feedback request': [
    {
      name: 'feedback',
      required: true,
      multiline: true,
      type: 'string',
      labelEn: 'Feedback',
      minimum: null,
      maximum: null,
      maxLength: 2000,
      pattern: '',
      enumText: '',
      format: '',
    },
  ],
};

/**
 * Task Template Studio (plan §9 Phase 5): versioned, immutable template versions with a
 * drag & drop field builder bounded to the envelope's field allowlist. Publish stamps the next
 * version; delete only while zero published versions.
 */
@Component({
  selector: 'app-msg-center-template-studio',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './template-studio.component.html',
  styleUrl: './template-studio.component.css',
})
export class TemplateStudio implements OnInit {
  protected readonly i18n = inject(I18nService);

  protected readonly templates = signal<TemplateDetail[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly info = signal<string | null>(null);
  private busy = false;

  // current authoring state
  protected readonly draftKey = signal('');
  protected readonly draftName = signal('');
  protected readonly draftKind = signal('collect');
  protected readonly draftCompletion = signal('any');
  protected readonly fields = signal<DraftField[]>([]);
  protected readonly dragIndex = signal<number | null>(null);

  protected readonly hasPublishedVersion = computed(() =>
    this.templates().some((t) => t.key === this.draftKey() && t.versions.some((v) => v.status === 'published')),
  );

  protected readonly canDelete = computed(() => {
    const t = this.templates().find((tpl) => tpl.key === this.draftKey());
    return !!t && t.versions.length > 0 && !t.versions.some((v) => v.status === 'published');
  });

  protected readonly fieldTypes = FIELD_TYPES;
  protected readonly presetNames = Object.keys(STARTER_PRESETS);

  ngOnInit(): void {
    void this.refresh();
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const res = await apiFetch('/api/msgcenter/admin/templates');
      const payload = (await res.json()) as { templates?: TemplateDetail[] };
      this.templates.set(payload.templates ?? []);
    } catch (err) {
      this.error.set(err instanceof ApiError ? `API ${err.status}` : String(err));
    } finally {
      this.loading.set(false);
    }
  }

  protected selectTemplate(detail: TemplateDetail): void {
    this.draftKey.set(detail.key);
    this.draftName.set(detail.name);
    const latest = detail.versions[0];
    if (latest) {
      this.draftKind.set(latest.kind);
      this.draftCompletion.set(latest.completion);
      this.fields.set(parseFields(latest.fields));
    }
  }

  protected applyPreset(presetName: string): void {
    const preset = STARTER_PRESETS[presetName];
    if (preset) {
      this.fields.set(preset.map((f) => ({ ...f })));
    }
  }

  protected addField(): void {
    this.fields.update((list) => [
      ...list,
      {
        name: 'field-' + (list.length + 1),
        required: false,
        multiline: false,
        type: 'string',
        labelEn: '',
        minimum: null,
        maximum: null,
        maxLength: null,
        pattern: '',
        enumText: '',
        format: '',
      },
    ]);
  }

  protected updateField(index: number, key: keyof DraftField, value: unknown): void {
    this.fields.update((list) =>
      list.map((f, i) => (i === index ? { ...f, [key]: value } as DraftField : f)),
    );
  }

  protected updateDraft(key: 'key' | 'name' | 'kind' | 'completion', value: string): void {
    if (key === 'key') this.draftKey.set(value);
    if (key === 'name') this.draftName.set(value);
    if (key === 'kind') this.draftKind.set(value);
    if (key === 'completion') this.draftCompletion.set(value);
  }

  protected toNumber(value: unknown): number | null {
    if (value === '' || value == null) return null;
    const n = Number(value);
    return Number.isFinite(n) ? n : null;
  }

  protected removeField(index: number): void {
    this.fields.update((list) => list.filter((_, i) => i !== index));
  }

  // ── drag & drop reorder (HTML5 native, no CDK — bundle budget) ────────

  protected onDragStart(index: number): void {
    this.dragIndex.set(index);
  }

  protected onDrop(index: number): void {
    const from = this.dragIndex();
    this.dragIndex.set(null);
    if (from == null || from === index) return;
    this.fields.update((list) => {
      const copy = [...list];
      const [moved] = copy.splice(from, 1);
      copy.splice(index, 0, moved);
      return copy;
    });
  }

  protected onDragOver(event: Event): void {
    event.preventDefault();
  }

  // ── persistence ───────────────────────────────────────────────────────

  protected async createTemplate(): Promise<void> {
    await this.guarded(async () => {
      const res = await apiFetch('/api/msgcenter/admin/templates', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ key: this.draftKey(), name: this.draftName() }),
      });
      const payload = (await res.json()) as { key: string };
      this.info.set('created ' + payload.key);
      await this.refresh();
    });
  }

  protected async publishVersion(): Promise<void> {
    await this.guarded(async () => {
      const res = await apiFetch(`/api/msgcenter/admin/templates/${this.draftKey()}/versions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          kind: this.draftKind(),
          completion: this.draftCompletion(),
          fields: this.fieldsToJson(),
        }),
      });
      const payload = (await res.json()) as { version: number };
      this.info.set('published version ' + payload.version);
      await this.refresh();
    });
  }

  protected async retireVersion(version: number): Promise<void> {
    await this.guarded(async () => {
      await apiFetch(
        `/api/msgcenter/admin/templates/${this.draftKey()}/versions/${version}/retire`,
        { method: 'POST' },
      );
      await this.refresh();
    });
  }

  protected async deleteTemplate(): Promise<void> {
    await this.guarded(async () => {
      await apiFetch(`/api/msgcenter/admin/templates/${this.draftKey()}`, { method: 'DELETE' });
      this.draftKey.set('');
      this.fields.set([]);
      await this.refresh();
    });
  }

  private fieldsToJson(): unknown {
    return this.fields().map((f) => {
      const schema: Record<string, unknown> = { type: f.type };
      if (f.enumText.trim()) {
        schema['enum'] = f.enumText.split(',').map((s) => s.trim());
      }
      if (f.format) schema['format'] = f.format;
      if (f.pattern) schema['pattern'] = f.pattern;
      if (f.maxLength != null) schema['maxLength'] = f.maxLength;
      if (f.minimum != null) schema['minimum'] = f.minimum;
      if (f.maximum != null) schema['maximum'] = f.maximum;
      const field: Record<string, unknown> = { name: f.name, required: f.required, schema };
      if (f.multiline) field['multiline'] = true;
      if (f.labelEn.trim()) field['label'] = { en: f.labelEn.trim() };
      return field;
    });
  }

  private async guarded(action: () => Promise<void>): Promise<void> {
    this.busy = true;
    this.loading.set(true);
    this.error.set(null);
    this.info.set(null);
    try {
      await action();
    } catch (err) {
      this.error.set(err instanceof ApiError ? `API ${err.status}: ${err.body}` : String(err));
    } finally {
      this.busy = false;
      this.loading.set(false);
    }
  }
}

function parseFields(raw: string): DraftField[] {
  try {
    const parsed = JSON.parse(raw) as Array<Record<string, unknown>>;
    return parsed.map((f) => {
      const schema = (f['schema'] ?? {}) as Record<string, unknown>;
      return {
        name: String(f['name'] ?? ''),
        required: f['required'] === true,
        multiline: f['multiline'] === true,
        type: String(schema['type'] ?? 'string'),
        labelEn: labelEn(f['label']),
        minimum: numeric(schema['minimum']),
        maximum: numeric(schema['maximum']),
        maxLength: numeric(schema['maxLength']),
        pattern: String(schema['pattern'] ?? ''),
        enumText: Array.isArray(schema['enum'])
          ? (schema['enum'] as unknown[]).map(String).join(', ')
          : '',
        format: String(schema['format'] ?? ''),
      };
    });
  } catch {
    return [];
  }
}

function labelEn(label: unknown): string {
  if (label && typeof label === 'object' && 'en' in (label as Record<string, unknown>)) {
    return String((label as Record<string, unknown>)['en'] ?? '');
  }
  return '';
}

function numeric(value: unknown): number | null {
  return typeof value === 'number' ? value : null;
}
