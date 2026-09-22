# Design System - Complete Reference

## 1. Design Tokens

All visual values are defined as CSS custom properties on `:root` in `design-tokens.css`. Themes override these properties under `[data-theme="..."]` selectors.

### Token Categories

| Category | Prefix | Description |
|----------|--------|-------------|
| Background | `--portal-bg-*` | Surface hierarchy from darkest to lightest |
| Text | `--portal-text-*` | Text colors for different emphasis levels |
| Border | `--portal-border-*` | Border colors for different contexts |
| Accent | `--portal-accent-*` | Primary and secondary accent colors |
| Status | `--portal-status-*` | Success, warning, danger, info colors |
| Sidebar | `--portal-sidebar-*` | Sidebar-specific tokens |
| Toolbar | `--portal-toolbar-*` | Toolbar-specific tokens |
| Tab | `--portal-tab-*` | Tab bar tokens |
| Input | `--portal-input-*` | Form input tokens |
| Card | `--portal-card-*` | Card container tokens |
| Modal | `--portal-modal-*` | Modal dialog tokens |
| Button | `--portal-btn-*` | Button variant tokens |
| Shadow | `--portal-shadow-*` | Box shadow tokens |
| Typography | `--portal-font-*` | Font family, size, weight, line-height |
| Spacing | `--portal-space-*` | Spacing scale (4px base) |
| Radius | `--portal-radius-*` | Border radius tokens |
| Transition | `--portal-transition-*`, `--portal-duration-*` | Animation timing tokens |
| Z-Index | `--portal-z-*` | Stacking order tokens |
| Gradient | `--portal-gradient-*` | Gradient tokens |
| Palette | `--portal-palette-*` | Dynamic color palette for icons |

### Background Tokens

```
--portal-bg-base       #020617    Darkest background (page)
--portal-bg-surface    #0f172a    Card/sidebar surface
--portal-bg-elevated   #1e293b    Elevated elements (inputs, tabs)
--portal-bg-overlay    rgba()     Modal/tooltip backdrops
--portal-bg-hover      rgba()     Hover state backgrounds
--portal-bg-active     rgba()     Active/pressed backgrounds
--portal-bg-subtle     rgba()     Subtle background tinting
--portal-bg-glass      rgba()     Glassmorphism effect
```

### Text Tokens

```
--portal-text-primary    #f1f5f9    Headings, primary content
--portal-text-secondary  #cbd5e1    Body text
--portal-text-tertiary   #94a3b8    Secondary labels
--portal-text-muted      #64748b    Hints, placeholders
--portal-text-disabled   #475569    Disabled state text
--portal-text-inverse    #020617    Text on accent backgrounds
--portal-text-link       #818cf8    Link text
--portal-text-link-hover #a5b4fc    Link hover text
```

### Accent Tokens

```
--portal-accent-primary          #6366f1    Primary interactive elements
--portal-accent-primary-hover    #818cf8    Hover state
--portal-accent-primary-active   #4f46e5    Active/pressed state
--portal-accent-primary-subtle   rgba()     Subtle background tint
--portal-accent-primary-glow     rgba()     Glow effect

--portal-accent-secondary        #a855f7    Secondary accent (settings, registry)
--portal-accent-secondary-hover  #c084fc    Hover state
--portal-accent-secondary-active #9333ea    Active state
--portal-accent-secondary-subtle rgba()     Subtle background tint
```

### Status Tokens

```
--portal-status-success        #34d399    Success states
--portal-status-success-bg     rgba()     Success background
--portal-status-success-subtle rgba()     Success subtle tint

--portal-status-warning        #fbbf24    Warning states
--portal-status-warning-bg     rgba()     Warning background

--portal-status-danger         #f87171    Error/danger states
--portal-status-danger-bg      rgba()     Danger background

--portal-status-info           #60a5fa    Informational states
--portal-status-info-bg        rgba()     Info background
```

---

## 2. Component Classes

All components use the `.ds-` prefix. Combine base class with modifier classes.

### Buttons

**Base:** `.ds-btn`

| Class | Description |
|-------|-------------|
| `.ds-btn-primary` | Primary action (accent color) |
| `.ds-btn-secondary` | Secondary action (neutral) |
| `.ds-btn-danger` | Destructive action (red) |
| `.ds-btn-ghost` | Minimal emphasis (transparent) |
| `.ds-btn-accent` | Secondary accent color (purple) |
| `.ds-btn-gradient` | Gradient background with glow |
| `.ds-btn-sm` | Small size (24px height) |
| `.ds-btn-lg` | Large size (36px height) |
| `.ds-btn-icon` | Square icon-only button (28x28) |
| `.ds-btn-icon-sm` | Small icon button (24x24) |

**States:** `:disabled` attribute disables the button.

```html
<button class="ds-btn ds-btn-primary">Primary</button>
<button class="ds-btn ds-btn-secondary ds-btn-sm">Small Secondary</button>
<button class="ds-btn ds-btn-ghost ds-btn-icon">X</button>
```

### Inputs

| Class | Description |
|-------|-------------|
| `.ds-input` | Text input |
| `.ds-input-error` | Error state (red border) |
| `.ds-select` | Dropdown select |
| `.ds-textarea` | Multi-line text input |
| `.ds-checkbox` | Checkbox |
| `.ds-toggle` | Toggle switch |
| `.ds-toggle-active` | Toggle on state |
| `.ds-label` | Form label |
| `.ds-form-error` | Error message text |
| `.ds-form-hint` | Help text |
| `.ds-search` | Search input wrapper |

```html
<label class="ds-label">Name</label>
<input type="text" class="ds-input" placeholder="Enter name">
<div class="ds-form-error">Required field</div>

<div class="ds-toggle ds-toggle-active"></div>
```

### Cards

| Class | Description |
|-------|-------------|
| `.ds-card` | Card container |
| `.ds-card-hover` | Adds hover border effect |
| `.ds-card-header` | Card header section |
| `.ds-card-body` | Card body section |
| `.ds-card-footer` | Card footer section |

```html
<div class="ds-card ds-card-hover">
  <div class="ds-card-header">Title</div>
  <div class="ds-card-body">Content</div>
  <div class="ds-card-footer">Actions</div>
</div>
```

### Badges

| Class | Description |
|-------|-------------|
| `.ds-badge` | Base badge |
| `.ds-badge-default` | Neutral gray |
| `.ds-badge-success` | Green (active/online) |
| `.ds-badge-warning` | Yellow (pending/warning) |
| `.ds-badge-danger` | Red (error/offline) |
| `.ds-badge-info` | Blue (informational) |
| `.ds-badge-outline` | Border only |
| `.ds-badge-dot` | Prepends a colored dot |

```html
<span class="ds-badge ds-badge-success">Active</span>
<span class="ds-badge ds-badge-dot ds-badge-warning">Pending</span>
```

### Tabs

| Class | Description |
|-------|-------------|
| `.ds-tab-bar` | Tab bar container |
| `.ds-tab` | Individual tab |
| `.ds-tab-active` | Active tab |
| `.ds-tab-close` | Close button |
| `.ds-tab-dot` | Colored dot indicator |

```html
<div class="ds-tab-bar">
  <div class="ds-tab ds-tab-active">
    <span class="ds-tab-dot" style="background:var(--portal-accent-primary)"></span>
    Dashboard
  </div>
  <div class="ds-tab">
    Settings
    <span class="ds-tab-close">&times;</span>
  </div>
</div>
```

### Sidebar

| Class | Description |
|-------|-------------|
| `.ds-sidebar` | Sidebar container |
| `.ds-sidebar-expanded` | Full width (256px) |
| `.ds-sidebar-collapsed` | Collapsed width (64px) |
| `.ds-sidebar-item` | Navigation item |
| `.ds-sidebar-item-active` | Active item |
| `.ds-sidebar-item-icon` | Icon container (32x32) |
| `.ds-sidebar-item-label` | Text label |
| `.ds-sidebar-section-label` | Section heading |
| `.ds-sidebar-divider` | Horizontal separator |

**Collapsed variant**: combine `.ds-sidebar` + `.ds-sidebar-collapsed` (64px, centered items) or `.ds-sidebar-expanded` (256px). Widths come from the `--portal-sidebar-width-*` tokens; the base class carries a 200ms width transition for smooth expand/collapse. In collapsed mode the active item renders with a **full 1px accent ring** (instead of the left bar), section labels automatically collapse into short centered rules (`--portal-border-strong`), and `.ds-sidebar-divider` is hidden entirely (sections already emit their own rule). See the showroom **Sidebar** section for a side-by-side demo.

### Toolbar

| Class | Description |
|-------|-------------|
| `.ds-toolbar` | Toolbar container (40px height) |
| `.ds-toolbar-separator` | Vertical divider |
| `.ds-toolbar-label` | Label text |

### Modals

| Class | Description |
|-------|-------------|
| `.ds-modal-backdrop` | Backdrop overlay |
| `.ds-modal` | Modal dialog (480px max) |
| `.ds-modal-lg` | Large modal (640px max) |
| `.ds-modal-header` | Header with title and close |
| `.ds-modal-title` | Title text |
| `.ds-modal-close` | Close button |
| `.ds-modal-body` | Scrollable body |
| `.ds-modal-footer` | Footer with actions |

### Tables

| Class | Description |
|-------|-------------|
| `.ds-table-wrapper` | Table container with border |
| `.ds-table` | Table element |
| Headers use `<thead>`, `<th>` | Styled uppercase labels |
| Rows use `<tbody>`, `<tr>` | Hover effect on rows |

### Avatars

| Class | Description |
|-------|-------------|
| `.ds-avatar` | Avatar (gradient background) |
| `.ds-avatar-sm` | Small (24px) |
| `.ds-avatar-md` | Medium (32px) |
| `.ds-avatar-lg` | Large (48px) |

### Chat Bubbles

| Class | Description |
|-------|-------------|
| `.ds-chat-bubble` | Message bubble |
| `.ds-chat-bubble-user` | User message (right-aligned, accent) |
| `.ds-chat-bubble-assistant` | Assistant message (left-aligned, neutral) |
| `.ds-chat-typing` | Typing indicator container |
| `.ds-chat-typing-dot` | Animated bouncing dot |

### Alerts

| Class | Description |
|-------|-------------|
| `.ds-alert` | Alert container |
| `.ds-alert-info` | Blue informational |
| `.ds-alert-success` | Green success |
| `.ds-alert-warning` | Yellow warning |
| `.ds-alert-danger` | Red danger |

### Misc Components

| Class | Description |
|-------|-------------|
| `.ds-spinner` | Loading spinner |
| `.ds-spinner-sm` | Small spinner (16px) |
| `.ds-spinner-lg` | Large spinner (56px) |
| `.ds-empty-state` | Empty state container |
| `.ds-empty-state-icon` | Icon container |
| `.ds-empty-state-title` | Title text |
| `.ds-empty-state-description` | Description text |
| `.ds-color-dot` | Colored circle (32px) |
| `.ds-color-dot-sm` | Small colored circle (24px) |
| `.ds-color-pip` | Tiny colored dot (8px) |
| `.ds-dropdown` | Dropdown menu |
| `.ds-dropdown-item` | Menu item |
| `.ds-dropdown-divider` | Menu separator |
| `.ds-fieldset` | Form fieldset |
| `.ds-section-header` | Collapsible section header |
| `.ds-tree-node` | Tree/list item — see Tree Navigation below |

### Wizard Workflow - Vertical

Stacked panel wizard: only the current step is expanded; completed steps collapse with summary and can be re-expanded for editing; future steps are disabled. Connector line runs between indicators, hidden above the active step via `opacity:0` and `z-index` layering on indicators. Animation: `grid 0fr→1fr` on body, respecting `prefers-reduced-motion`.

| Class | Description |
|-------|-------------|
| `.ds-wv` | Container — `flex column gap-3`, draws vertical connector between indicators |
| `.ds-wv-step` | Panel card (no `overflow:hidden`; body uses grid animation) |
| `.ds-wv-step-active` | Current step — expanded, `border accent + shadow`, header `bg-subtle` |
| `.ds-wv-step-completed` | Done step — collapsed with green check, header is `<button aria-expanded>` + summary visible; add `.ds-wv-step-expanded` when re-opened |
| `.ds-wv-step-disabled` | Future step — muted `opacity 0.55`, header is `<div aria-disabled>`, no body |
| `.ds-wv-step-error` | Validation error — `border danger`, danger indicator; combine with `.ds-wv-step-expanded` to show `.ds-wv-step-error-msg` |
| `.ds-wv-step-optional` | Skippable step — add `.ds-wv-step-optional-badge` in title |
| `.ds-wv-step-header` | Clickable header (or `div` when disabled) — `gap-3`, `hover:bg-hover`, `focus-visible` ring |
| `.ds-wv-indicator` | Circle 28px — number / check / error; colors per state (active=accent, completed=success, error=danger, disabled=elevated) |
| `.ds-wv-step-titles` | Column wrapper for title/description/summary |
| `.ds-wv-step-title` | Title `sm medium primary` |
| `.ds-wv-step-description` | Description `xs muted` (hidden when active/expanded) |
| `.ds-wv-step-summary` | Collapsed summary `xs tertiary` — visible only in `completed` collapsed |
| `.ds-wv-step-optional-badge` | Badge 10px "Optional" (`bg-elevated`, `border-default`) |
| `.ds-wv-step-action` | "Edit" text on right — visible only in `completed` collapsed, `hover` reveals |
| `.ds-wv-chevron` | Chevron 16px — `rotate(180deg)` when `aria-expanded="true"` |
| `.ds-wv-step-body` | Animated grid `0fr→1fr` (`prefers-reduced-motion` disables) |
| `.ds-wv-step-body-inner` | `overflow:hidden` required by grid animation |
| `.ds-wv-step-content` | Content with `border-top` + `padding-4` |
| `.ds-wv-step-footer` | Action bar (`flex justify-end gap-2`, `bg-subtle`, `border-top`) |
| `.ds-wv-step-error-msg` | Error message (`danger-bg`, `border danger 20%`, `radius-md`) |

```html
<div class="ds-wv">
  <section class="ds-wv-step ds-wv-step-completed">
    <button class="ds-wv-step-header" aria-expanded="false" aria-controls="wv-s1">
      <span class="ds-wv-indicator">&#10003;</span>
      <span class="ds-wv-step-titles">
        <span class="ds-wv-step-title">Personal Info</span>
        <span class="ds-wv-step-summary">John Doe · john@example.com</span>
      </span>
      <span class="ds-wv-step-action">Edit</span>
      <span class="ds-wv-chevron">&#9662;</span>
    </button>
    <div id="wv-s1" class="ds-wv-step-body"><div class="ds-wv-step-body-inner"><div class="ds-wv-step-content">...</div></div></div>
  </section>
  <section class="ds-wv-step ds-wv-step-active">
    <button class="ds-wv-step-header" aria-expanded="true">...Address...</button>
    <div class="ds-wv-step-body"><div class="ds-wv-step-body-inner"><div class="ds-wv-step-content">...form...</div><div class="ds-wv-step-footer"><button class="ds-btn ds-btn-ghost ds-btn-sm">Back</button><button class="ds-btn ds-btn-primary ds-btn-sm">Continue</button></div></div></div>
  </section>
  <section class="ds-wv-step ds-wv-step-disabled" aria-disabled="true">
    <div class="ds-wv-step-header">...Payment...</div>
  </section>
</div>
```

### Wizard Workflow - Horizontal

Panel card with a scrollable steps bar at the top and full-width body below. Steps bar scrolls horizontally when content overflows. Active step body renders full-width below the bar. Same semantics as vertical: completed steps show summary, "Edit" re-expands, "Continue" advances.

| Class | Description |
|-------|-------------|
| `.ds-wh` | Panel container — card bg/border/radius, `flex column` |
| `.ds-wh-steps` | Scrollable bar — `flex row`, `overflow-x:auto`, `scrollbar-width:thin` |
| `.ds-wh-step` | Individual step header wrapper (`flex-shrink:0`, `min-width:120px`) |
| `.ds-wh-step-header` | Button/div — `flex column centered`, `gap-2`, `hover:bg-hover`, `focus-visible` ring |
| `.ds-wh-step-active` | Current step — accent indicator, `bg-subtle` header |
| `.ds-wh-step-completed` | Done step — success indicator, clickable |
| `.ds-wh-step-expanded` | Applied via JS when completed re-opened |
| `.ds-wh-step-disabled` | Future step — muted, no interaction |
| `.ds-wh-step-error` | Validation error — danger border + indicator |
| `.ds-wh-step-optional` | Skippable — add `.ds-wh-step-optional-badge` |
| `.ds-wh-indicator` | Circle 28px — number / check / error |
| `.ds-wh-step-titles` | Centered column for title/description/summary |
| `.ds-wh-step-title` | Title `sm medium`, `-webkit-line-clamp:2` |
| `.ds-wh-step-description` | Description `xs muted` |
| `.ds-wh-step-summary` | Collapsed summary `xs tertiary` — visible completed collapsed |
| `.ds-wh-step-optional-badge` | Badge 10px "Optional" |
| `.ds-wh-body` | Full-width body — animated grid `0fr→1fr` |
| `.ds-wh-body-open` | Expanded state |
| `.ds-wh-body-content` | Content with `border-top` + `padding-4` |
| `.ds-wh-step-footer` | Action bar (`flex justify-end gap-2`, `bg-subtle`, `border-top`) |
| `.ds-wh-step-error-msg` | Error message (`danger-bg`, `border danger 20%`, `radius-md`) |

```html
<div class="ds-wh">
  <div class="ds-wh-steps">
    <div class="ds-wh-step ds-wh-step-completed">
      <button class="ds-wh-step-header" aria-expanded="false">
        <span class="ds-wh-indicator">&#10003;</span>
        <span class="ds-wh-step-titles">
          <span class="ds-wh-step-title">Personal Info</span>
          <span class="ds-wh-step-summary">John Doe</span>
        </span>
      </button>
    </div>
    <div class="ds-wh-step ds-wh-step-active">
      <button class="ds-wh-step-header" aria-expanded="true">
        <span class="ds-wh-indicator">2</span>
        <span class="ds-wh-step-titles"><span class="ds-wh-step-title">Address</span></span>
      </button>
    </div>
    <div class="ds-wh-step ds-wh-step-disabled">
      <div class="ds-wh-step-header" aria-disabled="true">
        <span class="ds-wh-indicator">3</span>
        <span class="ds-wh-step-titles"><span class="ds-wh-step-title">Payment</span></span>
      </div>
    </div>
  </div>
  <div class="ds-wh-body ds-wh-body-open">
    <div class="ds-wh-body-inner">
      <div class="ds-wh-body-content">...active step form...</div>
      <div class="ds-wh-step-footer"><button>Back</button><button>Continue</button></div>
    </div>
  </div>
</div>
```

### Tree Navigation

Nested list navigation with optional built-in search header. Depth indentation is applied via inline `padding-left` per level.

| Class | Description |
|-------|-------------|
| `.ds-tree` | Container — surface chrome (padding, bg-surface, border, radius) |
| `.ds-tree-unboxed` | Variant modifier — strips padding/background/border for embedding inside panels or cards |
| `.ds-tree-header` | Header row — compose with `.ds-search` + `.ds-search-icon` + `.ds-input` for the built-in search |
| `.ds-tree-node` | Selectable row (transparent 2px left border, hover bg) |
| `.ds-tree-node-selected` | Selected row (accent left border, active bg, medium weight). Alias: `.ds-tree-node-active` |
| `.ds-tree-chevron` | Group chevron — add `.open` to rotate 90° |
| `.ds-tree-label` | Truncateable row title |
| `.ds-tree-meta` | Right-aligned 10px sub-line (counts, hints) |
| `.ds-tree-dot` | Status dot (set color inline) |
| `.ds-tree-action` | Ghost icon action — hidden until row hover, hover turns danger |
| `.ds-tree-footer` | Optional actions row at the bottom (border-top separator). Style child buttons with `.ds-tree-node` for a compact muted "+ Add item" affordance |

```html
<div class="ds-tree">
  <div class="ds-tree-header ds-search">
    <svg class="ds-search-icon size-3.5">…</svg>
    <input type="text" class="ds-input" placeholder="Filter tree...">
  </div>
  <button class="ds-tree-node">
    <span class="ds-tree-chevron open">…</span>
    <span class="ds-tree-label">Providers</span>
    <span class="ds-tree-meta">4</span>
  </button>
  <div style="padding-left:14px">
    <button class="ds-tree-node ds-tree-node-selected" style="padding-left:12px">
      <span class="ds-tree-dot" style="background:var(--portal-status-success)"></span>
      <span class="ds-tree-label">Anthropic</span>
      <span class="ds-tree-meta">3 models</span>
      <span class="ds-tree-action">…</span>
    </button>
  </div>
  <div class="ds-tree-footer">
    <button class="ds-tree-node">
      <svg width="10" height="10" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M8 3v10M3 8h10"/></svg>
      <span class="ds-tree-label">Add provider</span>
    </button>
  </div>
</div>
```

---

## 3. Theme System

### How Themes Work

1. `design-tokens.css` defines default values on `:root`
2. Each `theme-*.css` file overrides those values under `[data-theme="theme-name"]`
3. The default theme (`dark-slate`) also sets `:root` to ensure fallback
4. Switch themes by setting `data-theme` on `<html>`:

```javascript
document.documentElement.setAttribute('data-theme', 'nord');
```

### Available Themes

| Theme | Character | Best For |
|-------|-----------|----------|
| `dark-slate` | Slate grays, indigo accents | Default, matches current portal |
| `dark-slate-bold` | Same colors, heavier weights | Emphasis, bold presence |
| `dark-slate-light` | Same colors, lighter weights | Refined, airy feel |
| `light` | White/gray, blue accents | Daytime use, bright environments |
| `midnight-blue` | Deep navy, cyan accents | Professional, focused work |
| `forest` | Dark green, earthy tones | Nature-inspired, calming |
| `sunset` | Warm orange/red, amber accents | Energetic, bold interfaces |
| `ocean` | Teal/cyan on deep blue | Fresh, aquatic feel |
| `nord` | Polar night/snow storm palette | Clean, Scandinavian aesthetic |
| `dracula` | Vibrant purple/pink/cyan | Expressive, developer-friendly |
| `monochrome` | Pure grayscale | Minimalist, no color distraction |
| `high-contrast` | Maximum contrast, yellow accent | WCAG AAA accessibility |
| `malo-1` | Deep purple & gold luxury | Premium, elegant interfaces |
| `malo-2` | Teal & navy clinical | Professional, healthcare |
| `malo-3` | Purple & teal modern | Bold, contemporary energy |
| `malo-light` | Navy, teal & gold on white | Clean, professional, healthcare |
| `malo-dark` | Navy, teal & gold on dark | Professional, healthcare, dark mode |

### Creating a New Theme

1. Copy `theme-dark-slate.css` as a template
2. Rename to `theme-yourname.css`
3. Change the selector to `[data-theme="yourname"]`
4. Override all token values
5. Add `@import url("./theme-yourname.css")` to `themes/index.css`

---

## 4. Integration Guide

### Angular + Tailwind (Portal)

The portal uses a **vendored copy** of the stylesheets at `portal/ui/src/design-system/styles/` (committed to git), so its Docker build is fully self-contained.

After changing the design system, sync the copy from the repo root:

```bash
node backoffice-tools/scripts/sync-design-system.mjs
```

In `portal/ui/src/styles.css`:

```css
@import "./design-system/styles/design-tokens.css";
@import "./design-system/styles/base.css";
@import "./design-system/styles/components.css";
/* + one @import per theme file under ./design-system/styles/themes/ */
@import "tailwindcss";

@source "./src/app";
```

In templates, use both Tailwind utilities and design tokens:

```html
<!-- Tailwind for layout -->
<div class="flex items-center gap-4 p-6">
  <!-- Design tokens for theming -->
  <div class="bg-[var(--portal-bg-surface)] text-[var(--portal-text-primary)]">
    Content
  </div>
  <!-- Or use .ds-* classes -->
  <button class="ds-btn ds-btn-primary">Action</button>
</div>
```

### Standalone HTML (Showroom / MFEs)

```html
<link rel="stylesheet" href="path/to/design-system/styles/index.css">
```

### Web Components (Shadow DOM)

Import the CSS inside the shadow root:

```javascript
connectedCallback() {
  this.attachShadow({ mode: 'open' });
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = '/path/to/design-system/styles/index.css';
  this.shadowRoot.appendChild(link);
}
```

### JavaScript Theme Switching

```javascript
// Get available themes
const themes = [
  'dark-slate', 'dark-slate-bold', 'dark-slate-light', 'light',
  'midnight-blue', 'forest', 'sunset', 'ocean', 'nord', 'dracula',
  'monochrome', 'high-contrast', 'malo-1', 'malo-2', 'malo-3', 'malo-light', 'malo-dark'
];

// Apply theme
function setTheme(name) {
  document.documentElement.setAttribute('data-theme', name);
  localStorage.setItem('portal-theme', name);
}

// Restore saved theme
const saved = localStorage.getItem('portal-theme');
if (saved) setTheme(saved);
```

---

## 5. Component Behavior Specifications

### Shell (Main Layout)

The Shell is the top-level layout component containing the sidebar and main content area.

- **Sidebar**: Starts collapsed (64px), expands on hover or when pinned
- **Main area**: Fills remaining width, contains toolbar + work area
- **Responsive**: Sidebar overlays on mobile, no permanent dock

### Sidebar

- **Collapsed state**: Shows only icons (64px width)
- **Expanded state**: Shows icons + labels (256px width)
- **Pin toggle**: Click pin icon to keep sidebar permanently expanded
- **Sections**: Home, Workspaces, Apps (tree), Services
- **Active states**: Highlighted background + accent text for active items
- **Hover**: Background change on hover for all interactive items
- **Favorites**: Star icon shown on favorited apps
- **Expand/collapse**: Group nodes toggle their children

### Tab Bar

- **Active tab**: Solid background, border-bottom accent color, white text
- **Inactive tabs**: Transparent background, muted text, hover shows subtle bg
- **Close button**: Appears on hover (or always in edit mode), X icon
- **Drag reorder**: CDK drag-drop in edit mode
- **Scroll arrows**: Appear when tabs overflow container width
- **"+" button**: Opens "Add app" search dialog
- **Close all except dashboard**: Clears all non-dashboard tabs

### Split Layout

- **Divider**: 4px wide/tall, draggable in edit mode
- **Edit mode**: Divider glows indigo on hover, cursor changes to resize
- **Read-only mode**: Divider is dimmed, non-interactive
- **Keyboard**: Arrow keys nudge divider by 2%

### Module Outlet

- **Loading state**: Spinner overlay with "Loading {name}..." text
- **Embedded**: Angular component rendered via ViewContainerRef
- **MFE**: Custom element created, `mount(context)` called
- **iframe**: Standard iframe with sandbox attributes
- **Error**: Falls back to error display

### Login Page

- **Layout**: Centered card on dark background
- **Decorative**: Three blurred gradient orbs in background
- **Card**: Glassmorphism effect (backdrop-blur, semi-transparent bg)
- **Form**: Username + password inputs with focus ring
- **Button**: Gradient primary button, shows spinner when busy
- **Error**: Red alert banner above form

### Dashboard

- **Grid layout**: Responsive grid (3-8 columns based on viewport)
- **Sections**: My Workspaces, My Apps, All Applications
- **Collapsible**: Each section has a toggle chevron
- **App cards**: Icon (color dot + initial), name, type badge
- **Hover**: Border changes to accent color
- **Favorites**: Star toggle on each app card
- **Search**: Filters all sections simultaneously

### Settings

- **Layout**: Left sidebar (256px) + main content area
- **Sidebar**: Tree navigation with groups and leaf items
- **Active item**: Left border accent, white text, elevated bg
- **Admin indicator**: Warning badge when non-admin

### AI Assistant

- **Layout**: Full-height flex column (header, messages, input)
- **Messages**: Scrollable container, auto-scroll to bottom
- **User messages**: Right-aligned, accent background
- **Assistant messages**: Left-aligned, neutral background
- **Typing indicator**: Three bouncing dots
- **Input**: Textarea with send button, auto-resize
- **Model selector**: Dropdown above input

### Quick Chat (Modal)

- **Position**: Fixed bottom-right corner
- **Size**: 384px wide, 500px tall
- **Overlay**: Semi-transparent backdrop
- **Header**: Title + close button
- **Same message pattern** as AI Assistant

---

## 6. Accessibility

- All interactive elements have visible focus rings (`--portal-border-focus`)
- Color contrast meets WCAG AA minimum (4.5:1 for text)
- `high-contrast` theme meets WCAG AAA (7:1 for text)
- Toggle switches have `role="switch"` and `aria-checked`
- Modal close buttons have `aria-label`
- Tab navigation supports keyboard (arrow keys for divider)
- Screen reader text available via `.sr-only` class
- Reduced motion: animations respect `prefers-reduced-motion`

---

## 7. File Reference

| File | Lines | Purpose |
|------|-------|---------|
| `design-tokens.css` | ~200 | All CSS custom properties |
| `base.css` | ~200 | Reset, typography, utilities, animations |
| `components.css` | ~700 | All .ds-* component classes |
| `themes/*.css` | ~100 each | Theme token overrides |
| `index.css` | ~15 | Entry point imports |
| `showroom/index.html` | ~400 | Interactive component showcase |
| `DESIGN_SYSTEM.md` | This file | Full documentation |
| `README.md` | ~80 | Quick start guide |
