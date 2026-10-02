import { ChangeDetectionStrategy, Component } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideZonelessChangeDetection } from '@angular/core';
import { createCustomElement } from '@angular/elements';
import { RfpsComponent } from './app/rfps/rfps.component';
import { SettingsComponent } from './app/settings/settings.component';

// Invisible bootstrap root — exists only so the element registry gets a fully
// configured injector (zoneless scheduler, error handler, i18n-less defaults).
@Component({
  selector: 'stf-root',
  template: '',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
class RootComponent {}

async function defineElements(): Promise<void> {
  // bootstrapApplication requires a host element in the document (NG05104
  // otherwise) — mount a hidden one, bootstrap, then remove it; the
  // ApplicationRef/injector stay alive and power the custom elements.
  const host = document.createElement('stf-root');
  host.style.display = 'none';
  document.body.appendChild(host);
  const app = await bootstrapApplication(RootComponent, {
    providers: [provideZonelessChangeDetection()],
  });
  host.remove();
  const injector = app.injector;
  const elements = [
    ['staffing-rfps', RfpsComponent],
    ['staffing-settings', SettingsComponent],
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
