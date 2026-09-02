import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

@Component({
  selector: 'app-confirm-dialog',
  template: `
    <div class="ds-modal-backdrop" (click)="cancelled.emit()">
      <div class="ds-modal w-full max-w-sm" (click)="$event.stopPropagation()">
        <div class="ds-modal-header">
          <h2 class="ds-modal-title">{{ title() }}</h2>
        </div>
        <div class="ds-modal-body">
          <p class="text-sm text-[var(--portal-text-tertiary)]">{{ message() }}</p>
        </div>
        <div class="ds-modal-footer">
          <button type="button" (click)="cancelled.emit()"
            class="ds-btn ds-btn-ghost">Cancel</button>
          <button type="button" (click)="confirmed.emit()"
            class="ds-btn ds-btn-danger">
            {{ confirmLabel() }}
          </button>
        </div>
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmDialog {
  readonly title = input<string>('Confirm');
  readonly message = input<string>('');
  readonly confirmLabel = input<string>('Delete');
  readonly confirmed = output<void>();
  readonly cancelled = output<void>();
}
