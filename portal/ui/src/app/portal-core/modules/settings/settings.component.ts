import { Component, computed, effect, inject, input, signal, DestroyRef } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SettingsService } from '../../../core/settings/settings.service';
import { entryPointId, type PortalEntryPoint, PortalUser, type EntryPointGroup } from '../../../core/models';
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
  protected readonly activeEntry = signal<PortalEntryPoint | null>(null);

  protected readonly settingsEntryPoints = computed(() =>
    this.wb.getEntryPoints().filter((ep) => ep.category === 'settings'),
  );

  protected readonly settingsGroups = computed(() =>
    this.wb.getEntryPointGroups().filter((g) => g.category === 'settings'),
  );

  protected readonly settingsTree = computed<TreeNode<PortalEntryPoint | EntryPointGroup>[]>(() =>
    buildSettingsTree(this.settingsGroups(), this.settingsEntryPoints()),
  );

  protected readonly dsNodes = computed<DsTreeNode[]>(() => this.toDsNodes(this.settingsTree()));

  protected readonly selectedEntryId = computed(() => {
    const ep = this.activeEntry();
    return ep ? entryPointId(ep) : null;
  });

  constructor() {
    effect(() => {
      const eps = this.settingsEntryPoints();
      if (eps.length === 0) return;
      if (!this.activeEntry()) this.activeEntry.set(eps[0]);
    });

    // Restore requests arrive through the NavigationCoordinator (URL query
    // params, popstate, deep links) — no private hash router. A pending path
    // that arrived before this component mounted is replayed on registration.
    const off = this.coordinator.onRestore(MODULE_KEY, (path) => this.applyModulePath(path));
    inject(DestroyRef).onDestroy(off);
  }

  protected selectEntry(ep: PortalEntryPoint): void {
    this.activeEntry.set(ep);
    this.coordinator.navigateFromModule(MODULE_KEY, '/' + ep.entryKey);
  }

  protected onTreeNodeSelected(node: DsTreeNode): void {
    if (node.data) this.selectEntry(node.data as PortalEntryPoint);
  }

  private toDsNodes(
    nodes: TreeNode<PortalEntryPoint | EntryPointGroup>[],
  ): DsTreeNode[] {
    return nodes.map((n) => {
      if ('moduleKey' in n.data) {
        return { id: entryPointId(n.data), label: n.data.name, data: n.data };
      }
      return {
        id: `group:${n.data.groupKey}`,
        label: n.data.name,
        children: this.toDsNodes(n.children),
      };
    });
  }

  private applyModulePath(path: string): void {
    const entryKey = path.replace(/^\//, '').split('/')[0];
    const ep = this.settingsEntryPoints().find((e) => e.entryKey === entryKey);
    if (ep) this.activeEntry.set(ep);
  }
}
