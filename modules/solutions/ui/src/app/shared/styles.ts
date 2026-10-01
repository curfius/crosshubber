/** Shared shadow-DOM base styling for Solutions MFE elements. */
export const SHARED_STYLES = /* css */ `
  :host {
    display: block;
    height: 100%;
    font-family: system-ui, -apple-system, 'Segoe UI', sans-serif;
    color: var(--portal-text-primary, #1f2937);
    background: var(--portal-bg-base, #f8fafc);
    box-sizing: border-box;
  }
  *, *::before, *::after { box-sizing: inherit; }

  h2 {
    margin: 0 0 12px;
    font-size: 16px;
    font-weight: 600;
    color: var(--portal-text-primary, #111827);
  }
  h3 {
    margin: 0 0 6px;
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.04em;
    text-transform: uppercase;
    color: var(--portal-text-secondary, #4b5563);
  }
  .muted { color: var(--portal-text-muted, #6b7280); font-size: 13px; }

  .card {
    background: var(--portal-modal-bg, #ffffff);
    border: 1px solid var(--portal-modal-border, #e5e7eb);
    border-radius: 10px;
    padding: 16px;
  }
  .error { border-color: var(--portal-status-danger, #dc2626); color: var(--portal-status-danger, #dc2626); font-size: 13px; }

  table {
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;
  }
  th {
    text-align: left;
    font-weight: 600;
    padding: 6px 8px;
    border-bottom: 1px solid var(--portal-modal-border, #e5e7eb);
    color: var(--portal-text-secondary, #4b5563);
  }
  td {
    padding: 6px 8px;
    border-bottom: 1px solid var(--portal-bg-hover, #f1f5f9);
  }
  tr:last-child td { border-bottom: none; }

  .badge {
    display: inline-block;
    padding: 1px 8px;
    border-radius: 999px;
    font-size: 11px;
    font-weight: 600;
    border: 1px solid var(--portal-badge-border, #d1d5db);
    color: var(--portal-text-secondary, #4b5563);
    background: var(--portal-bg-hover, #f3f4f6);
  }
  .badge.ok { border-color: var(--portal-status-ok, #16a34a); color: var(--portal-status-ok, #16a34a); }
  .badge.warn { border-color: var(--portal-status-warning, #d97706); color: var(--portal-status-warning, #d97706); }
  .badge.danger { border-color: var(--portal-status-danger, #dc2626); color: var(--portal-status-danger, #dc2626); }
`;
