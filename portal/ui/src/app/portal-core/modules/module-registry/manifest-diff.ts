import type { ManifestContentEntry, PortalModuleManifest } from '../../../core/models';

export interface PreviewField {
  key: string;
  label: string;
  oldValue?: unknown;
  newValue: unknown;
  changed?: boolean;
}

export interface PreviewItem {
  action: 'create' | 'update' | 'unchanged';
  key: string;
  fields: PreviewField[];
}

export interface PreviewSection {
  title: string;
  action: 'create' | 'update' | 'unchanged';
  fields?: PreviewField[];
  items?: PreviewItem[];
}

/** Pure diff/preview engine for module manifests (no Angular dependencies). */

const FIELD_SKIP = new Set(['key', 'type']);
const DIFF_SKIP = new Set(['key', 'type', 'description']);

function objectToFields(obj: Record<string, unknown> | object): PreviewField[] {
  const o = obj as Record<string, unknown>;
  return Object.entries(o)
    .filter(([k, v]) => !FIELD_SKIP.has(k) && v !== undefined)
    .map(([k, v]) => ({ key: k, label: k, newValue: v }));
}

function diffObjects(old: Record<string, unknown> | object, cur: Record<string, unknown> | object): PreviewField[] {
  const fields: PreviewField[] = [];
  const o = old as Record<string, unknown>;
  const c = cur as Record<string, unknown>;
  const allKeys = new Set([...Object.keys(o), ...Object.keys(c)]);
  for (const k of allKeys) {
    if (DIFF_SKIP.has(k)) continue;
    const ov = o[k];
    const nv = c[k];
    const changed = JSON.stringify(ov) !== JSON.stringify(nv);
    fields.push({ key: k, label: k, oldValue: ov, newValue: nv, changed });
  }
  return fields;
}

function computeSectionAction(items: PreviewItem[]): 'create' | 'update' | 'unchanged' {
  if (items.every((i) => i.action === 'unchanged')) return 'unchanged';
  if (items.every((i) => i.action === 'create')) return 'create';
  return 'update';
}

interface KeyedEntry {
  key: string;
  name?: string;
}

/** Shared diff for any key-collared collection (content entries, roles). */
function diffKeyed<T extends KeyedEntry>(oldList: T[], newList: T[], deletedLabel: string): PreviewItem[] {
  const oldMap = new Map(oldList.map((e) => [e.key, e]));
  const newMap = new Map(newList.map((e) => [e.key, e]));
  const allKeys = new Set([...oldMap.keys(), ...newMap.keys()]);
  const items: PreviewItem[] = [];
  for (const k of allKeys) {
    const ov = oldMap.get(k);
    const nv = newMap.get(k);
    if (!ov) items.push({ action: 'create', key: k, fields: objectToFields(nv!) });
    else if (!nv) {
      items.push({
        action: 'update',
        key: k,
        fields: [{ key: '_deleted', label: deletedLabel, oldValue: ov.name, newValue: null, changed: true }],
      });
    } else {
      const diffs = diffObjects(ov, nv);
      items.push({ action: diffs.some((f) => f.changed) ? 'update' : 'unchanged', key: k, fields: diffs });
    }
  }
  return items;
}

const CONTENT_GROUPS: Array<{ key: keyof PortalModuleManifest['content'] & string; label: string }> = [
  { key: 'applications', label: 'Applications' },
  { key: 'features', label: 'Features' },
  { key: 'adminSettings', label: 'Admin Settings' },
  { key: 'userSettings', label: 'User Settings' },
];

function buildDiffSections(oldManifest: PortalModuleManifest | null, newManifest: PortalModuleManifest): PreviewSection[] {
  const sections: PreviewSection[] = [];

  // Root properties
  if (oldManifest) {
    const rootFields: PreviewField[] = [
      { key: 'manifestVersion', label: 'Manifest Version', oldValue: oldManifest.manifestVersion, newValue: newManifest.manifestVersion, changed: oldManifest.manifestVersion !== newManifest.manifestVersion },
      { key: 'key', label: 'Key', oldValue: oldManifest.key, newValue: newManifest.key, changed: oldManifest.key !== newManifest.key },
      { key: 'name', label: 'Name', oldValue: oldManifest.name, newValue: newManifest.name, changed: oldManifest.name !== newManifest.name },
      { key: 'baseUrl', label: 'Base URL', oldValue: oldManifest.baseUrl, newValue: newManifest.baseUrl, changed: oldManifest.baseUrl !== newManifest.baseUrl },
      { key: 'health', label: 'Health', oldValue: oldManifest.health ?? null, newValue: newManifest.health ?? null, changed: (oldManifest.health ?? null) !== (newManifest.health ?? null) },
    ];
    sections.push({ title: 'Module', fields: rootFields, action: rootFields.some((f) => f.changed) ? 'update' : 'unchanged' });
  } else {
    sections.push({
      title: 'Module', action: 'create',
      fields: [
        { key: 'manifestVersion', label: 'Manifest Version', newValue: newManifest.manifestVersion },
        { key: 'key', label: 'Key', newValue: newManifest.key },
        { key: 'name', label: 'Name', newValue: newManifest.name },
        { key: 'baseUrl', label: 'Base URL', newValue: newManifest.baseUrl },
        { key: 'health', label: 'Health', newValue: newManifest.health ?? null },
      ],
    });
  }

  // Content groups
  for (const g of CONTENT_GROUPS) {
    const newEntries = (newManifest.content?.[g.key as keyof typeof newManifest.content] ?? []) as ManifestContentEntry[];
    const oldEntries = (oldManifest?.content?.[g.key as keyof typeof oldManifest.content] ?? []) as ManifestContentEntry[];
    if (oldEntries.length === 0 && newEntries.length === 0) continue;
    const items = diffKeyed(oldEntries, newEntries, 'deleted');
    sections.push({ title: g.label, items, action: computeSectionAction(items) });
  }

  // Security roles
  const newRoles = newManifest.security?.roles ?? [];
  const oldRoles = oldManifest?.security?.roles ?? [];
  if (oldRoles.length > 0 || newRoles.length > 0) {
    const items = diffKeyed(oldRoles, newRoles, 'deleted');
    sections.push({ title: 'Security Roles', items, action: computeSectionAction(items) });
  }

  return sections;
}

export { buildDiffSections };
