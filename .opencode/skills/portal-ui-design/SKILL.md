---
name: portal-ui-design
description: Use when designing or implementing UI in the Crosshubber portal. Covers CSS-only design system tokens, .ds-* component classes, theme system (18 themes), zoneless Angular patterns, signals-first architecture, and component composition rules.
metadata:
  opencode/autoinvoke: "false"
---

# Portal UI Design System Skill

## Overview

The Crosshubber portal uses a **CSS-only design system** (no JS components) built on CSS custom properties (design tokens) and `.ds-*` component classes. The system supports 18 themes via CSS custom property overrides. Angular components are standalone, signals-first, zoneless, and use `OnPush` change detection.

## When to Use This Skill

- Designing or implementing any UI in the portal (components, pages, modules)
- Adding new components or extending existing ones
- Ensuring consistency with the design system
- When no existing `.ds-*` component or design token fits the need — **propose a new component or token to be added to the design system** (document the gap, suggest token/class name following conventions, outline the API). Do not create ad-hoc one-off styles or inline CSS.

## Design Tokens Reference

All tokens are defined in `portal/ui/src/design-system/styles/design-tokens.css` as CSS custom properties (`--portal-*`).

### Colors

**Background Hierarchy** (darkest → lightest):
- `--portal-bg-base` (#020617)
- `--portal-bg-surface` (#0f172a)
- `--portal-bg-elevated` (#1e293b)
- `--portal-bg-overlay` (rgba(0,0,0,0.70))
- `--portal-bg-hover`, `--portal-bg-active`, `--portal-bg-subtle`, `--portal-bg-glass`

**Text**:
- `--portal-text-primary` (#f1f5f9)
- `--portal-text-secondary` (#cbd5e1)
- `--portal-text-tertiary` (#94a3b8)
- `--portal-text-muted` (#64748b)
- `--portal-text-disabled` (#475569)
- `--portal-text-inverse`, `--portal-text-link`, `--portal-text-link-hover`

**Borders**:
- `--portal-border-default` (#1e293b)
- `--portal-border-subtle` (#334155)
- `--portal-border-strong` (#475569)
- `--portal-border-focus` (#6366f1)
- `--portal-border-input` (#334155)

**Accent Colors**:
- Primary (Indigo): `--portal-accent-primary` (#6366f1), hover/active/subtle/glow variants
- Secondary (Purple): `--portal-accent-secondary` (#a855f7), variants

**Status Colors** (each with subtle/bg variants):
- Success: `--portal-status-success` (#34d399)
- Warning: `--portal-status-warning` (#fbbf24)
- Danger: `--portal-status-danger` (#f87171)
- Info: `--portal-status-info` (#60a5fa)

### Spacing (4px base unit)
`--portal-space-0` through `--portal-space-16` (0, 2px, 4px, 6px, 8px, 10px, 12px, 14px, 16px, 20px, 24px, 28px, 32px, 36px, 40px, 48px, 64px)

### Typography
- Font families: `--portal-font-family-sans` (Inter/Gilroy), `--portal-font-family-display` (Gilroy), `--portal-font-family-mono` (Fragment Mono)
- Sizes: `--portal-font-size-xs` (11px) through `--portal-font-size-3xl` (24px)
- Weights: normal (400), medium (500), semibold (600), bold (700)
- Line heights: tight (1.25), normal (1.5), relaxed (1.75)

### Border Radius
`--portal-radius-sm` (4px), `md` (6px), `lg` (8px), `xl` (12px), `2xl` (16px), `full` (9999px)

### Shadows
`--portal-shadow-sm` through `--portal-shadow-2xl`, plus `--portal-shadow-glow`

### Transitions
`--portal-transition-fast` (100ms), `normal` (150ms), `slow` (200ms), `slower` (300ms)

### Z-Index Hierarchy
- Sidebar: 30
- Dropdown: 40
- Sticky: 50
- Modal backdrop: 50, Modal: 51
- Popover: 52
- Tooltip: 53

### Gradients
`--portal-gradient-primary`, `--portal-gradient-brand`, `--portal-gradient-glass`

### Theme System
18 themes in `portal/ui/src/design-system/styles/themes/` (e.g., `theme-dark-slate.css`, `theme-carris-light.css`, `theme-ocean.css`, `theme-sunset.css`, etc.). Themes override design tokens via CSS custom properties. `theme-carris-light/dark` are the defaults.

## Component Classes (`.ds-*`)

All components in `portal/ui/src/design-system/styles/components.css`.

### Buttons (`.ds-btn`)
- Base: `.ds-btn` (inline-flex, gap, padding, border-radius, transition)
- Variants: `.ds-btn-primary`, `.ds-btn-secondary`, `.ds-btn-danger`, `.ds-btn-ghost`, `.ds-btn-accent`, `.ds-btn-gradient`
- Sizes: `.ds-btn-sm`, `.ds-btn-lg`, `.ds-btn-icon`, `.ds-btn-icon-sm`
- Intent icons: `.ds-btn-icon-danger`, `.ds-btn-icon-success`, `.ds-btn-icon-warning`
- Gradient: `.ds-btn-gradient` (with glow shadow)

### Inputs & Forms
- `.ds-input` (text, focus ring, disabled state, error state `.ds-input-error`)
- `.ds-select` (custom arrow, focus, disabled)
- `.ds-textarea` (resize vertical, min-height)
- `.ds-checkbox` (accent-color), `.ds-toggle` (animated)
- `.ds-label` (xs, medium, tertiary color)
- Form groups: `.ds-form-group`, `.ds-form-hint`, `.ds-form-error`
- Fieldset: `.ds-fieldset`, `.ds-fieldset-legend`

### Cards
- `.ds-card` (bg, border, radius, hover variant `.ds-card-hover`)
- Sections: `.ds-card-header`, `.ds-card-body`, `.ds-card-footer`

### Badges (`.ds-badge`)
- Variants: `.ds-badge-default`, `.ds-badge-success`, `.ds-badge-warning`, `.ds-badge-danger`, `.ds-badge-info`, `.ds-badge-outline`
- Dot variant: `.ds-badge-dot` (with `::before` dot)

### Tabs (`.ds-tab-bar`, `.ds-tab`)
- Active state: `.ds-tab-active` (accent bottom border)
- Close button: `.ds-tab-close`
- Dot indicator: `.ds-tab-dot`

### Sidebar (`.ds-sidebar`)
- Collapsed/expanded: `.ds-sidebar-collapsed` (64px), `.ds-sidebar-expanded` (256px)
- Items: `.ds-sidebar-item` (hover/active states, icon + label)
- Section labels: `.ds-sidebar-section-label`
- Collapsed mode: 40px tiles, section labels become rules

### Toolbar (`.ds-toolbar`)
- Height: 40px, separator: `.ds-toolbar-separator`, labels

### Modals (`.ds-modal`, `.ds-modal-backdrop`)
- Sizes: `.ds-modal` (max 480px), `.ds-modal-lg` (640px)
- Parts: header, title, close, body, footer
- Animation: `ds-fade-in-scale`

### Tables (`.ds-table`, `.ds-table-wrapper`)
- Sticky header, hover rows, bordered cells

### Avatar (`.ds-avatar`)
- Sizes: `sm` (24px), `md` (32px), `lg` (48px)
- Gradient background, white text

### Chat Bubbles (`.ds-chat-bubble`)
- User/assistant variants with asymmetric border-radius
- Markdown support: `.ds-chat-md` (headings, lists, code, blockquotes, tables)
- Typing indicator: `.ds-chat-typing` with bouncing dots

### Dropdowns (`.ds-dropdown`)
- Absolute positioned, shadow, animation
- Items with hover states, dividers

### Alerts (`.ds-alert`)
- Variants: info, success, warning, danger (with subtle bg/border/color)

### Form Helpers
- `.ds-fieldset`, `.ds-fieldset-legend`
- `.ds-form-group`, `.ds-form-hint`, `.ds-form-error`

### Split Pane Divider (`.ds-split-divider`)
- Horizontal/vertical, active state with accent color

### Color Dots (`.ds-color-dot`)
- Sizes: `sm` (24px), `md` (32px), `lg` (40px)
- Color pips: `.ds-color-pip` (8px)

### App Cards (`.ds-app-card`)
- Dashboard grid items with icon, name, type badge

### Search Input (`.ds-search`)
- Icon prefix, padded input

### Section Header (`.ds-section-header`)
- Collapsible with chevron, count badge

### Tree Navigation (`.ds-tree`)
- Unboxed variant for embedding
- Nodes with chevron, label, meta, actions
- Selected/active/error states
- Footer for actions

### Wizards
- Vertical (`.ds-wv-*`): collapsible steps with connector line, animated body (grid 0fr→1fr)
- Horizontal (`.ds-wh-*`): scrollable steps bar with connector, full-width body
- Step states: active, completed, error, disabled
- Indicators with state colors, titles, summaries, actions

## Shared UI Kit (`design-system/components/`)

Workspace-agnostic Angular sources shared by the portal shell and module MFEs
(source of truth; vendored copies are synced into consumers by
`scripts/sync-design-system.mjs` and committed to git). Edit the kit source,
then run the sync; never edit vendored copies directly.

- **`i18n.ts`** — `UI_I18N` InjectionToken + `UiI18n` contract
  (`t` / `formatDate` / `formatNumber` / `locale`). Portal binds
  `PortalUiI18n` (adapter over `I18nService`) in `app.config.ts`; MFEs bind
  `MfeI18n` (public `/api/i18n/*` bundles + `portal:language` events).
- **`mfe-i18n.ts`** — MFE-side label implementation (portal public bundles,
  `contentVersion`-keyed localStorage cache).
- **`grid/`** — shared data grid (`app-data-grid`): sortable columns, search,
  group-by, per-column filter row, empty state (`emptyKey` input), custom cell
  templates via `gridCell`, and an actions zone — `<button gridActions>…`
  renders in the toolbar after the filter toggle.

Kit contract: every host loads the design system globally (tokens +
`components.css`); portal-hosted MFE elements use emulated encapsulation so
portal globals (`.ds-*` classes, tokens, themes) style module-authored markup.
Component css only covers component-private layout (toolbar, sort, filters),
never table/card/button primitives.

## Angular Implementation Patterns

### Zoneless & Signals
- **No Zone.js** — change detection only from signals/DOM events
- Use `OnPush` everywhere (app-wide direction)
- Signals: `signal()`, `computed()`, `effect()`, `input()`, `output()`, `viewChild()`
- **No** `ChangeDetectorRef.detectChanges()` — rely on signals
- Legacy decorators (`@Input`, `@Output`, `@ViewChild`) are legacy debt — use signals API

### Standalone Components
- All components are standalone
- Lazy-loaded where possible (route entry points)
- `Shell` must not eagerly import lazily-registered embedded modules

### HTTP & Data Fetching
- No `HttpClient` module — services use `fetch`
- Shared `apiFetch` helper in `src/app/core/http/`
- Error policy: throw or document-and-return-default; never silently swallow `!res.ok`

### i18n
- Runtime i18n is DB-backed (`I18nService`, localStorage cache keyed by `contentVersion`)
- Templates call `i18n.t('key')`
- Hardcoded English strings in templates are bugs
- For MFE elements: bind `UI_I18N` to `MfeI18n` at bootstrap and call labels
  through it (same DB-backed catalog — module keys live there too, e.g.
  `talenthub.*`)

### CSS Usage
- Use design tokens (`var(--portal-*)`) — never raw hex colors
- Use `.ds-*` component classes — do not create custom component styles
- Global styles: `design-tokens.css`, `components.css`, `base.css`, theme files
- Component styles only for layout/structure, not visual design

## Proposing New Design System Components

**When no existing `.ds-*` component or design token fits the need:**

1. **Document the gap** — What UI need is unmet?
2. **Propose the token/class** — Suggest name following conventions:
   - Tokens: `--portal-<category>-<property>` (e.g., `--portal-tooltip-bg`)
   - Components: `.ds-<component>` (e.g., `.ds-tooltip`, `.ds-tooltip-trigger`)
3. **Outline the API** — Token values or component class structure (base + variants + sizes)
4. **Follow conventions** — Use existing token categories, spacing scale, naming patterns
5. **PR to design system** — Add tokens to `design-tokens.css`, components to `components.css`

**Never** create ad-hoc one-off styles, inline styles, or component-specific CSS for visual design. All visual design lives in the design system.

## Theme System
- 18 themes in `styles/themes/` (index.css imports active theme)
- Themes override `:root` design tokens
- Default: `theme-carris-light` / `theme-carris-dark`
- Themes only override tokens — never add new CSS rules

## File Locations
- Tokens: `portal/ui/src/design-system/styles/design-tokens.css`
- Components: `portal/ui/src/design-system/styles/components.css`
- Base: `portal/ui/src/design-system/styles/base.css`
- Themes: `portal/ui/src/design-system/styles/themes/*.css`
- Theme index: `portal/ui/src/design-system/styles/themes/index.css`

## Key Rules Summary
- **Use tokens, not raw values** — `var(--portal-accent-primary)` not `#6366f1`
- **Use `.ds-*` classes** — not custom component CSS
- **No inline styles** for visual design
- **Signals-first, OnPush, zoneless** — no Zone.js, no manual change detection
- **Signals API** — `input()`, `output()`, `signal()`, `computed()`, `effect()`
- **No `HttpClient`** — use `fetch` via `apiFetch`
- **i18n via `i18n.t()`** — no hardcoded English in templates
- **Propose to design system** when nothing fits — don't create one-off styles