import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

@Component({
  selector: 'app-switch',
  template: `
    <button type="button" role="switch" [attr.aria-checked]="checked()" [disabled]="disabled()"
      (click)="toggled.emit(!checked())"
      [class]="checked()
        ? 'relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent bg-[var(--portal-accent-primary)] transition-colors duration-200 disabled:cursor-not-allowed disabled:opacity-50'
        : 'relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent bg-[var(--portal-border-strong)] transition-colors duration-200 disabled:cursor-not-allowed disabled:opacity-50'">
      <span [class]="checked()
        ? 'pointer-events-none inline-block h-4 w-4 rounded-full bg-white shadow ring-0 transition duration-200 translate-x-4'
        : 'pointer-events-none inline-block h-4 w-4 rounded-full bg-white shadow ring-0 transition duration-200 translate-x-0'"></span>
    </button>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Switch {
  readonly checked = input.required<boolean>();
  readonly disabled = input<boolean>(false);
  readonly toggled = output<boolean>();
}
