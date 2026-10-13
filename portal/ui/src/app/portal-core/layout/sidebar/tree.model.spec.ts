import { describe, expect, it } from 'vitest';
import type { NavigationGroup, PortalModuleContent } from '../../../core/models';
import { buildAppTree, buildSettingsTree } from './tree.model';

function ep(
  partial: Partial<PortalModuleContent> & Pick<PortalModuleContent, 'moduleKey' | 'contentKey' | 'category'>,
): PortalModuleContent {
  return {
    name: partial.contentKey,
    type: 'embedded',
    parentContentKey: null,
    groupKey: null,
    sortOrder: 0,
    ...partial,
  };
}

function group(
  partial: Partial<NavigationGroup> & Pick<NavigationGroup, 'groupKey' | 'category'>,
): NavigationGroup {
  return { name: partial.groupKey, parentKey: null, sortOrder: 0, ...partial };
}

function keyOf(node: { data: PortalModuleContent | NavigationGroup }): string {
  return 'contentKey' in node.data ? `ep:${node.data.contentKey}` : node.data.groupKey;
}

describe('buildAppTree', () => {
  it('groups ungrouped applications under a virtual per-module section', () => {
    const groups = [group({ groupKey: 'nav-g', category: 'applications', sortOrder: 10 })];
    const eps = [
      ep({ moduleKey: 'm', contentKey: 'root', category: 'applications', sortOrder: 0 }),
      ep({ moduleKey: 'm', contentKey: 'other', category: 'applications', sortOrder: 5 }),
      ep({ moduleKey: 'm', contentKey: 'child', category: 'applications', groupKey: 'nav-g' }),
      ep({ moduleKey: 'm', contentKey: 'othercat', category: 'admin-settings' }),
    ];

    const tree = buildAppTree(groups, eps);

    expect(tree.map(keyOf)).toEqual(['nav-g', 'virtual:m']);
    const virtual = tree[1];
    expect(virtual.data.name).toBe('m');
    expect(virtual.children.map(keyOf)).toEqual(['ep:root', 'ep:other']);
  });

  it('orders siblings by sortOrder', () => {
    const eps = [
      ep({ moduleKey: 'm', contentKey: 'b', category: 'applications', sortOrder: 20 }),
      ep({ moduleKey: 'm', contentKey: 'a', category: 'applications', sortOrder: 10 }),
    ];

    const tree = buildAppTree([], eps);
    const virtual = tree[0];
    expect(virtual.children.map(keyOf)).toEqual(['ep:a', 'ep:b']);
  });
});

describe('buildSettingsTree', () => {
  it('renders admin-settings groups with their contents', () => {
    const groups = [group({ groupKey: 'nav-s', category: 'admin-settings' })];
    const eps = [
      ep({ moduleKey: 'm', contentKey: 'in', category: 'admin-settings', groupKey: 'nav-s' }),
      ep({ moduleKey: 'm', contentKey: 'other', category: 'features' }),
    ];

    const tree = buildSettingsTree(groups, eps);

    expect(tree.map(keyOf)).toEqual(['nav-s']);
    expect(tree[0].children.map(keyOf)).toEqual(['ep:in']);
  });

  it('excludes legacy settings-category rows (folded to admin-settings)', () => {
    const groups = [group({ groupKey: 'legacy', category: 'settings' })];
    const eps = [ep({ moduleKey: 'm', contentKey: 'old', category: 'settings' })];

    expect(buildSettingsTree(groups, eps)).toHaveLength(0);
  });
});
