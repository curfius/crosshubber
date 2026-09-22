import { describe, expect, it } from 'vitest';
import type { EditableTreeNode } from '../../../core/navigation/navigation.models';
import type { ShellTreeResponse } from '../../../core/navigation/navigation.models';
import { shellTreeFromResponse, shellTreeToPayload } from './shell-nav-editor.component';

const RESPONSE: ShellTreeResponse = {
  category: 'settings',
  groups: [
    { groupKey: 'nav-a', category: 'settings', name: 'A', parentKey: null, sortOrder: 0, hidden: true },
    { groupKey: 'nav-b', category: 'settings', name: 'B', parentKey: 'nav-a', sortOrder: 10 },
  ],
  items: [
    { moduleKey: 'm1', entryKey: 'one', name: 'One', category: 'settings', type: 'embedded', sortOrder: 0, groupKey: 'nav-a', hidden: true },
    { moduleKey: 'm2', entryKey: 'two', name: 'Two', category: 'settings', type: 'embedded', sortOrder: 10, groupKey: null },
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
});

describe('shellTreeToPayload', () => {
  it('round-trips hidden flags for groups and items', () => {
    const tree = shellTreeFromResponse(RESPONSE);
    const payload = shellTreeToPayload(tree, new Set(['nav-a', 'nav-b']));
    const groupA = payload.groups.find((g) => g.groupKey === 'nav-a')!;
    expect(groupA.hidden).toBe(true);
    const groupB = payload.groups.find((g) => g.groupKey === 'nav-b')!;
    expect(groupB.hidden).toBe(false);
    const itemOne = payload.items.find((i) => i.moduleKey === 'm1' && i.entryKey === 'one')!;
    expect(itemOne.hidden).toBe(true);
    const itemTwo = payload.items.find((i) => i.moduleKey === 'm2')!;
    expect(itemTwo.hidden).toBe(false);
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
