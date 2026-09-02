import type { PortalEntryPoint, EntryPointGroup } from '../../../core/models';
import { entryPointId } from '../../../core/models';

export interface TreeNode<T> {
  data: T;
  children: TreeNode<PortalEntryPoint | EntryPointGroup>[];
}

export function buildAppTree(
  groups: EntryPointGroup[],
  entryPoints: PortalEntryPoint[],
): TreeNode<PortalEntryPoint | EntryPointGroup>[] {
  const appGroups = groups.filter((g) => g.category === 'applications');
  const appEps = entryPoints.filter((ep) => ep.category === 'applications');

  return buildTree(appGroups, appEps);
}

export function buildSettingsTree(
  groups: EntryPointGroup[],
  entryPoints: PortalEntryPoint[],
): TreeNode<PortalEntryPoint | EntryPointGroup>[] {
  const settingsGroups = groups.filter((g) => g.category === 'settings');
  const settingsEps = entryPoints.filter((ep) => ep.category === 'settings');

  return buildTree(settingsGroups, settingsEps);
}

export function buildUserSettingsTree(
  groups: EntryPointGroup[],
  entryPoints: PortalEntryPoint[],
): TreeNode<PortalEntryPoint | EntryPointGroup>[] {
  const userGroups = groups.filter((g) => g.category === 'user-settings');
  const userEps = entryPoints.filter((ep) => ep.category === 'user-settings');

  return buildTree(userGroups, userEps);
}

function buildTree(
  groups: EntryPointGroup[],
  entryPoints: PortalEntryPoint[],
): TreeNode<PortalEntryPoint | EntryPointGroup>[] {
  const groupNodes = new Map<string, TreeNode<EntryPointGroup>>();
  for (const g of groups) {
    groupNodes.set(g.groupKey, { data: g, children: [] });
  }

  const roots: TreeNode<PortalEntryPoint | EntryPointGroup>[] = [];
  for (const g of groups) {
    const node = groupNodes.get(g.groupKey)!;
    if (g.parentKey && groupNodes.has(g.parentKey)) {
      groupNodes.get(g.parentKey)!.children.push(node);
    } else {
      roots.push(node);
    }
  }

  for (const ep of entryPoints) {
    const node: TreeNode<PortalEntryPoint | EntryPointGroup> = { data: ep, children: [] };
    if (ep.groupKey && groupNodes.has(ep.groupKey)) {
      groupNodes.get(ep.groupKey)!.children.push(node);
    } else {
      roots.push(node);
    }
  }

  const sortBySortOrder = (a: TreeNode<{ sortOrder?: number }>, b: TreeNode<{ sortOrder?: number }>) =>
    (a.data.sortOrder ?? 0) - (b.data.sortOrder ?? 0);

  roots.sort(sortBySortOrder);
  for (const node of groupNodes.values()) {
    node.children.sort(sortBySortOrder);
  }

  return roots;
}
