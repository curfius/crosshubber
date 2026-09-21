import { describe, expect, it } from 'vitest';
import { EMBEDDED_LOAD_PATHS, embeddedModules } from './embedded-modules';

// The authoritative embedded-module catalog lives in the backend
// (portal/server/src/main/java/com/crosshubber/portal/bootstrap/EmbeddedCatalog.java);
// parity between it and this loader map is enforced by the reconciler and the
// contract-diff harness, not by a TS import. These tests pin the loader-map
// invariants that are checkable client-side.

describe('embedded module loader map', () => {
  it('derives EMBEDDED_LOAD_PATHS from the loader map keys', () => {
    expect([...EMBEDDED_LOAD_PATHS].sort()).toEqual(Object.keys(embeddedModules).sort());
  });

  it('loader keys are unique kebab-case load paths', () => {
    const keys = Object.keys(embeddedModules);
    expect(new Set(keys).size).toBe(keys.length);
    for (const key of keys) {
      expect(key).toMatch(/^[a-z0-9][a-z0-9-]{0,63}$/);
    }
  });

  it('every loader resolves to a dynamic import factory', () => {
    for (const load of Object.values(embeddedModules)) {
      expect(typeof load).toBe('function');
    }
  });
});
