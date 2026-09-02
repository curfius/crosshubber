import { Component, output, computed, effect, inject, input, signal, viewChild, type ElementRef, type OnDestroy } from '@angular/core';
import { AppOutlet } from './module-outlet.component';
import { CdkDragDrop, DragDropModule } from '@angular/cdk/drag-drop';
import type { PortalUser, PortalEntryPoint, SplitDir, Tab } from '../../core/models';
import { entryPointId } from '../../core/models';
import { WorkbenchService } from '../features/workspaces/workspaces.store';
import { I18nService } from '../../core/i18n/i18n.service';

@Component({
  selector: 'app-area',
  imports: [AppOutlet, DragDropModule],
  templateUrl: './tab-area.component.html',
  styleUrl: './tab-area.component.css',
})
export class AppArea implements OnDestroy {
  protected readonly wb = inject(WorkbenchService);
  protected readonly i18n = inject(I18nService);
  private readonly tabsNav = viewChild<ElementRef<HTMLDivElement>>('tabsNav');
  private navEl: HTMLDivElement | null = null;
  private resizeObserver: ResizeObserver | null = null;

  readonly groupId = input.required<string>();
  readonly user = input<PortalUser | null>(null);
  readonly primaryGroupId = input<string | null>(null);
  readonly editMode = input<boolean>(false);
  readonly hideSingleTabToolbar = input<boolean>(false);
  readonly allEntryPoints = input<PortalEntryPoint[]>([]);

  protected readonly isPrimary = computed(() => this.groupId() === this.primaryGroupId());
  protected readonly showConfirm = signal(false);
  protected readonly showAddApp = signal(false);
  protected readonly appSearch = signal('');
  protected readonly filteredApps = computed(() => {
    const search = this.appSearch().toLowerCase();
    const homeRef = this.wb.getHomeAppRef();
    return this.allEntryPoints()
      .filter((ep) => ep.category === 'applications' && ep.active !== false && entryPointId(ep) !== homeRef)
      .filter((ep) => ep.name.toLowerCase().includes(search) || ep.moduleKey.toLowerCase().includes(search));
  });

  closeAllExceptHome = output<string>();

  protected readonly isSingleArea = computed(() => {
    const l = this.wb.layout();
    return l !== null && l.kind === 'leaf';
  });

  protected readonly holdsHomeTab = computed(() =>
    this.tabs().some((t) => this.wb.isHomeTab(t)),
  );

  protected readonly group = computed(() => this.wb.groups()[this.groupId()]);
  protected readonly tabs = computed(() => this.group()?.tabs ?? []);
  protected readonly activeId = computed(() => this.group()?.activeId ?? null);
  protected readonly mounts = computed(() => [...this.tabs()].sort((a, b) => a.id - b.id));

  protected readonly showNav = computed(() => {
    if (this.editMode()) return true;
    if (!this.hideSingleTabToolbar()) return true;
    return this.tabs().length > 1;
  });

  protected readonly tabOverflow = signal(false);
  protected readonly atStart = signal(true);
  protected readonly atEnd = signal(false);

  constructor() {
    effect(() => {
      const el = this.tabsNav()?.nativeElement ?? null;
      if (el === this.navEl) return;
      this.navEl = el;
      this.resizeObserver?.disconnect();
      this.resizeObserver = null;
      if (!el) return;
      el.addEventListener('scroll', () => this.updateOverflow(), { passive: true });
      this.resizeObserver = new ResizeObserver(() => {
        this.updateOverflow();
        this.ensureActiveVisible();
      });
      this.resizeObserver.observe(el);
      if (el.firstElementChild) this.resizeObserver.observe(el.firstElementChild);
      this.updateOverflow();
    });
    effect(() => {
      const el = this.tabsNav()?.nativeElement ?? null;
      const id = this.activeId();
      if (!el || id == null) return;
      this.ensureActiveVisible();
    });
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
  }

  protected onDrop(event: CdkDragDrop<Tab[]>): void {
    this.wb.onDrop(event);
  }

  protected onTabClick(id: number): void {
    this.wb.activate(this.groupId(), id, true);
  }

  protected closeTab(id: number): void {
    this.wb.closeTab(this.groupId(), id);
  }

  protected split(dir: SplitDir): void {
    this.wb.split(this.groupId(), dir);
  }

  protected closeArea(): void {
    this.wb.closeArea(this.groupId());
  }

  protected onCloseAreaClick(): void {
    this.showConfirm.set(true);
  }

  protected confirmCloseArea(): void {
    this.showConfirm.set(false);
    this.closeArea();
  }

  protected scrollTabs(dx: number): void {
    this.tabsNav()?.nativeElement.scrollBy({ left: dx, behavior: 'auto' });
  }

  protected openAddApp(): void {
    this.showAddApp.set(true);
  }

  protected closeAddApp(): void {
    this.showAddApp.set(false);
    this.appSearch.set('');
  }

  protected addAppToGroup(ep: PortalEntryPoint): void {
    this.wb.openAppInGroup(ep, this.groupId());
  }

  protected searchApps(event: Event): void {
    const value = (event.target as HTMLInputElement).value;
    this.appSearch.set(value);
  }

  protected closeAllExceptHomeClick(): void {
    this.closeAllExceptHome.emit(this.groupId());
  }

  formatTabId(tab: Tab): string {
    return entryPointId(tab.entryPoint);
  }

  private updateOverflow(): void {
    const el = this.navEl;
    if (!el) return;
    const max = Math.max(0, el.scrollWidth - el.clientWidth);
    this.tabOverflow.set(max > 0);
    this.atStart.set(el.scrollLeft <= 0);
    this.atEnd.set(el.scrollLeft >= max - 1);
  }

  private ensureActiveVisible(): void {
    const el = this.navEl;
    const id = this.activeId();
    if (!el || id == null) return;
    const btn = el.querySelector(`[data-tab-id="${id}"]`) as HTMLElement | null;
    btn?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }
}
