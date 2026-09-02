import { describe, expect, it } from 'vitest';
import type { EditableTreeNode, PinnedNode } from './navigation.models';
import {
  collectPinnedRefs,
  flattenTree,
  isSelfOrAncestor,
  moveNode,
  moveNodeRelative,
} from './navigation.models';

function folder(id: string, label: string, children: EditableTreeNode[] = []): EditableTreeNode {
  return { id, label, kind: 'folder', children };
}

function item(id: string, label: string, ref = label): EditableTreeNode {
  return { id, label, kind: 'item', meta: ref, children: [] };
}

function buildSampleTree(): EditableTreeNode[] {
  return [
    item('a', 'App A', 'mod:a'),
    folder('f1', 'Folder 1', [
      item('b', 'App B', 'mod:b'),
      folder('f2', 'Folder 2', [item('c', 'App C', 'mod:c')]),
    ]),
  ];
}

describe('moveNode', () => {
  it('moves an item between root positions', () => {
    const tree = buildSampleTree();
    const next = moveNode(tree, 'a', null, 1, 3);
    expect(next?.map((n) => n.id)).toEqual(['f1', 'a']);
  });

  it('moves an item into a folder', () => {
    const tree = buildSampleTree();
    const next = moveNode(tree, 'a', 'f1', 0, 3);
    expect(next?.[0].children.map((n) => n.id)).toEqual(['a', 'b', 'f2']);
  });

  it('rejects dropping a folder into its own subtree', () => {
    const tree = buildSampleTree();
    expect(moveNode(tree, 'f1', 'f2', 0, 3)).toBeNull();
  });

  it('rejects dropping a folder into itself', () => {
    const tree = buildSampleTree();
    expect(moveNode(tree, 'f1', 'f1', 0, 3)).toBeNull();
  });

  it('enforces max depth', () => {
    const tree = buildSampleTree(); // f2 items would land at depth 3
    expect(moveNode(tree, 'a', 'f2', 0, 3)).not.toBeNull();
    expect(moveNode(tree, 'f1', 'f2', 0, 3)).toBeNull(); // folder into sibling folder at depth 3
  });

  it('rejects unknown node ids', () => {
    const tree = buildSampleTree();
    expect(moveNode(tree, 'nope', null, 0, 3)).toBeNull();
  });

  it('does not mutate the input tree', () => {
    const tree = buildSampleTree();
    const snapshot = JSON.stringify(tree);
    moveNode(tree, 'a', 'f1', 0, 3);
    expect(JSON.stringify(tree)).toBe(snapshot);
  });
});

describe('isSelfOrAncestor', () => {
  it('detects ancestry', () => {
    const tree = buildSampleTree();
    expect(isSelfOrAncestor(tree, 'f1', 'f2')).toBe(true);
    expect(isSelfOrAncestor(tree, 'f1', 'a')).toBe(false);
    expect(isSelfOrAncestor(tree, 'f2', 'f2')).toBe(true);
  });
});

describe('flattenTree', () => {
  it('walks depth-first with parent links', () => {
    const flat = flattenTree(buildSampleTree());
    expect(flat.map((f) => f.node.id)).toEqual(['a', 'f1', 'b', 'f2', 'c']);
    expect(flat.find((f) => f.node.id === 'c')?.parentId).toBe('f2');
    expect(flat.find((f) => f.node.id === 'a')?.depth).toBe(0);
  });
});

describe('moveNodeRelative', () => {
  it('moves a node before a later sibling (upward)', () => {
    const tree = [item('a', 'A'), item('b', 'B'), item('c', 'C')];
    expect(moveNodeRelative(tree, 'c', 'b', 'before', 3)?.map((n) => n.id)).toEqual(['a', 'c', 'b']);
  });

  it('moves a node after an earlier sibling (downward)', () => {
    const tree = [item('a', 'A'), item('b', 'B'), item('c', 'C')];
    expect(moveNodeRelative(tree, 'a', 'b', 'after', 3)?.map((n) => n.id)).toEqual(['b', 'a', 'c']);
  });

  it('is a no-op when the node is already at the target position', () => {
    const tree = [item('a', 'A'), item('b', 'B')];
    expect(moveNodeRelative(tree, 'a', 'b', 'before', 3)?.map((n) => n.id)).toEqual(['a', 'b']);
    expect(moveNodeRelative(tree, 'b', 'a', 'after', 3)?.map((n) => n.id)).toEqual(['a', 'b']);
  });

  it('moves a node from another parent before a target sibling', () => {
    const tree = buildSampleTree();
    // move 'a' (root) before 'b' (inside f1)
    const next = moveNodeRelative(tree, 'a', 'b', 'before', 3);
    expect(next?.[0].id).toBe('f1');
    expect(next?.[0].children.map((n) => n.id)).toEqual(['a', 'b', 'f2']);
  });

  it('moves a node after the last child of a folder', () => {
    const tree = buildSampleTree();
    const next = moveNodeRelative(tree, 'a', 'c', 'after', 3);
    const f2 = next?.[0].children.find((n) => n.id === 'f2');
    expect(f2?.children.map((n) => n.id)).toEqual(['c', 'a']);
  });

  it('rejects dropping a folder into its own subtree', () => {
    const tree = buildSampleTree();
    expect(moveNodeRelative(tree, 'f1', 'c', 'after', 3)).toBeNull();
  });
});

describe('collectPinnedRefs', () => {
  it('collects item refs in document order across folders', () => {
    const tree: PinnedNode[] = [
      { id: '1', nodeType: 'item', ref: 'mod:a', children: [] },
      {
        id: '2',
        nodeType: 'folder',
        name: 'F',
        children: [
          { id: '3', nodeType: 'item', ref: 'mod:b', children: [] },
          { id: '4', nodeType: 'item', ref: 'mod:c', children: [] },
        ],
      },
    ];
    expect(collectPinnedRefs(tree)).toEqual(['mod:a', 'mod:b', 'mod:c']);
  });
});
