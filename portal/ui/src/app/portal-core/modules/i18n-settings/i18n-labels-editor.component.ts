import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { I18nAdminService } from '../../../core/i18n/i18n-admin.service';
import { I18nService } from '../../../core/i18n/i18n.service';

interface LabelDraft {
  key: string;
  value: string;
  original: string;
  dirty: boolean;
}

@Component({
  selector: 'app-i18n-labels-editor',
  imports: [FormsModule],
  templateUrl: './i18n-labels-editor.component.html',
  styleUrl: './i18n-labels-editor.component.css',
})
export class I18nLabelsEditor {
  private readonly admin = inject(I18nAdminService);
  protected readonly i18n = inject(I18nService);

  protected readonly canEdit = this.admin.canEdit;
  protected readonly saving = this.admin.saving;
  protected readonly loadError = this.admin.loadError;

  protected readonly config = this.admin.config;
  protected readonly selectedLang = signal('');
  protected readonly entries = signal<LabelDraft[]>([]);
  protected readonly baseLabels = signal<Record<string, string>>({});
  protected readonly search = signal('');
  protected readonly loadingLabels = signal(false);
  protected readonly saveState = signal<'idle' | 'ok' | 'error'>('idle');
  private saveTimer: ReturnType<typeof setTimeout> | null = null;

  protected readonly allLanguages = computed(() => this.config()?.languages ?? []);

  protected readonly dirtyCount = computed(() => this.entries().filter((e) => e.dirty).length);

  protected readonly filteredEntries = computed(() => {
    const q = this.search().trim().toLowerCase();
    const list = this.entries();
    if (!q) return list;
    return list.filter((e) => e.key.toLowerCase().includes(q) || e.value.toLowerCase().includes(q));
  });

  /** Keys grouped by their first namespace segment: `shell.toolbar.x` → `shell`. */
  protected readonly groups = computed<Array<{ namespace: string; entries: LabelDraft[] }>>(() => {
    const map = new Map<string, LabelDraft[]>();
    for (const entry of this.filteredEntries()) {
      const ns = entry.key.split('.')[0] ?? 'other';
      const list = map.get(ns);
      if (list) list.push(entry);
      else map.set(ns, [entry]);
    }
    return [...map.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([namespace, entries]) => ({ namespace, entries }));
  });

  constructor() {
    void this.initSelection();
  }

  private async initSelection(): Promise<void> {
    await this.admin.load();
    const cfg = this.admin.config();
    if (cfg) await this.selectLanguage(cfg.defaultLanguage);
  }

  protected async selectLanguage(code: string): Promise<void> {
    if (!code || this.dirtyCount() > 0) return;
    this.selectedLang.set(code);
    this.loadingLabels.set(true);
    try {
      const cfg = this.admin.config();
      const baseCode = cfg?.defaultLanguage ?? code;
      const [target, base] = await Promise.all([
        this.admin.loadLabels(code),
        this.admin.loadLabels(baseCode),
      ]);
      this.baseLabels.set(base ?? {});
      const list = Object.keys(base ?? {}).sort().map<LabelDraft>((key) => ({
        key,
        value: target?.[key] ?? '',
        original: target?.[key] ?? '',
        dirty: false,
      }));
      this.entries.set(list);
    } finally {
      this.loadingLabels.set(false);
    }
  }

  protected onEdit(key: string, value: string): void {
    this.entries.update((list) =>
      list.map((e) =>
        e.key === key ? { ...e, value, dirty: value !== e.original } : e,
      ),
    );
  }

  protected async save(): Promise<void> {
    const code = this.selectedLang();
    if (!code || !this.canEdit() || this.saving()) return;
    const changed = this.entries().filter((e) => e.dirty).map((e) => ({ key: e.key, value: e.value }));
    if (!changed.length) return;
    const ok = await this.admin.saveLabels(code, changed);
    if (ok) {
      this.entries.update((list) => list.map((e) => (e.dirty ? { ...e, original: e.value, dirty: false } : e)));
    }
    this.saveState.set(ok ? 'ok' : 'error');
    if (this.saveTimer) clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => this.saveState.set('idle'), 2500);
  }

  protected onLanguageChange(code: string): void {
    if (code === this.selectedLang()) return;
    void this.selectLanguage(code);
  }
}
