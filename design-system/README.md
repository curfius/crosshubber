# Design System

A framework-agnostic design system for the DuckChat portal and its embedded modules. Built on CSS custom properties with 16 switchable themes.

## Quick Start

### Import into your project

**Method 1: Copy the files**

```bash
cp -r design-system/styles/ your-project/styles/design-system/
```

```css
/* your-styles.css */
@import "./styles/design-system/index.css";
```

**Method 2: Relative path (monorepo)**

```css
@import "../design-system/styles/index.css";
```

**Method 3: npm link (local development)**

```bash
cd design-system && npm link
cd your-project && npm link design-system
```

```css
@import "design-system/styles/index.css";
```

**Portal (vendored copy)**

The portal uses a committed copy at `portal/ui/src/design-system/styles/`. After changing the design system, re-sync it from the repo root:

```bash
node backoffice-tools/scripts/sync-design-system.mjs
```

## File Structure

```
design-system/
├── styles/
│   ├── index.css              ← Import this single file
│   ├── design-tokens.css      ← CSS custom properties (tokens)
│   ├── base.css               ← Reset, typography, utilities
│   ├── components.css         ← .ds-* component classes
│   └── themes/
│       ├── index.css           ← Theme registry
│       ├── theme-dark-slate.css
│       ├── theme-dark-slate-bold.css
│       ├── theme-dark-slate-light.css
│       ├── theme-light.css
│       ├── theme-midnight-blue.css
│       ├── theme-forest.css
│       ├── theme-sunset.css
│       ├── theme-ocean.css
│       ├── theme-nord.css
│       ├── theme-dracula.css
│       ├── theme-monochrome.css
│       ├── theme-high-contrast.css
│       ├── theme-malo-1.css
│       ├── theme-malo-2.css
│       └── theme-malo-3.css
├── components/                 ← Shared Angular UI kit (see below)
│   ├── i18n.ts                 ← UI_I18N token + UiI18n contract
│   ├── mfe-i18n.ts             ← MFE-side label loader (portal bundles)
│   └── grid/                   ← Shared data-grid component
│       ├── data-grid.component.ts / .html / .css
│       ├── grid-column.ts
│       └── grid-cell.directive.ts
├── showroom/
│   └── index.html             ← Interactive component showcase
├── README.md                  ← This file
└── DESIGN_SYSTEM.md           ← Full documentation
```

## Shared Components Kit (`design-system/components/`)

Angular sources for components that must render identically in the portal shell
and inside module MFEs (custom elements mounted in the portal document).

**Contract**

- Important prerequisite for every host: the design system must be loaded
  *globally* (tokens + `components.css` + the active theme). Component css only
  covers grid-private styling (toolbar, sorting, filters, groups); the table
  markup uses the shared `.ds-table*` primitives.
- Host apps consuming MFEs mount them in the portal document with *emulated*
  encapsulation, so portal globals style module-authored markup too.
- i18n goes through the neutral `UI_I18N` token (`i18n.ts`). The portal binds
  it to `PortalUiI18n` (over `I18nService`) in `app.config.ts`; module MFEs
  bind `MfeI18n` (fetches the portal's public `/api/i18n/*` label bundles and
  listens for `portal:language`). Unbound hosts get the built-in English kit
  strings.

**Vendoring**

Consumers are wired into `scripts/sync-design-system.mjs`; run
`node scripts/sync-design-system.mjs` after editing kit sources. Vendored
copies are committed to git (self-contained Docker builds). Consumer-owned
files living alongside vendored sources (e.g. the portal's
`data-grid.component.spec.ts`) are never overwritten — the sync plan copies
named files only.

**Grid API (`app-data-grid`)**

- Inputs: `data`, `columns` (GridColumn: `key`, `header`, `headerKey?`,
  `accessor`, `format?`, `sortable?`, `searchable?`, `align?`, `width?`),
  `searchable`, `groupable`, `trackBy`, `emptyKey`, `selectable`,
  `bulkActions` (GridBulkAction: `id`, `label`, `labelKey?`, `danger?`),
  `clickable`.
- Outputs: `selectionChange` (selected row ids), `bulkAction`
  (`{id, ids}`), `rowClick` (clicked row, only when `clickable`).
  The bulk actions render as a button opening a dropdown menu (closes on
  pick, outside click, or Escape). `clearSelection()` resets the checkbox
  state after a bulk action.
- `gridCell` templates for custom cells (context: `$implicit` row, `value`).
- `[gridActions]` projection: buttons placed in the toolbar after the filter
  toggle (rendered whenever the toolbar renders, i.e.
  `searchable||groupable||showBulk`).

## Theme Switching

Set the `data-theme` attribute on `<html>`:

```html
<html data-theme="nord">
```

Available themes:
- `dark-slate` (default)
- `dark-slate-bold`
- `dark-slate-light`
- `light`
- `midnight-blue`
- `forest`
- `sunset`
- `ocean`
- `nord`
- `dracula`
- `monochrome`
- `high-contrast`
- `malo-1` (Purple + Gold)
- `malo-2` (Teal + Navy)
- `malo-3` (Purple + Teal)

## Usage with Tailwind CSS

The design system works alongside Tailwind. Use CSS custom properties in Tailwind's arbitrary value syntax:

```html
<div class="bg-[var(--portal-bg-surface)] text-[var(--portal-text-primary)]">
  Themed content
</div>

<button class="ds-btn ds-btn-primary">
  Uses design system button
</button>
```

## Showroom

Open `showroom/index.html` in any browser to see all components interactively. No build step required.

## License

Internal use only.
