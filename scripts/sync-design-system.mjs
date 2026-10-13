#!/usr/bin/env node
/**
 * Copies the design system from the repo root into consumer workspaces so
 * every build stays self-contained (vendored copies are committed to git).
 *
 *  styles     design-system/styles     → portal/ui/src/design-system/styles
 *  components design-system/components → shared Angular UI kit sources:
 *    grid/*   → portal/ui/src/app/shared/components/data-grid/*     (selector/API shared)
 *    grid/*   → modules/talent-hub/ui/src/app/shared/grid/*
 *    i18n.ts  → portal/ui/src/app/shared/components/i18n.ts          (UI_I18N token)
 *    i18n.ts, mfe-i18n.ts → modules/talent-hub/ui/src/app/shared/*
 *
 * Consumer-owned files that live alongside vendored sources (e.g. the
 * portal's data-grid.component.spec.ts) are never touched — the plan only
 * syncs named files, never wholesale directory replacements.
 *
 * Run manually after changing the design system:
 *   node scripts/sync-design-system.mjs
 */
import { cpSync, mkdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const root = dirname(scriptDir); // scripts/ → repo root

/** { src } entry under design-system/ (file or dir) → { dest } path relative to repo root. */
const KIT_COPY_PLAN = [
  { src: 'styles', dest: 'portal/ui/src/design-system/styles' },
  { src: 'components/grid/data-grid.component.ts', dest: 'portal/ui/src/app/shared/components/data-grid/data-grid.component.ts' },
  { src: 'components/grid/data-grid.component.html', dest: 'portal/ui/src/app/shared/components/data-grid/data-grid.component.html' },
  { src: 'components/grid/data-grid.component.css', dest: 'portal/ui/src/app/shared/components/data-grid/data-grid.component.css' },
  { src: 'components/grid/grid-column.ts', dest: 'portal/ui/src/app/shared/components/data-grid/grid-column.ts' },
  { src: 'components/grid/grid-cell.directive.ts', dest: 'portal/ui/src/app/shared/components/data-grid/grid-cell.directive.ts' },
  { src: 'components/i18n.ts', dest: 'portal/ui/src/app/shared/components/i18n.ts' },
  { src: 'components/grid/data-grid.component.ts', dest: 'modules/talent-hub/ui/src/app/shared/grid/data-grid.component.ts' },
  { src: 'components/grid/data-grid.component.html', dest: 'modules/talent-hub/ui/src/app/shared/grid/data-grid.component.html' },
  { src: 'components/grid/data-grid.component.css', dest: 'modules/talent-hub/ui/src/app/shared/grid/data-grid.component.css' },
  { src: 'components/grid/grid-column.ts', dest: 'modules/talent-hub/ui/src/app/shared/grid/grid-column.ts' },
  { src: 'components/grid/grid-cell.directive.ts', dest: 'modules/talent-hub/ui/src/app/shared/grid/grid-cell.directive.ts' },
  { src: 'components/i18n.ts', dest: 'modules/talent-hub/ui/src/app/shared/i18n.ts' },
  { src: 'components/mfe-i18n.ts', dest: 'modules/talent-hub/ui/src/app/shared/mfe-i18n.ts' },
];

function existsFor(path) {
  try {
    statSync(path);
    return true;
  } catch {
    return false;
  }
}

let synced = 0;

for (const { src, dest } of KIT_COPY_PLAN) {
  const from = join(root, 'design-system', src);
  const to = join(root, dest);
  if (!existsFor(from)) {
    console.error(`[sync-ds] source not found, skipped: ${src}`);
    continue;
  }
  mkdirSync(dirname(to), { recursive: true });
  cpSync(from, to, { recursive: true });
  console.log(`[sync-ds] ${src} → ${dest}`);
  synced += 1;
}

if (synced === 0) {
  console.error('[sync-ds] nothing was synced — check the design-system directory.');
  process.exit(1);
}
console.log('[sync-ds] done. Vendored copies are committed — run the affected builds after syncing.');
