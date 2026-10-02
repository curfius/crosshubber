import { ElementRef, inject } from '@angular/core';

/**
 * Clears the portal's MFE loading overlay: the outlet hides its spinner on the
 * `mfe:ready` event (bubbling, caught at the element), falling back to a 4 s
 * timeout for elements that never signal.
 *
 * Call `mfeReadyDispatcher()` in a field initializer (injection context — it
 * reads the host ElementRef), then invoke the returned function on first paint.
 */
export function mfeReadyDispatcher(): () => void {
  const el = inject(ElementRef).nativeElement as HTMLElement;
  return () => el.dispatchEvent(new CustomEvent('mfe:ready', { bubbles: true }));
}
