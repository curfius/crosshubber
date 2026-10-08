import { describe, expect, it } from 'vitest';
import { groupAgentCatalog, toggleDisabled } from './user-settings-ai.helpers';
import type { AgentCatalogEntry } from '../../../core/ai-hub/ai-hub.service';

function entry(overrides: Partial<AgentCatalogEntry>): AgentCatalogEntry {
  return {
    modelName: 'mod_tool',
    name: 'tool',
    kind: 'remote',
    moduleKey: 'mod',
    description: 'does things',
    mutates: false,
    ...overrides,
  };
}

describe('groupAgentCatalog', () => {
  it('groups by module, sorted alphabetically with the portal builtin group first', () => {
    const groups = groupAgentCatalog([
      entry({ modelName: 'solutions_b', name: 'beta', moduleKey: 'solutions' }),
      entry({ modelName: 'mod_a', name: 'alpha', moduleKey: 'msgcenter' }),
      entry({ modelName: 'listModules', name: 'listModules', kind: 'builtin', moduleKey: null }),
      entry({ modelName: 'solutions_a', name: 'alpha_remote', moduleKey: 'solutions' }),
    ]);
    expect(groups.map((g) => g.moduleKey)).toEqual(['', 'msgcenter', 'solutions']);
    expect(groups[0].entries.map((e) => e.name)).toEqual(['listModules']);
    expect(groups[2].entries.map((e) => e.name)).toEqual(['alpha_remote', 'beta']);
  });

  it('sorts entries within a module by name', () => {
    const groups = groupAgentCatalog([
      entry({ modelName: 'mod_z', name: 'zeta', moduleKey: 'mod' }),
      entry({ modelName: 'mod_a', name: 'alpha', moduleKey: 'mod' }),
    ]);
    expect(groups[0].entries.map((e) => e.name)).toEqual(['alpha', 'zeta']);
  });

  it('returns an empty list for an empty catalog', () => {
    expect(groupAgentCatalog([])).toEqual([]);
  });
});

describe('toggleDisabled', () => {
  it('adds when disabling and removes when enabling', () => {
    const base = new Set(['mod_a']);
    const disabled = toggleDisabled(base, 'mod_b', false);
    expect(disabled).toEqual(new Set(['mod_a', 'mod_b']));
    const enabled = toggleDisabled(disabled, 'mod_a', true);
    expect(enabled).toEqual(new Set(['mod_b']));
  });

  it('does not mutate the input set', () => {
    const base = new Set(['mod_a']);
    toggleDisabled(base, 'mod_b', false);
    expect(base).toEqual(new Set(['mod_a']));
  });
});
