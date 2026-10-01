import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ViewEncapsulation } from '@angular/core';
import { SHARED_STYLES } from '../shared/styles';

/**
 * Solutions settings surface (module-owned). v1 render: module identity card.
 * The settings slice (agent config, LLM credentials, tool toggles, OneDrive
 * root, RAG limits) replaces this once the module exposes its settings REST.
 */
@Component({
  selector: 'sol-settings',
  encapsulation: ViewEncapsulation.ShadowDom,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      <h2>Solutions — Settings</h2>
      <div class="card">
        <p class="muted">
          Module-owned settings arrive with the settings slice: agent configuration
          (enabled, system prompt, provider credentials, max iterations), per-tool
          toggles with role narrowing, document-source container + credentials, and
          RAG limits. This surface is served by the Solutions module backend only.
        </p>
      </div>
    </div>
  `,
  styles: [
    SHARED_STYLES +
      /* css */ `
      .wrap { padding: 16px; }
    `,
  ],
})
export class SettingsComponent {}
