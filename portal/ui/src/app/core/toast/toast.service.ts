import { Injectable, signal } from '@angular/core';

/**
 * Minimal transient message surface for shell-level feedback (deep-link errors,
 * etc.). Zoneless-safe: signal writes schedule change detection; there is no
 * toast design-system component yet, so Shell renders `message()` itself with
 * portal tokens.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly message = signal<string | null>(null);

  private timer: ReturnType<typeof setTimeout> | null = null;

  show(message: string, durationMs = 5000): void {
    this.message.set(message);
    this.dismissAfter(durationMs);
  }

  dismiss(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.message.set(null);
  }

  private dismissAfter(durationMs: number): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = null;
      this.message.set(null);
    }, durationMs);
  }
}
