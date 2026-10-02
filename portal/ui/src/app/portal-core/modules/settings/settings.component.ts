import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, DestroyRef } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SettingsService } from '../../../core/settings/settings.service';
import { moduleContentId, type PortalModuleContent, PortalUser, type NavigationGroup } from '../../../core/models';
import { TreeNode, buildSettingsTree } from '../../layout/sidebar/tree.model';
import { AppOutlet } from '../../workarea/module-outlet.component';
import { WorkbenchService } from '../../features/workspaces/workspaces.store';
import { NavigationCoordinator } from '../../features/navigation-coordinator.service';
import { DsTree, DsTreeNode } from '../../../shared/components/ds-tree/ds-tree.component';
import { I18nService } from '../../../core/i18n/i18n.service';

const MODULE_KEY = 'settings';

@Component({
  selector: 'app-module-settings',
  imports: [FormsModule, AppOutlet, DsTree],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.css',
})
export class Settings {
  private readonly settingsService = inject(SettingsService);
  private readonly wb = inject(WorkbenchService);
  private readonly coordinator = inject(NavigationCoordinator);
  protected readonly i18n = inject(I18nService);

  readonly user = input<PortalUser | null>(null);

  protected readonly isAdmin = this.settingsService.isAdmin;
  protected readonly activeEntry = signal<PortalModuleContent | null>(null);

  protected readonly settingsContents = computed(() =>
    this.wb
      .getModuleContents()
      .filter((ep) => ep.category === 'settings' || ep.category === 'admin-settings'),
  );

  protected readonly settingsGroups = computed(() =>
    this.wb.getNavigationGroups().filter((g) => g.category === 'settings'),
  );

  protected readonly settingsTree = computed<TreeNode<PortalModuleContent | NavigationGroup>[]>(() =>
    buildSettingsTree(this.settingsGroups(), this.settingsContents()),
  );

  protected readonly dsNodes = computed<DsTreeNode[]>(() => this.toDsNodes(this.settingsTree()));

  protected readonly selectedEntryId = computed(() => {
    const ep = this.activeEntry();
    return ep ? moduleContentId(ep) : null;
  });

  constructor() {
    effect(() => {
      const eps = this.settingsContents();
      if (eps.length === 0) return;
      if (!this.activeEntry()) this.activeEntry.set(eps[0]);
    });

    // Restore requests arrive through the NavigationCoordinator (URL query
    // params, popstate, deep links) — no private hash router. A pending path
    // that arrived before this component mounted is replayed on registration.
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
    const ep = this.settingsContents().find((e) => e.contentKey === contentKey);
    if (ep) this.activeEntry.set(ep);
  }
}
