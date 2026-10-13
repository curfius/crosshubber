import { describe, expect, it } from 'vitest';
import type { EditableTreeNode } from '../../../core/navigation/navigation.models';
import { avatarColor } from '../../../core/navigation/navigation.models';
import type { ShellTreeResponse } from '../../../core/navigation/navigation.models';
import {
  shellTreeFromResponse,
  shellTreeToPayload,
  setNodeColor,
  setNodeColorDeep,
} from './shell-nav-editor.component';

const RESPONSE: ShellTreeResponse = {
  category: 'admin-settings',
  groups: [
    { groupKey: 'nav-a', category: 'admin-settings', name: 'A', parentKey: null, sortOrder: 0, hidden: true },
    { groupKey: 'nav-b', category: 'admin-settings', name: 'B', parentKey: 'nav-a', sortOrder: 10 },
    { groupKey: 'mod-m3', category: 'admin-settings', name: 'M3', parentKey: null, sortOrder: 20, color: '#10b981' },
  ],
  items: [
    { moduleKey: 'm1', contentKey: 'one', name: 'One', category: 'admin-settings', type: 'embedded', sortOrder: 0, groupKey: 'nav-a', color: '#ef4444', hidden: true },
    { moduleKey: 'm2', contentKey: 'two', name: 'Two', category: 'admin-settings', type: 'embedded', sortOrder: 10, groupKey: null },
    { moduleKey: 'm3', contentKey: 'three', name: 'Three', category: 'admin-settings', type: 'embedded', sortOrder: 20, groupKey: 'mod-m3' },
  ],
};

describe('shellTreeFromResponse', () => {
  it('maps hidden flags onto editable nodes', () => {
    const tree = shellTreeFromResponse(RESPONSE);
    const groupA = tree.find((n) => n.id === 'group:nav-a')!;
    expect(groupA.hidden).toBe(true);
    const groupB = groupA.children.find((n) => n.id === 'group:nav-b')!;
    expect(groupB.hidden).toBeUndefined();
    const itemOne = groupA.children.find((n) => n.id === 'item:m1:one')!;
    expect(itemOne.hidden).toBe(true);
    const itemTwo = tree.find((n) => n.id === 'item:m2:two')!;
    expect(itemTwo.hidden).toBeUndefined();
  });

  it('maps item colors plus deterministic hash fallbacks', () => {
    const tree = shellTreeFromResponse(RESPONSE);
    const groupA = tree.find((n) => n.id === 'group:nav-a')!;
    const itemOne = groupA.children.find((n) => n.id === 'item:m1:one')!;
    expect(itemOne.color).toBe('#ef4444');
    expect(itemOne.fallbackColor).toBe(avatarColor('m1:one'));
    const itemTwo = tree.find((n) => n.id === 'item:m2:two')!;
    expect(itemTwo.color).toBeUndefined();
    expect(itemTwo.fallbackColor).toBe(avatarColor('m2:two'));
  });

  it('leaves folder nodes without colors unless the response carries one', () => {
    const tree = shellTreeFromResponse(RESPONSE);
    const groupA = tree.find((n) => n.id === 'group:nav-a')!;
    expect(groupA.color).toBeUndefined();
    expect(groupA.fallbackColor).toBeUndefined();
    const groupM3 = tree.find((n) => n.id === 'group:mod-m3')!;
    expect(groupM3.color).toBe('#10b981');
  });
});

describe('shellTreeToPayload', () => {
  it('round-trips hidden flags for groups and items', () => {
    const tree = shellTreeFromResponse(RESPONSE);
    const payload = shellTreeToPayload(tree, new Set(['nav-a', 'nav-b']));
    const groupA = payload.groups.find((g) => g.groupKey === 'nav-a')!;
    expect(groupA.hidden).toBe(true);
    const groupB = payload.groups.find((g) => g.groupKey === 'nav-b')!;
    expect(groupB.hidden).toBe(false);
    const itemOne = payload.items.find((i) => i.moduleKey === 'm1' && i.contentKey === 'one')!;
    expect(itemOne.hidden).toBe(true);
    expect(itemOne.color).toBe('#ef4444');
    const itemTwo = payload.items.find((i) => i.moduleKey === 'm2')!;
    expect(itemTwo.hidden).toBe(false);
    expect(itemTwo.color).toBeNull();
  });

  it('round-trips colors and clears to null when unset', () => {
    const tree = shellTreeFromResponse(RESPONSE);
    const payload = shellTreeToPayload(tree, new Set(['nav-a', 'nav-b', 'mod-m3']));
    expect(JSON.stringify(payload)).not.toContain('fallbackColor');
    const groupM3 = payload.groups.find((g) => g.groupKey === 'mod-m3')!;
    expect(groupM3.color).toBe('#10b981');
  });

  it('saves legacy ungrouped items into their module section', () => {
    const payload = shellTreeToPayload(shellTreeFromResponse(RESPONSE), new Set(['nav-a', 'nav-b', 'mod-m3']));
    const itemTwo = payload.items.find((i) => i.moduleKey === 'm2')!;
    expect(itemTwo.groupKey).toBe('mod-m2');
    expect(payload.groups.find((g) => g.groupKey === 'mod-m2')).toBeDefined();
  });

  it('marks freshly created sections hidden when the node is hidden', () => {
    const nodes: EditableTreeNode[] = [
      { id: 'new-1', label: 'Custom', kind: 'folder', hidden: true, children: [] },
    ];
    const payload = shellTreeToPayload(nodes, new Set());
    expect(payload.groups[0].hidden).toBe(true);
    expect(payload.groups[0].groupKey).toContain('nav-');
  });
});

describe('setNodeColor', () => {
  it('sets the color on the target node and clones the tree', () => {
    const tree: EditableTreeNode[] = [
      { id: 'f', label: 'F', kind: 'folder', children: [{ id: 'i', label: 'I', kind: 'item', color: '#ef4444', fallbackColor: avatarColor('m:i'), children: [] }] },
    ];
    const next = setNodeColor(tree, 'i', '#3b82f6');
    expect(next[0].children[0].color).toBe('#3b82f6');
    expect(tree[0].children[0].color).toBe('#ef4444');
    expect(next).not.toBe(tree);
  });

  it('clears the color back to undefined for the hash fallback', () => {
    const tree: EditableTreeNode[] = [{ id: 'i', label: 'I', kind: 'item', color: '#ef4444', children: [] }];
    const next = setNodeColor(tree, 'i', undefined);
    expect(next[0].color).toBeUndefined();
    expect(JSON.stringify(next)).not.toContain('color');
  });

  it('preserves the color of unaffected nodes', () => {
    const tree: EditableTreeNode[] = [
      { id: 'a', label: 'A', kind: 'item', color: '#ef4444', children: [] },
      { id: 'b', label: 'B', kind: 'item', children: [] },
    ];
    const next = setNodeColor(tree, 'b', '#3b82f6');
    expect(next[0].color).toBe('#ef4444');
    expect(next[1].color).toBe('#3b82f6');
    expect(next).not.toBe(tree);
  });
});

describe('setNodeColorDeep', () => {
  const section = (): EditableTreeNode[] => [
    {
      id: 'f', label: 'F', kind: 'folder', children: [
        { id: 'i1', label: 'I1', kind: 'item', color: '#ef4444', children: [] },
        { id: 'f2', label: 'F2', kind: 'folder', children: [{ id: 'i2', label: 'I2', kind: 'item', children: [] }] },
      ],
    },
  ];

  it('applies to the section and all descendants when applyToAll is on', () => {
    const next = setNodeColorDeep(section(), 'f', '#10b981', true);
    expect(next[0].color).toBe('#10b981');
    expect(next[0].children[0].color).toBe('#10b981');
    expect(next[0].children[1].color).toBe('#10b981');
    expect(next[0].children[1].children[0].color).toBe('#10b981');
    expect(section()[0].children[0].color).toBe('#ef4444');
  });

  it('colors only the section when applyToAll is off', () => {
    const next = setNodeColorDeep(section(), 'f', '#10b981', false);
    expect(next[0].color).toBe('#10b981');
    expect(next[0].children[0].color).toBe('#ef4444');
    expect(next[0].children[1].color).toBeUndefined();
  });
});
