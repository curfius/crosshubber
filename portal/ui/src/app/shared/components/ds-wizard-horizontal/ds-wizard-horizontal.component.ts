import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

export interface WizardStep {
  key: string;
  title: string;
  description?: string;
}

@Component({
  selector: 'app-ds-wizard-horizontal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ds-wh">
      <div class="ds-wh-steps">
        @for (step of steps(); track step.key; let i = $index) {
          <div class="ds-wh-step"
            [class.ds-wh-step-active]="i === activeIndex()"
            [class.ds-wh-step-completed]="completed()[i]"
            [class.ds-wh-step-disabled]="!completed()[i] && i !== activeIndex()"
            [class.ds-wh-step-error]="!!errors()[i]">
            <div class="ds-wh-step-header"
              [attr.aria-disabled]="!completed()[i] && i !== activeIndex() ? 'true' : null">
              <span class="ds-wh-indicator">
                @if (completed()[i] && i !== activeIndex()) {
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M5 13l4 4L19 7"/></svg>
                } @else if (errors()[i] && i === activeIndex()) {
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M12 8v5"/><path d="M12 16h.01"/><circle cx="12" cy="12" r="9"/></svg>
                } @else {
                  {{ i + 1 }}
                }
              </span>
              <span class="ds-wh-step-titles">
                <span class="ds-wh-step-title">{{ step.title }}</span>
                @if (step.description) {
                  <span class="ds-wh-step-description">{{ step.description }}</span>
                }
              </span>
            </div>
          </div>
        }
      </div>

      <div class="ds-wh-body ds-wh-body-open">
        <div class="ds-wh-body-inner">
          <div class="ds-wh-body-content">
            <ng-content></ng-content>
          </div>
          <div class="ds-wh-step-footer">
            <ng-content select="[footer]"></ng-content>
          </div>
        </div>
      </div>
    </div>
  `,
})
export class DsWizardHorizontal {
  readonly steps = input.required<WizardStep[]>();
  readonly activeIndex = input<number>(0);
  readonly completed = input<boolean[]>([]);
  readonly errors = input<string[]>([]);
  readonly busy = input<boolean>(false);

  readonly next = output<void>();
  readonly back = output<void>();
  readonly skip = output<number>();
  readonly cancel = output<void>();
}
