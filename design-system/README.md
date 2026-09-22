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
├── showroom/
│   └── index.html             ← Interactive component showcase
├── README.md                  ← This file
└── DESIGN_SYSTEM.md           ← Full documentation
```

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
