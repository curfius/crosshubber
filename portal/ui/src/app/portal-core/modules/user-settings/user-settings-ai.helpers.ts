import type { AgentCatalogEntry } from '../../../core/ai-hub/ai-hub.service';

// Pure helpers for the per-user AI settings page (phase 5): grouping the
// agent catalogue by owning module and applying enable/disable toggles.
// Kept dependency-free so they are directly unit-testable.

/** Tools/agents grouped by owning module; '' = portal builtins. */
export interface ModuleGroup {
  moduleKey: string;
  entries: AgentCatalogEntry[];
}

/** Groups by module key ('' for builtins), sorted by module then tool name. */
export function groupAgentCatalog(entries: AgentCatalogEntry[]): ModuleGroup[] {
  const map = new Map<string, AgentCatalogEntry[]>();
  for (const e of entries) {
    const key = e.moduleKey ?? '';
    const list = map.get(key) ?? [];
    list.push(e);
    map.set(key, list);
  }
  return Array.from(map.entries())
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([moduleKey, list]) => ({
      moduleKey,
      entries: list.sort((x, y) => x.name.localeCompare(y.name)),
    }));
}

/** Pure set toggle: enabled removes the model name, disabled adds it. */
export function toggleDisabled(
  current: ReadonlySet<string>,
  modelName: string,
  enabled: boolean,
): Set<string> {
  const next = new Set(current);
  if (enabled) next.delete(modelName);
  else next.add(modelName);
  return next;
}
