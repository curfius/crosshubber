// Placeholder MFE surface — replaced by the Angular custom-element build (H2 UI slice).
// Registers the element declared in the manifest so the install -> load -> mount path
// is testable end to end before the real UI lands.
class SolutionsPipeline extends HTMLElement {
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
        <h2>Solutions — Project Pipeline</h2>
        <p>Backend live (domain + agent tools). The Angular MFE arrives in the H2 UI slice.</p>
      </div>`;
  }
}
customElements.define('solutions-pipeline', SolutionsPipeline);

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
customElements.define('solutions-settings', SolutionsSettings);
