import type { PortalModuleContent, NavigationGroup } from '../../../core/models';
import { moduleContentId } from '../../../core/models';

export interface TreeNode<T> {
  data: T;
  children: TreeNode<PortalModuleContent | NavigationGroup>[];
}

export function buildAppTree(
  groups: NavigationGroup[],
  moduleContents: PortalModuleContent[],
): TreeNode<PortalModuleContent | NavigationGroup>[] {
  const appGroups = groups.filter((g) => g.category === 'applications');
  const appEps = moduleContents.filter((ep) => ep.category === 'applications');

  return buildTree(appGroups, appEps);
}

export function buildSettingsTree(
  groups: NavigationGroup[],
  moduleContents: PortalModuleContent[],
): TreeNode<PortalModuleContent | NavigationGroup>[] {
  const settingsGroups = groups.filter((g) => g.category === 'settings');
  // Module-owned settings MFEs land in the admin-settings category and are
  // rendered as plain entries in the same tree (role-filtered server-side).
  const settingsEps = moduleContents.filter(
    (ep) => ep.category === 'settings' || ep.category === 'admin-settings',
  );

  return buildTree(settingsGroups, settingsEps);
}

export function buildUserSettingsTree(
  groups: NavigationGroup[],
  moduleContents: PortalModuleContent[],
): TreeNode<PortalModuleContent | NavigationGroup>[] {
  const userGroups = groups.filter((g) => g.category === 'user-settings');
  const userEps = moduleContents.filter((ep) => ep.category === 'user-settings');

  return buildTree(userGroups, userEps);
}

function buildTree(
  groups: NavigationGroup[],
  moduleContents: PortalModuleContent[],
): TreeNode<PortalModuleContent | NavigationGroup>[] {
  const groupNodes = new Map<string, TreeNode<NavigationGroup>>();
  for (const g of groups) {
    groupNodes.set(g.groupKey, { data: g, children: [] });
  }

  const roots: TreeNode<PortalModuleContent | NavigationGroup>[] = [];
  for (const g of groups) {
    const node = groupNodes.get(g.groupKey)!;
    if (g.parentKey && groupNodes.has(g.parentKey)) {
      groupNodes.get(g.parentKey)!.children.push(node);
    } else {
      roots.push(node);
    }
  }

  for (const ep of moduleContents) {
    const node: TreeNode<PortalModuleContent | NavigationGroup> = { data: ep, children: [] };
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
