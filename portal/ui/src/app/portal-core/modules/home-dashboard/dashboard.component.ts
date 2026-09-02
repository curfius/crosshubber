import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import type { PortalEntryPoint, WorkspaceMeta } from '../../../core/models';
import { entryPointId } from '../../../core/models';
import { WorkbenchService } from '../../features/workspaces/workspaces.store';
import { NavigationStore } from '../../../core/navigation/navigation.store';
import { I18nService } from '../../../core/i18n/i18n.service';

@Component({
  selector: 'app-portal-dashboard',
  imports: [FormsModule],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css',
})
export class PortalDashboard implements OnInit {
  private readonly wb = inject(WorkbenchService);
  protected readonly nav = inject(NavigationStore);
  protected readonly i18n = inject(I18nService);

  protected readonly entryPoints = signal<PortalEntryPoint[]>([]);
  protected readonly workspaces = signal<WorkspaceMeta[]>([]);
  protected readonly search = signal('');

  protected readonly pinnedRefs = this.nav.pinnedRefs;
  protected readonly workspacesEnabled = computed(() => this.nav.features().workspacesEnabled);
  protected readonly pinnedAppsEnabled = computed(() => this.nav.features().pinnedAppsEnabled);

  protected readonly collapsedMyWorkspaces = signal(false);
  protected readonly collapsedMyApps = signal(false);
  protected readonly collapsedAllApplications = signal(false);

  protected readonly filteredMyWorkspaces = computed(() => {
    const q = this.search().toLowerCase();
    return this.workspaces()
      .filter((ws) => this.matchesWs(ws, q))
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  protected readonly filteredFavApps = computed(() => {
    const q = this.search().toLowerCase();
    const pinned = new Set(this.pinnedRefs());
    return this.entryPoints()
      .filter((ep) => ep.category === 'applications' && ep.type !== 'link' && pinned.has(entryPointId(ep)) && this.matchesApp(ep, q))
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  protected readonly filteredAllApps = computed(() => {
    const q = this.search().toLowerCase();
    return this.entryPoints()
      .filter((ep) => ep.category === 'applications' && ep.type !== 'link' && this.matchesApp(ep, q))
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  async ngOnInit(): Promise<void> {
    if (!this.nav.loaded()) await this.nav.load();
    this.entryPoints.set(this.wb.getEntryPoints());
    this.workspaces.set(this.wb.workspaces());
  }

  protected isFav(_type: 'app' | 'workspace', key: string): boolean {
    return this.pinnedRefs().includes(key);
  }

  protected async toggleFav(_type: 'app' | 'workspace', key: string, event: Event): Promise<void> {
    event.stopPropagation();
    await this.nav.togglePin(key);
  }

  protected favKey(ep: PortalEntryPoint): string {
    return entryPointId(ep);
  }

  protected openApp(ep: PortalEntryPoint): void {
    this.wb.openApp(ep);
  }

  protected openInNewTab(ep: PortalEntryPoint, event: Event): void {
    event.stopPropagation();
    this.wb.openApp(ep, false);
  }

  protected openWorkspace(name: string): void {
    void this.wb.loadWorkspace(name, false);
  }

  protected async deleteWorkspace(name: string, event: Event): Promise<void> {
    event.stopPropagation();
    await this.wb.deleteWorkspace(name);
    this.workspaces.set(this.wb.workspaces());
  }

  protected typeBadge(type: string): string {
    switch (type) {
      case 'iframe': return 'iframe';
      case 'embedded': return 'SPA';
      case 'mfe': return 'MFE';
      default: return type;
    }
  }

  protected timeAgo(ts: number): string {
    const diff = Date.now() - ts;
    const mins = Math.floor(diff / 60000);
    if (mins < 1) return this.i18n.t('dashboard.time.justNow');
    if (mins < 60) return this.i18n.t('dashboard.time.minutesAgo', { mins });
    const hrs = Math.floor(mins / 60);
    if (hrs < 24) return this.i18n.t('dashboard.time.hoursAgo', { hrs });
    const days = Math.floor(hrs / 24);
    return this.i18n.t('dashboard.time.daysAgo', { days });
  }

  private matchesWs(ws: WorkspaceMeta, q: string): boolean {
    if (!q) return true;
    return ws.name.toLowerCase().includes(q) || (ws.description ?? '').toLowerCase().includes(q);
  }

  private matchesApp(ep: PortalEntryPoint, q: string): boolean {
    if (!q) return true;
    return ep.name.toLowerCase().includes(q) || ep.moduleKey.toLowerCase().includes(q);
  }
}
