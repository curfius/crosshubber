#!/usr/bin/env node
/**
 * Copies the design system stylesheets from design-system/styles (source of
 * truth) into portal/ui/src/design-system/styles (vendored copy).
 *
 * Run manually after changing the design system:
 *   node scripts/sync-design-system.mjs
 *
 * The vendored copy is committed to git, so the portal Docker build is fully
 * self-contained and never needs the design-system directory.
 */
import { rmSync, cpSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const root = dirname(scriptDir); // scripts/ → repo root
const src = join(root, 'design-system', 'styles');
const dest = join(root, 'portal', 'ui', 'src', 'design-system', 'styles');

try {
  readdirSync(src);
} catch {
  console.error(`[sync-ds] Source not found: ${src} — nothing to do.`);
  process.exit(0);
}

rmSync(dest, { recursive: true, force: true });
cpSync(src, dest, { recursive: true });

const count = (function walk(dir) {
  return readdirSync(dir, { withFileTypes: true }).reduce(
    (n, e) => n + (e.isDirectory() ? walk(join(dir, e.name)) : 1),
    0,
  );
})(dest);

console.log(`[sync-ds] Copied ${count} files → portal/ui/src/design-system/styles`);
