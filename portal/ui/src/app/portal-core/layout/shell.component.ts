import { DragDropModule } from '@angular/cdk/drag-drop';
import { Component, computed, inject, signal, type OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import type { Subscription } from 'rxjs';
import { AppLayout } from '../workarea/split-layout.component';
import { WorkspaceToolbar } from './toolbar/toolbar.component';
import { ConfigService } from '../../core/config/config.service';
import type { PortalConfig, PortalEntryPoint } from '../../core/models';
import { entryPointId } from '../../core/models';
import { UrlSyncService } from '../../core/history/url-sync.service';
import { NavigationCoordinator } from '../features/navigation-coordinator.service';
import { RegistryService } from '../modules/module-registry/module-registry.store';
import { NavigationStore } from '../../core/navigation/navigation.store';
import { NavigationAdminService } from '../../core/navigation/navigation-admin.service';
import { Sidebar } from './sidebar/sidebar.component';
import { AiHubQuickChat } from '../modules/ai-hub/quick-chat/quick-chat.component';
import { WorkbenchService } from '../features/workspaces/workspaces.store';
import { SettingsService } from '../../core/settings/settings.service';
import { AiHubService } from '../../core/ai-hub/ai-hub.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { I18nAdminService } from '../../core/i18n/i18n-admin.service';
import { ThemeService } from '../../core/theme/theme.service';

@Component({
  selector: 'app-shell',
  imports: [Sidebar, AppLayout, WorkspaceToolbar, DragDropModule, AiHubQuickChat],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.css',
})
export class Shell implements OnDestroy {
  private readonly configService = inject(ConfigService);
  private readonly registry = inject(RegistryService);
  private readonly router = inject(Router);
  private readonly urlSync = inject(UrlSyncService);
  private readonly coordinator = inject(NavigationCoordinator);
  protected readonly wb = inject(WorkbenchService);
  private readonly sub: Subscription;
  protected readonly settings = inject(SettingsService);
  protected readonly config = signal<PortalConfig | null>(null);
  protected readonly layout = this.wb.layout;
  protected readonly hasTabs = this.wb.hasTabs;
  protected readonly primaryGroupId = this.wb.primaryGroupId;
  protected readonly workspaces = this.wb.workspaces;
  protected readonly activeWorkspace = this.wb.activeWorkspace;
  protected readonly nav = inject(NavigationStore);
  private readonly navAdmin = inject(NavigationAdminService);
  private readonly aiHub = inject(AiHubService);
  protected readonly i18n = inject(I18nService);
  private readonly i18nAdmin = inject(I18nAdminService);
  private readonly theme = inject(ThemeService);
  protected readonly showQuickChat = signal(false);

  protected readonly appEntryPoints = computed(() =>
    this.config()?.entryPoints.filter((ep) => ep.category === 'applications' && ep.active !== false) ?? [],
  );

  protected readonly activeAppKey = computed(() => {
    const gid = this.wb.focusedGroupId();
    const groups = this.wb.groups();
    if (!gid || !groups[gid] || groups[gid].activeId == null) return null;
    const tab = groups[gid].tabs.find((t) => t.id === groups[gid].activeId);
    return tab ? entryPointId(tab.entryPoint) : null;
  });

  constructor() {
    this.sub = this.registry.changed.subscribe(() => void this.refresh());
    // Navigation editors trigger a config refresh so shell nav trees render
    // immediately after save.
    this.configService.changed$.subscribe(() => void this.refresh());
  }

  ngOnDestroy(): void {
    this.sub.unsubscribe();
  }

  async ngOnInit(): Promise<void> {
    try {
      const i18n = this.i18n.init();
      const config = await this.configService.load();
      this.config.set(config);
      this.wb.setEntryPoints(config.entryPoints);
      this.wb.setEntryPointGroups(config.entryPointGroups);
      this.settings.init(config.user);
      this.i18nAdmin.init(config.user);
      this.navAdmin.init(config.user);
      // Home tab needs the configured home app before state application.
      await this.settings.load();
      this.wb.setHomeApp(this.settings.homeApp());
      await this.nav.load();
      this.wb.setWorkspacesEnabled(this.nav.features().workspacesEnabled);
      await i18n;
      // Boot-time preference correction (DB > localStorage > browser > default).
      // Deliberately NOT re-applied in refresh() â€” boot-time only.
      const prefs = config.preferences ?? {};
      this.theme.initFromPreferences(prefs);
      this.i18n.applyServerPreference(prefs);
      // Legacy hash links (`#<epId>[:n][/path]`) get a one-time redirect to
      // the query-param scheme (D9) â€” cold load only.
      const legacy = this.urlSync.readLegacyHash();
      if (legacy) {
        this.urlSync.replace(legacy, {});
      }
      await this.coordinator.applyState(this.urlSync.read(), { cold: true });
    } catch {
      await this.router.navigate(['/login']);
    }
  }

  private async refresh(): Promise<void> {
    try {
      const config = await this.configService.load();
      this.config.set(config);
      this.wb.setEntryPoints(config.entryPoints);
      this.wb.setEntryPointGroups(config.entryPointGroups);
    } catch {
      // session still valid; ignore transient failures
    }
  }

  openApp(ep: PortalEntryPoint): void {
    if (this.wb.activeWorkspace() !== null) {
      this.wb.openHomeWorkspace();
    }
    this.wb.openApp(ep);
  }

  onHomeClick(): void {
    this.wb.goHome();
  }

  toggleQuickChat(): void {
    this.showQuickChat.update((v) => !v);
  }

  async onWorkspaceClick(name: string): Promise<void> {
    await this.wb.loadWorkspace(name, false);
  }

  async onWorkspaceRename(event: { old: string; newName: string }): Promise<void> {
    await this.wb.renameWorkspace(event.old, event.newName);
  }

  async onWorkspaceDelete(name: string): Promise<void> {
    await this.wb.deleteWorkspace(name);
  }

  logout(): void {
    window.location.href = '/logout';
  }
}
