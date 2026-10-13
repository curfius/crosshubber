import { ChangeDetectionStrategy, Component } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideZonelessChangeDetection } from '@angular/core';
import { createCustomElement } from '@angular/elements';
import { CaptureComponent } from './app/capture/capture.component';
import { OngoingComponent } from './app/ongoing/ongoing.component';
import { TalentComponent } from './app/talent/talent.component';
import { AnalyticsComponent } from './app/analytics/analytics.component';
import { SettingsComponent } from './app/settings/settings.component';
import { UI_I18N } from './app/shared/i18n';
import { MfeI18n } from './app/shared/mfe-i18n';

// Invisible bootstrap root — exists only so the element registry gets a fully
// configured injector (zoneless scheduler, error handler, i18n-less defaults).
@Component({
  selector: 'th-root',
  template: '',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class RootComponent {}

async function defineElements(): Promise<void> {
  // bootstrapApplication requires a host element in the document (NG05104
  // otherwise) — mount a hidden one, bootstrap, then remove it; the
  // ApplicationRef/injector stay alive and power the custom elements.
  const host = document.createElement('th-root');
  host.style.display = 'none';
  document.body.appendChild(host);
  const app = await bootstrapApplication(RootComponent, {
    providers: [
      provideZonelessChangeDetection(),
      // Shared UI kit labels: render from the portal's public i18n bundles.
      { provide: UI_I18N, useExisting: MfeI18n },
    ],
  });
  host.remove();
  const injector = app.injector;
  const elements = [
    ['th-capture', CaptureComponent],
    ['th-ongoing', OngoingComponent],
    ['th-talent', TalentComponent],
    ['th-analytics', AnalyticsComponent],
    ['th-settings', SettingsComponent],
  ] as const;
  for (const [tag, type] of elements) {
    // Idempotent: the portal dedupes script loads, but re-install/reload paths
    // can execute this file twice in one document lifetime.
    if (!customElements.get(tag)) {
      customElements.define(tag, createCustomElement(type, { injector }));
    }
  }
}

void defineElements();
