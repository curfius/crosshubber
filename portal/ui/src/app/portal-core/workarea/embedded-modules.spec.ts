import { describe, expect, it } from 'vitest';
import { EMBEDDED_LOAD_PATHS, embeddedModules } from './embedded-modules';
import { EMBEDDED_CATALOG } from '../../../../../src/shared/embedded-catalog';

// The backend catalog (portal/src/shared/embedded-catalog.ts) is the single
// source of truth for embedded modules. The UI loader map declares which of
// those load paths this build can actually render. These tests fail the build
// the moment the two drift apart (e.g. a load path is renamed in one place only).

const catalogLoadPaths = EMBEDDED_CATALOG.flatMap((m) => m.entryPoints.map((e) => e.loadPath));

describe('embedded module catalog consistency', () => {
  it('has a UI loader for every catalog load path', () => {
    const missing = catalogLoadPaths.filter((lp) => !embeddedModules[lp]);
    expect(missing, `catalog load paths without a UI loader: ${missing.join(', ')}`).toEqual([]);
  });

  it('has a catalog entry for every UI loader', () => {
    const catalogSet = new Set(catalogLoadPaths);
    const orphans = Object.keys(embeddedModules).filter((lp) => !catalogSet.has(lp));
    expect(orphans, `UI loaders not declared in the catalog: ${orphans.join(', ')}`).toEqual([]);
  });

  it('derives EMBEDDED_LOAD_PATHS from the loader map keys', () => {
    expect([...EMBEDDED_LOAD_PATHS].sort()).toEqual(Object.keys(embeddedModules).sort());
  });

  it('has no duplicate load paths in the catalog', () => {
    expect(new Set(catalogLoadPaths).size).toBe(catalogLoadPaths.length);
  });

  it('has no duplicate entry keys within a catalog module', () => {
    for (const mod of EMBEDDED_CATALOG) {
      const keys = mod.entryPoints.map((e) => e.entryKey);
      expect(new Set(keys).size, `module "${mod.key}" has duplicate entryKeys`).toBe(keys.length);
    }
  });

  it('catalog module keys are unique and kebab-case', () => {
    const keys = EMBEDDED_CATALOG.map((m) => m.key);
    expect(new Set(keys).size).toBe(keys.length);
    for (const key of keys) {
      expect(key).toMatch(/^[a-z0-9][a-z0-9-]{0,63}$/);
    }
  });
});
