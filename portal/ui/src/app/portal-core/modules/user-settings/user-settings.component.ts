import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, DestroyRef } from '@angular/core';
import { moduleContentId, type PortalModuleContent, PortalUser, type NavigationGroup } from '../../../core/models';
import { TreeNode, buildUserSettingsTree } from '../../layout/sidebar/tree.model';
import { AppOutlet } from '../../workarea/module-outlet.component';
import { WorkbenchService } from '../../features/workspaces/workspaces.store';
import { NavigationCoordinator } from '../../features/navigation-coordinator.service';
import { DsTree, DsTreeNode } from '../../../shared/components/ds-tree/ds-tree.component';
import { I18nService } from '../../../core/i18n/i18n.service';

const MODULE_KEY = 'user-settings';

/**
 * Aggregating shell for user settings screens: portal-owned screens
 * (category 'user-settings') contributed by modules. Opens from the toolbar
 * avatar dialog; renders as a tab app like the admin settings shell.
 */
@Component({
  selector: 'app-user-settings',
  imports: [AppOutlet, DsTree],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './user-settings.component.html',
  styleUrl: './user-settings.component.css',
})
export class UserSettings {
  private readonly wb = inject(WorkbenchService);
  private readonly coordinator = inject(NavigationCoordinator);
  protected readonly i18n = inject(I18nService);

  readonly user = input<PortalUser | null>(null);

  protected readonly activeEntry = signal<PortalModuleContent | null>(null);

  protected readonly userSettingsContents = computed(() =>
    this.wb.getModuleContents().filter((ep) => ep.category === 'user-settings'),
  );

  protected readonly userSettingsGroups = computed(() =>
    this.wb.getNavigationGroups().filter((g) => g.category === 'user-settings'),
  );

  protected readonly userSettingsTree = computed<TreeNode<PortalModuleContent | NavigationGroup>[]>(() =>
    buildUserSettingsTree(this.userSettingsGroups(), this.userSettingsContents()),
  );

  protected readonly dsNodes = computed<DsTreeNode[]>(() => this.toDsNodes(this.userSettingsTree()));

  protected readonly selectedEntryId = computed(() => {
    const ep = this.activeEntry();
    return ep ? moduleContentId(ep) : null;
  });

  constructor() {
    effect(() => {
      const eps = this.userSettingsContents();
      if (eps.length === 0) return;
      if (!this.activeEntry()) this.activeEntry.set(eps[0]);
    });

    // Restores arrive through the NavigationCoordinator (URL query params,
    // popstate, deep links). A path that arrived before this component
    // mounted is replayed on registration.
    const off = this.coordinator.onRestore(MODULE_KEY, (path) => this.applyModulePath(path));
    inject(DestroyRef).onDestroy(off);
  }

  protected selectEntry(ep: PortalModuleContent): void {
    this.activeEntry.set(ep);
    this.coordinator.navigateFromModule(MODULE_KEY, '/' + ep.contentKey);
  }

  protected onTreeNodeSelected(node: DsTreeNode): void {
    if (node.data) this.selectEntry(node.data as PortalModuleContent);
  }

  private toDsNodes(
    nodes: TreeNode<PortalModuleContent | NavigationGroup>[],
  ): DsTreeNode[] {
    return nodes.map((n) => {
      if ('moduleKey' in n.data) {
        return { id: moduleContentId(n.data), label: n.data.name, data: n.data };
      }
      return {
        id: `group:${n.data.groupKey}`,
        label: n.data.name,
        children: this.toDsNodes(n.children),
      };
    });
  }

  private applyModulePath(path: string): void {
    const contentKey = path.replace(/^\//, '').split('/')[0];
    const ep = this.userSettingsContents().find((e) => e.contentKey === contentKey);
    if (ep) this.activeEntry.set(ep);
  }
}
