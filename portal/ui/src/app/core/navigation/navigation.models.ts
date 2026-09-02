// ── Navigation domain models + pure tree helpers ───────────────────────
// Mirrors portal/src/modules/navigation/navigation.service.ts (kept separate
// so the UI bundle stays independent from backend imports).

export type PinnedNodeType = 'folder' | 'item';

export interface PinnedNode {
  id: string;
  nodeType: PinnedNodeType;
  name?: string;
  ref?: string;
  children: PinnedNode[];
}

export interface NavigationSidebarSettings {
  showPinned: boolean;
  showWorkspaces: boolean;
  apps: string[];
}

export interface NavigationUserSettings {
  sidebar: NavigationSidebarSettings;
  sidebarExpanded: string[];
}

export interface NavigationFeatures {
  pinnedAppsEnabled: boolean;
  workspacesEnabled: boolean;
}

export interface LayoutItemNode {
  id: string;
  type: 'item';
  ref: string;
}

export interface LayoutSectionNode {
  id: string;
  name: string;
  type?: 'section';
  children: (LayoutSectionNode | LayoutItemNode)[];
}

export type LayoutNode = LayoutSectionNode | LayoutItemNode;

export interface NavigationLayout {
  pinnedSectionEnabled: boolean;
  sections: LayoutSectionNode[];
}

export interface ShellGroupPayload {
  groupKey?: string;
  name: string;
  parentKey?: string | null;
  icon?: string;
}

export interface ShellItemPayload {
  moduleKey: string;
  entryKey: string;
  groupKey: string | null;
}

export interface ShellTreePayload {
  groups: ShellGroupPayload[];
  items: ShellItemPayload[];
}

export interface ShellTreeResponse {
  category: string;
  groups: Array<{
    groupKey: string;
    category: string;
    name: string;
    parentKey: string | null;
    sortOrder: number;
    icon?: string;
    roles?: string[];
  }>;
  items: Array<{
    moduleKey: string;
    entryKey: string;
    name: string;
    category: string;
    type: string;
    sortOrder: number;
    groupKey?: string | null;
    color?: string;
    roles?: string[];
    active?: boolean;
  }>;
}

export const REF_RE = /^[a-z0-9][a-z0-9-]{0,63}:[a-z0-9][a-z0-9-]{0,63}$/;

let idCounter = 0;
/** Client-side id for NEW nodes. UUIDs are preserved by the backend on save,
 *  so expansion/rename state survives save round-trips. */
export function newTreeId(prefix: string): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  idCounter += 1;
  return `${prefix}-new-${Date.now().toString(36)}-${idCounter}`;
}

// ── Generic editable tree (used by the DnD tree component) ────────────

export interface EditableTreeNode {
  id: string;
  label: string;
  kind: 'folder' | 'item';
  color?: string;
  meta?: unknown;
  children: EditableTreeNode[];
}

function findNode(nodes: EditableTreeNode[], id: string): EditableTreeNode | null {
  for (const node of nodes) {
    if (node.id === id) return node;
    const found = findNode(node.children, id);
    if (found) return found;
  }
  return null;
}

function removeNode(nodes: EditableTreeNode[], id: string): EditableTreeNode | null {
  const idx = nodes.findIndex((n) => n.id === id);
  if (idx >= 0) return nodes.splice(idx, 1)[0];
  for (const node of nodes) {
    const removed = removeNode(node.children, id);
    if (removed) return removed;
  }
  return null;
}

function depthOf(nodes: EditableTreeNode[], id: string, depth = 1): number {
  for (const node of nodes) {
    if (node.id === id) return depth;
    const d = depthOf(node.children, id, depth + 1);
    if (d > 0) return d;
  }
  return -1;
}

function subtreeDepth(node: EditableTreeNode): number {
  if (node.children.length === 0) return 1;
  return 1 + Math.max(...node.children.map(subtreeDepth));
}

/** True when `ancestorId` is the node itself or one of its ancestors. */
export function isSelfOrAncestor(nodes: EditableTreeNode[], ancestorId: string, nodeId: string): boolean {
  let current = findNode(nodes, nodeId);
  while (current) {
    if (current.id === ancestorId) return true;
    // find parent by scanning
    current = findParent(nodes, current.id);
  }
  return false;
}

export function findParent(nodes: EditableTreeNode[], id: string): EditableTreeNode | null {
  for (const node of nodes) {
    if (node.children.some((c) => c.id === id)) return node;
    const parent = findParent(node.children, id);
    if (parent) return parent;
  }
  return null;
}

/**
 * Moves a node to (targetParentId, index). Returns a NEW tree or null when
 * the move is invalid (unknown ids, drop into own subtree, depth overflow).
 */
export function moveNode(
  nodes: EditableTreeNode[],
  nodeId: string,
  targetParentId: string | null,
  index: number,
  maxDepth = 3,
): EditableTreeNode[] | null {
  const node = findNode(nodes, nodeId);
  if (!node) return null;
  if (targetParentId === nodeId) return null;
  if (targetParentId && isSelfOrAncestor(nodes, nodeId, targetParentId)) return null;

  const next = structuredClone(nodes);
  const removed = removeNode(next, nodeId);
  if (!removed) return null;

  const computedIndex = Math.max(0, Math.min(index, targetParentId
    ? (findNode(next, targetParentId)?.children.length ?? 0)
    : next.length));

  if (targetParentId) {
    const parent = findNode(next, targetParentId);
    if (!parent || parent.kind !== 'folder') return null;
    if (subtreeDepth(removed) + depthOf(next, targetParentId) > maxDepth) return null;
    parent.children.splice(computedIndex, 0, removed);
  } else {
    if (subtreeDepth(removed) > maxDepth) return null;
    next.splice(computedIndex, 0, removed);
  }
  return next;
}

export function flattenTree(nodes: EditableTreeNode[]): Array<{ node: EditableTreeNode; depth: number; parentId: string | null }> {
  const out: Array<{ node: EditableTreeNode; depth: number; parentId: string | null }> = [];
  const walk = (list: EditableTreeNode[], depth: number, parentId: string | null) => {
    for (const node of list) {
      out.push({ node, depth, parentId });
      walk(node.children, depth + 1, node.id);
    }
  };
  walk(nodes, 0, null);
  return out;
}

/**
 * Moves `nodeId` before/after `targetNodeId` among the target's siblings.
 * Handles same-parent index shifting (remove-then-insert semantics) and
 * cross-parent moves. Returns a NEW tree or null when the move is invalid.
 */
export function moveNodeRelative(
  nodes: EditableTreeNode[],
  nodeId: string,
  targetNodeId: string,
  position: 'before' | 'after',
  maxDepth = 3,
): EditableTreeNode[] | null {
  const target = findNode(nodes, targetNodeId);
  if (!target) return null;
  const parent = findParent(nodes, targetNodeId);
  const parentId = parent ? parent.id : null;
  const siblings = parent ? parent.children : nodes;
  const targetIdx = siblings.findIndex((n) => n.id === targetNodeId);
  if (targetIdx < 0) return null;
  const ownIdx = siblings.findIndex((n) => n.id === nodeId);
  const finalPos = position === 'before' ? targetIdx : targetIdx + 1;
  // Removing the node first shifts indices after it down by one.
  const insertIdx = ownIdx >= 0 && finalPos > ownIdx ? finalPos - 1 : finalPos;
  return moveNode(nodes, nodeId, parentId, insertIdx, maxDepth);
}

/** All item refs of a pinned tree, in root-first document order. */
export function collectPinnedRefs(nodes: PinnedNode[]): string[] {
  const refs: string[] = [];
  const walk = (list: PinnedNode[]) => {
    for (const node of list) {
      if (node.nodeType === 'item' && node.ref) refs.push(node.ref);
      else walk(node.children);
    }
  };
  walk(nodes);
  return refs;
}

// ── Layout helpers ────────────────────────────────────────────────────

export function walkLayout(
  sections: (LayoutSectionNode | LayoutItemNode)[],
  visit: (node: LayoutSectionNode | LayoutItemNode, depth: number) => void,
  depth = 0,
): void {
  for (const node of sections) {
    visit(node, depth);
    if (node.type !== 'item') walkLayout(node.children, visit, depth + 1);
  }
}

/** Resolves layout leaf refs; returns pairs of (sectionPath, ref). */
export function layoutLeafRefs(sections: LayoutSectionNode[]): string[] {
  const refs: string[] = [];
  walkLayout(sections, (node) => {
    if (node.type === 'item' && node.ref) refs.push(node.ref);
  });
  return refs;
}
