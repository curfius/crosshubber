import { Component, computed, inject, input } from '@angular/core';
import { AppArea } from './tab-area.component';
import type { PortalEntryPoint, PortalUser, LayoutNode, LeafNode, SplitNode } from '../../core/models';
import { WorkbenchService } from '../features/workspaces/workspaces.store';

@Component({
  selector: 'app-layout',
  imports: [AppArea, AppLayout],
  templateUrl: './split-layout.component.html',
  styleUrl: './split-layout.component.css',
})
export class AppLayout {
  private readonly wb = inject(WorkbenchService);

  readonly node = input.required<LayoutNode>();
  readonly user = input<PortalUser | null>(null);
  readonly primaryGroupId = input<string | null>(null);
  readonly editMode = input<boolean>(false);
  readonly hideSingleTabToolbar = input<boolean>(false);
  readonly allEntryPoints = input<PortalEntryPoint[]>([]);

  protected readonly isSplit = computed(() => this.node().kind === 'split');
  protected readonly splitNode = computed(() => this.node() as SplitNode);
  protected readonly leafNode = computed(() => this.node() as LeafNode);
  protected readonly row = computed(() => this.splitNode().dir === 'row');
  protected readonly ratioA = computed(() => this.splitNode().ratio);
  protected readonly ratioB = computed(() => 1 - this.splitNode().ratio);

  private container: HTMLElement | null = null;

  protected onDividerDown(event: PointerEvent): void {
    const host = event.currentTarget as HTMLElement;
    this.container = host.parentElement;
    host.setPointerCapture?.(event.pointerId);
    host.addEventListener('pointermove', this.onMove);
    host.addEventListener('pointerup', this.onUp);
    host.addEventListener('pointercancel', this.onUp);
    event.preventDefault();
  }

  protected nudge(delta: number): void {
    this.wb.resizeSplit(this.splitNode().id, this.splitNode().ratio + delta);
  }

  protected onCloseAllExceptHome(groupId: string): void {
    this.wb.closeAllExceptHome(groupId);
  }

  private readonly onMove = (e: PointerEvent): void => {
    if (!this.container) return;
    const rect = this.container.getBoundingClientRect();
    const isRow = this.splitNode().dir === 'row';
    const pos = isRow ? e.clientX - rect.left : e.clientY - rect.top;
    const size = isRow ? rect.width : rect.height;
    if (size <= 0) return;
    this.wb.resizeSplit(this.splitNode().id, pos / size);
  };

  private readonly onUp = (e: PointerEvent): void => {
    const host = e.currentTarget as HTMLElement;
    host.removeEventListener('pointermove', this.onMove);
    host.removeEventListener('pointerup', this.onUp);
    host.removeEventListener('pointercancel', this.onUp);
    host.releasePointerCapture?.(e.pointerId);
    this.container = null;
  };
}
