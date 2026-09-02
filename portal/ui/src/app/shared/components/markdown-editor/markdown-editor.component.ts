import { Component, Input, Output, EventEmitter, ElementRef, ViewChild, OnChanges, SimpleChanges, signal, inject, ViewEncapsulation } from '@angular/core';
import { marked } from 'marked';
import { I18nService } from '../../../core/i18n/i18n.service';

marked.setOptions({ breaks: true, gfm: true });

@Component({
  selector: 'app-markdown-editor',
  templateUrl: './markdown-editor.component.html',
  styleUrl: './markdown-editor.component.css',
  // None: the build's scoping pipeline emits unsubstituted %COMP% placeholders
  // for this component's styles, killing every rule (see git history). All CSS
  // is namespaced under .markdown-editor manually instead.
  encapsulation: ViewEncapsulation.None,
})
export class MarkdownEditorComponent implements OnChanges {
  protected readonly i18n = inject(I18nService);
  @Input() content = '';
  @Output() contentChange = new EventEmitter<string>();
  @Input() rows = 10;
  @Input() placeholder = '';

  @ViewChild('textarea') private textareaRef!: ElementRef<HTMLTextAreaElement>;
  @ViewChild('preview') private previewRef?: ElementRef<HTMLDivElement>;

  protected showPreview = false;
  protected headingMenuOpen = false;
  protected readonly activeHeading = signal<string | null>(null);
  private syncing = false;

  protected get minHeight(): string {
    return `${this.rows * 1.5}rem`;
  }

  protected get renderedContent(): string {
    try {
      return marked.parse(this.content || '') as string;
    } catch {
      return '';
    }
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['content'] && !changes['content'].firstChange) {
      this.syncTextarea();
    }
  }

  // --- Events ---

  protected onInput(): void {
    const el = this.textareaRef?.nativeElement;
    if (!el) return;
    this.content = el.value;
    this.contentChange.emit(el.value);
    this.detectHeading();
    // Re-render settles during the following CD cycle; sync once heights are live.
    setTimeout(() => this.syncScroll('source'), 0);
  }

  protected onTextareaScroll(): void {
    this.syncScroll('source');
  }

  protected onPreviewScroll(): void {
    this.syncScroll('preview');
  }

  // Two-way proportional scroll: maps relative position of the origin pane
  // onto the other. The syncing flag (released on next frame) prevents the
  // two scroll handlers from feeding back into each other.
  private syncScroll(origin: 'source' | 'preview'): void {
    if (!this.showPreview || this.syncing) return;
    const el = this.textareaRef?.nativeElement;
    const pv = this.previewRef?.nativeElement;
    if (!el || !pv) return;
    const from = origin === 'source' ? el : pv;
    const to = origin === 'source' ? pv : el;
    const fromMax = from.scrollHeight - from.clientHeight;
    const toMax = to.scrollHeight - to.clientHeight;
    if (fromMax <= 0 || toMax <= 0) return;
    this.syncing = true;
    to.scrollTop = (from.scrollTop / fromMax) * toMax;
    requestAnimationFrame(() => { this.syncing = false; });
  }

  protected onKeydown(e: KeyboardEvent): void {
    if (e.ctrlKey || e.metaKey) {
      switch (e.key.toLowerCase()) {
        case 'b': e.preventDefault(); this.bold(); break;
        case 'i': e.preventDefault(); this.italic(); break;
        case '`': e.preventDefault(); this.code(); break;
      }
    }
    setTimeout(() => this.detectHeading(), 0);
  }

  protected onSelect(): void {
    this.detectHeading();
  }

  protected togglePreview(): void {
    this.showPreview = !this.showPreview;
  }

  protected toggleHeadingMenu(): void {
    this.headingMenuOpen = !this.headingMenuOpen;
  }

  protected closeHeadingMenu(): void {
    this.headingMenuOpen = false;
  }

  // --- Toolbar ---

  protected bold(): void { this.wrap('**'); }
  protected italic(): void { this.wrap('*'); }
  protected code(): void { this.wrap('`'); }
  protected link(): void {
    const sel = this.getSelection();
    if (!sel) return;
    const text = sel.text || 'link text';
    const replacement = '[' + text + '](https://)';
    this.replaceRange(sel.start, sel.end, replacement);
    const el = this.textareaRef?.nativeElement;
    const urlStart = sel.start + 1 + text.length + 2; // [text](
    el?.setSelectionRange(urlStart, urlStart + 'https://'.length);
  }

  protected heading(level: number): void {
    this.headingMenuOpen = false;
    this.prefixLines('#'.repeat(level) + ' ');
    this.detectHeading();
  }

  protected removeHeading(): void {
    this.headingMenuOpen = false;
    const sel = this.getSelection();
    if (!sel) return;
    const lineStart = sel.value.lastIndexOf('\n', sel.start - 1) + 1;
    const lineEnd = sel.value.indexOf('\n', sel.start);
    const line = sel.value.substring(lineStart, lineEnd === -1 ? sel.value.length : lineEnd);
    const stripped = line.replace(/^\s*#{1,6}\s+/, '');
    if (stripped === line) return;
    this.replaceRange(lineStart, lineEnd === -1 ? sel.value.length : lineEnd, stripped);
    this.detectHeading();
  }

  protected bullets(): void { this.prefixLines('- '); }
  protected numbers(): void { this.prefixOrdered(); }
  protected quote(): void { this.prefixLines('> '); }
  protected insertTable(): void {
    const sel = this.getSelection();
    if (!sel) return;
    const table = '\n| Column 1 | Column 2 | Column 3 |\n| --- | --- | --- |\n| Cell     | Cell     | Cell     |\n';
    this.replaceRange(sel.start, sel.end, table);
  }
  protected hr(): void {
    const sel = this.getSelection();
    if (!sel) return;
    this.replaceRange(sel.start, sel.end, '\n\n---\n\n');
  }

  // --- Insert helpers (undo-preserving via execCommand) ---

  private getSelection(): { start: number; end: number; text: string; value: string } | null {
    const el = this.textareaRef?.nativeElement;
    if (!el) return null;
    const start = el.selectionStart ?? 0;
    const end = el.selectionEnd ?? 0;
    return { start, end, text: el.value.substring(start, end), value: el.value };
  }

  private replaceRange(start: number, end: number, text: string): void {
    const el = this.textareaRef?.nativeElement;
    if (!el) return;
    el.focus();
    el.setSelectionRange(start, end);
    // execCommand keeps the browser's native undo stack intact (Ctrl+Z)
    document.execCommand('insertText', false, text);
    this.onInput();
  }

  private wrap(marker: string): void {
    const sel = this.getSelection();
    if (!sel) return;
    const selected = sel.text;
    const replacement = marker + (selected || 'text') + marker;
    this.replaceRange(sel.start, sel.end, replacement);
    if (!selected) {
      const el = this.textareaRef?.nativeElement;
      el?.setSelectionRange(sel.start + marker.length, sel.start + marker.length + 4);
    }
  }

  private expandToFullLines(sel: { start: number; end: number; value: string }): { start: number; end: number } {
    const start = sel.value.lastIndexOf('\n', sel.start - 1) + 1;
    let end = sel.value.indexOf('\n', sel.end);
    if (end === -1) end = sel.value.length;
    return { start, end };
  }

  private prefixLines(prefix: string): void {
    const sel = this.getSelection();
    if (!sel) return;
    const { start, end } = this.expandToFullLines(sel);
    const block = sel.value.substring(start, end);
    const lines = block.split('\n');
    const prefixed = lines.map((l) => prefix + l).join('\n');
    this.replaceRange(start, end, prefixed);
  }

  private prefixOrdered(): void {
    const sel = this.getSelection();
    if (!sel) return;
    const { start, end } = this.expandToFullLines(sel);
    const block = sel.value.substring(start, end);
    const lines = block.split('\n');
    const prefixed = lines.map((l, i) => `${i + 1}. ${l}`).join('\n');
    this.replaceRange(start, end, prefixed);
  }

  // --- State ---

  private detectHeading(): void {
    const sel = this.getSelection();
    if (!sel) return;
    const lineStart = sel.value.lastIndexOf('\n', sel.start - 1) + 1;
    const line = sel.value.substring(lineStart, sel.start);
    const m = line.match(/^(#{1,6})\s+\S/);
    this.activeHeading.set(m ? `h${m[1].length}` : null);
  }

  private syncTextarea(): void {
    const el = this.textareaRef?.nativeElement;
    if (!el) return;
    if (el.value !== this.content) {
      el.value = this.content || '';
    }
  }
}
