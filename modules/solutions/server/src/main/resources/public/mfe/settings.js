// Placeholder MFE surface for the adminSettings manifest entry — replaced by the Angular
// custom-element build (H2 UI slice). Self-contained: the portal loads MFE scripts as
// classic (non-module) script tags, so no import()/export here.
class SolutionsSettings extends HTMLElement {
  connectedCallback() {
    if (!this.shadowRoot) {
      this.attachShadow({ mode: 'open' });
    }
    this.shadowRoot.innerHTML = `
      <style>
        :host { display: block; font-family: system-ui, sans-serif; padding: 16px; }
        .box { border: 1px solid #ccc; border-radius: 8px; padding: 16px; }
        h2 { margin: 0 0 8px; font-size: 16px; }
        p { margin: 0; color: #555; font-size: 13px; }
      </style>
      <div class="box">
        <h2>Solutions — Settings</h2>
        <p>Agent, tools and datasource configuration arrive with the Angular MFE slice.
           Configuration is module-owned (env + module DB).</p>
      </div>`;
  }
}
if (!customElements.get('solutions-settings')) {
  customElements.define('solutions-settings', SolutionsSettings);
}
