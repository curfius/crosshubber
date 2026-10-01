import { readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

/**
 * Turns the Angular application-builder output (ESM, hashed filename) into a
 * single classic-script IIFE bundle and installs it into the module server's
 * static resources, where the portal's MFE proxy serves it from.
 *
 * The portal loads MFE scripts as classic <script> tags (no type=module), so
 * the IIFE transform is what makes the Angular output mountable. The app has
 * no lazy chunks — one main-*.js is expected.
 */

const here = dirname(fileURLToPath(import.meta.url));
const uiRoot = dirname(here);
const distBrowser = join(uiRoot, 'dist', 'solutions-mfe', 'browser');
const outDir = join(uiRoot, '..', 'server', 'src', 'main', 'resources', 'public', 'mfe');
const OUT_NAME = 'solutions.js';

const candidates = readdirSync(distBrowser).filter(
  (f) => f.startsWith('main-') && f.endsWith('.js'),
);
if (candidates.length !== 1) {
  throw new Error(
    `expected exactly one main-*.js in ${distBrowser}, found: ${candidates.join(', ') || 'none'}`,
  );
}

await build({
  entryPoints: [join(distBrowser, candidates[0])],
  bundle: true,
  format: 'iife',
  target: 'es2022',
  minify: true,
  legalComments: 'none',
  outfile: join(outDir, OUT_NAME),
});

console.log(`MFE bundle written to ${join(outDir, OUT_NAME)}`);
