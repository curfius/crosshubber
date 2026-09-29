// Placeholder MFE surface — replaced by the Angular custom-element build (H3 UI slice).
// Classic script (no import/export): the portal loads MFE scripts as non-module script tags.
class StaffingRfps extends HTMLElement {
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
        <h2>Staffing — RFP Matcher</h2>
        <p>Backend live (domain + agent tools). The Angular MFE arrives in the H3 UI slice.</p>
      </div>`;
  }
}
if (!customElements.get('staffing-rfps')) {
  customElements.define('staffing-rfps', StaffingRfps);
}

class StaffingSettings extends HTMLElement {
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
        <h2>Staffing — Settings</h2>
        <p>Tool, matching and CV-library configuration arrive with the Angular MFE slice.</p>
      </div>`;
  }
}
if (!customElements.get('staffing-settings')) {
  customElements.define('staffing-settings', StaffingSettings);
}
