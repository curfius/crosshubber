import { describe, expect, it } from 'vitest';
import { buildDiffSections, type PreviewSection } from './manifest-diff';
import type { PortalModuleManifest } from '../../../core/models';

function manifest(overrides: Partial<PortalModuleManifest>): PortalModuleManifest {
  return {
    manifestVersion: 1,
    key: 'my-mod',
    name: 'My Mod',
    baseUrl: 'http://localhost:3000',
    health: '/healthz',
    content: { applications: [], features: [], adminSettings: [], userSettings: [] },
    ...overrides,
  } as PortalModuleManifest;
}

const app = (key: string, name: string, url: string): Record<string, unknown> => ({ key, name, url });

describe('manifest-diff engine', () => {
  it('create mode: single Module section with create action', () => {
    const sections = buildDiffSections(null, manifest({}));
    expect(sections).toHaveLength(1);
    expect(sections[0].title).toBe('Module');
    expect(sections[0].action).toBe('create');
    const labels = sections[0].fields!.map((f) => f.label);
    expect(labels).toEqual(['Manifest Version', 'Key', 'Name', 'Base URL', 'Health']);
  });

  it('unchanged manifests produce an unchanged Module section', () => {
    const m = manifest({});
    const sections = buildDiffSections(m, manifest({}));
    expect(sections[0].action).toBe('unchanged');
    expect(sections[0].fields!.every((f) => !f.changed)).toBe(true);
  });

  it('root property changes surface as update with changed fields', () => {
    const sections = buildDiffSections(manifest({}), manifest({ name: 'Renamed', health: undefined }));
    const section = sections[0];
    expect(section.action).toBe('update');
    const name = section.fields!.find((f) => f.key === 'name')!;
    expect(name.changed).toBe(true);
    expect(name.oldValue).toBe('My Mod');
    expect(name.newValue).toBe('Renamed');
    const health = section.fields!.find((f) => f.key === 'health')!;
    expect(health.changed).toBe(true);
    expect(name.key).toBe('name');
  });

  it('empty-to-empty content groups are omitted', () => {
    const sections = buildDiffSections(manifest({}), manifest({}));
    expect(sections.map((s) => s.title)).toEqual(['Module']);
  });

  it('new content entry is reported as create', () => {
    const sections = buildDiffSections(
      manifest({}),
      manifest({ content: { applications: [app('a1', 'App', 'http://x/a')], features: [], adminSettings: [], userSettings: [] } as unknown as PortalModuleManifest['content'] }),
    );
    const applications = sections.find((s) => s.title === 'Applications')!;
    expect(applications.action).toBe('create');
    expect(applications.items![0]).toMatchObject({ action: 'create', key: 'a1' });
  });

  it('removed content entry is reported as update with deleted field', () => {
    const old = manifest({
      content: { applications: [app('a1', 'App', 'http://x/a')], features: [], adminSettings: [], userSettings: [] } as unknown as PortalModuleManifest['content'],
    });
    const sections = buildDiffSections(old, manifest({}));
    const applications = sections.find((s) => s.title === 'Applications')!;
    expect(applications.action).toBe('update');
    expect(applications.items![0].action).toBe('update');
    expect(applications.items![0].fields[0].key).toBe('_deleted');
  });

  it('modified entry URL is a changed field; key/type/description are skipped', () => {
    const old = manifest({
      content: { applications: [{ ...app('a1', 'App', 'http://x/a'), description: 'd' }], features: [], adminSettings: [], userSettings: [] } as unknown as PortalModuleManifest['content'],
    });
    const next = manifest({
      content: { applications: [{ ...app('a1', 'App', 'http://x/b'), description: 'other' }], features: [], adminSettings: [], userSettings: [] } as unknown as PortalModuleManifest['content'],
    });
    const applications = buildDiffSections(old, next).find((s) => s.title === 'Applications')!;
    const fields = applications.items![0].fields;
    expect(applications.items![0].action).toBe('update');
    expect(fields.map((f) => f.key)).toEqual(['name', 'url']);
    expect(fields.find((f) => f.key === 'url')!.changed).toBe(true);
    expect(fields.find((f) => f.key === 'description')).toBeUndefined();
  });

  it('security roles diff uses the same create/update logic', () => {
    const old = manifest({ security: { roles: [{ key: 'r1', name: 'R1', description: '' }] } } as Partial<PortalModuleManifest>);
    const next = manifest({ security: { roles: [{ key: 'r1', name: 'R1 renamed', description: '' }, { key: 'r2', name: 'R2', description: '' }] } } as Partial<PortalModuleManifest>);
    const roles = buildDiffSections(old, next).find((s) => s.title === 'Security Roles')!;
    expect(roles.action).toBe('update');
    expect(roles.items!.find((i) => i.key === 'r2')!.action).toBe('create');
    expect(roles.items!.find((i) => i.key === 'r1')!.action).toBe('update');
  });

  it('section action is unchanged when every item is unchanged', () => {
    const entry = app('a1', 'App', 'http://x/a');
    const content = { applications: [entry], features: [], adminSettings: [], userSettings: [] } as unknown as PortalModuleManifest['content'];
    const sections: PreviewSection[] = buildDiffSections(manifest({ content }), manifest({ content }));
    const applications = sections.find((s) => s.title === 'Applications')!;
    expect(applications.action).toBe('unchanged');
    expect(applications.items![0].action).toBe('unchanged');
  });
});
