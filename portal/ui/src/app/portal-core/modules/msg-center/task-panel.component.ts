import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { I18nService } from '../../../core/i18n/i18n.service';
import { MsgCenterStore } from '../../../core/msg-center/msg-center.store';
import { inputValueFor } from '../../../core/msg-center/task-form-model';
import type { I18nMap, MsgCenterItem } from '../../../core/msg-center/msg-center.store';
import type {
  TaskFieldSpec,
  TaskSectionSpec,
  TaskSpec,
} from './task-panel.types';

/**
 * Task detail panel (plan §9 Phase-2 slice): claim/takeover affordances plus the generic
 * collect-form renderer and approval actions. Client-side checks mirror the server allowlist
 * for instant feedback; the server stays the sole submit authority.
 */
@Component({
  selector: 'app-msg-center-task-panel',
  imports: [FormsModule, NgTemplateOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './task-panel.component.html',
  styleUrl: './task-panel.component.css',
})
export class MsgCenterTaskPanel {
  readonly item = input.required<MsgCenterItem>();
  readonly changed = output<void>();

  private readonly store = inject(MsgCenterStore);
  protected readonly i18n = inject(I18nService);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  /** Working draft values (field name → scalar), adopted or locally typed. */
  protected readonly values = signal<Record<string, unknown>>({});
  protected readonly note = signal('');
  /** Takeover offer surfaced right after claiming. */
  protected readonly takeoverOffer = signal<string | null>(null);

  protected readonly task = computed(() => this.item().task as TaskSpec | null);

  protected readonly claimMode = computed(() => !!this.task()?.claim?.enabled);

  protected readonly claimedByMe = computed(() => {
    const item = this.item();
    const mySub = this.store.mySub();
    if (item.claimedBySub && mySub) return item.claimedBySub === mySub;
    // server identity not loaded yet (should not happen in the shell) — treat as claimed
    return !!item.claimedBySub;
  });

  protected readonly claimedByOther = computed(() => {
    const item = this.item();
    return item.claimedBySub != null && !this.claimedByMe();
  });

  protected readonly fieldBySection = computed(() => {
    const spec = this.task();
    if (!spec?.sections) return null;
    const all = new Map((spec.fields ?? []).map((f) => [f.name, f]));
    return spec.sections.map((s) => ({
      title: s.title,
      description: s.description,
      fields: (s.fields ?? []).map((n) => all.get(n)).filter((f): f is TaskFieldSpec => !!f),
    }));
  });

  protected readonly unsectionedFields = computed(() => {
    const spec = this.task();
    if (!spec?.fields) return [];
    if (!spec.sections) return spec.fields;
    const grouped = new Set(spec.sections.flatMap((s) => s.fields ?? []));
    return spec.fields.filter((f) => !grouped.has(f.name));
  });

  protected readonly isExpired = computed(() => {
    const expiresAt = this.task()?.expiresAt;
    if (!expiresAt) return false;
    return new Date(expiresAt).getTime() < Date.now();
  });

  protected readonly editable = computed(
    () =>
      this.item().status !== 'done' &&
      !this.isExpired() &&
      (!this.claimMode() || this.claimedByMe()),
  );

  protected pickI18n(map: I18nMap | undefined | null): string {
    if (!map) return '';
    return map['en'] ?? Object.values(map)[0] ?? '';
  }

  protected inputValue(event: Event, name: string): void {
    const target = event.target as {
      type?: string;
      checked?: boolean;
      value?: string;
    };
    const coerced = inputValueFor(target);
    this.values.update((v) => ({ ...v, [name]: coerced.value }));
  }

  protected checked(name: string): boolean {
    return this.values()[name] === true;
  }

  protected valueOf(name: string): unknown {
    return this.values()[name] ?? '';
  }

  /** Enum options are scalars — label them via String(). */
  protected optionLabel(option: unknown): string {
    return String(option);
  }

  protected fieldError(name: string): string | null {
    const spec = this.task()?.fields?.find((f) => f.name === name);
    const value = this.values()[name];
    if (value === undefined || value === null || value === '') return null;
    if (spec?.schema.type === 'number' || spec?.schema.type === 'integer') {
      const n = Number(value);
      if (Number.isNaN(n)) return 'not a number';
      if (spec.schema.minimum !== undefined && n < spec.schema.minimum) return `≥ ${spec.schema.minimum}`;
      if (spec.schema.maximum !== undefined && n > spec.schema.maximum) return `≤ ${spec.schema.maximum}`;
      if (spec.schema.type === 'integer' && !Number.isInteger(n)) return 'integer required';
    }
    if (typeof value === 'string') {
      if (spec?.schema.maxLength !== undefined && value.length > spec.schema.maxLength) {
        return `≤ ${spec.schema.maxLength} chars`;
      }
      if (spec?.schema.pattern && !value.match(new RegExp(spec.schema.pattern))) return 'pattern mismatch';
    }
    return null;
  }

  protected missingRequired(): string[] {
    const spec = this.task();
    if (!spec?.fields) return [];
    return spec.fields
      .filter(
        (f) =>
          f.required &&
          (this.values()[f.name] === undefined ||
            this.values()[f.name] === null ||
            this.values()[f.name] === ''),
      )
      .map((f) => f.name);
  }

  protected async claim(): Promise<void> {
    await this.run(async () => {
      const offer = await this.store.claimTask(this.item().id);
      this.takeoverOffer.set(offer.fromName);
      if (offer.draft && Object.keys(offer.draft).length > 0) {
        this.values.set(offer.draft);
      }
    });
  }

  protected async adopt(): Promise<void> {
    await this.run(async () => {
      const data = await this.store.adoptDraftTask(this.item().id);
      this.values.set(data);
      this.takeoverOffer.set(null);
    });
  }

  protected startFresh(): void {
    this.values.set({});
    this.takeoverOffer.set(null);
  }

  protected async saveDraft(): Promise<void> {
    await this.run(async () => {
      await this.store.saveDraftTask(this.item().id, this.values(), this.note() || null);
    });
  }

  protected async submit(): Promise<void> {
    const missing = this.missingRequired();
    if (missing.length > 0) {
      this.error.set('required: ' + missing.join(', '));
      return;
    }
    await this.run(async () => {
      await this.store.respondTask(this.item().id, 'submit', this.values(), this.note() || null);
      this.changed.emit();
    });
  }

  protected async approve(): Promise<void> {
    await this.run(async () => {
      await this.store.respondTask(this.item().id, 'approve', null, this.note() || null);
      this.changed.emit();
    });
  }

  protected async deny(): Promise<void> {
    await this.run(async () => {
      await this.store.respondTask(this.item().id, 'deny', null, this.note() || null);
      this.changed.emit();
    });
  }

  protected async release(discardDraft: boolean): Promise<void> {
    await this.run(async () => {
      await this.store.releaseTask(this.item().id, discardDraft);
      this.values.set({});
      this.changed.emit();
    });
  }

  protected async reset(): Promise<void> {
    await this.run(async () => {
      await this.store.resetTask(this.item().id);
      this.values.set({});
      this.changed.emit();
    });
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await action();
    } catch (err) {
      this.error.set(err instanceof Error ? err.message : String(err));
    } finally {
      this.busy.set(false);
    }
  }
}
