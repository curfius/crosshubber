import { TestBed } from '@angular/core/testing';
import type { CdkDragDrop } from '@angular/cdk/drag-drop';
import { describe, it, expect } from 'vitest';
import { DndTree, type DropListData } from './dnd-tree.component';
import type { EditableTreeNode } from '../../../core/navigation/navigation.models';
import { I18nService } from '../../../core/i18n/i18n.service';
import { ToastService } from '../../../core/toast/toast.service';

interface DndTreeInternals {
  onDrop(event: CdkDragDrop<DropListData>): void;
  moveUp(node: EditableTreeNode): void;
  moveDown(node: EditableTreeNode): void;
}

function folder(id: string, children: EditableTreeNode[] = []): EditableTreeNode {
  return { id, label: id, kind: 'folder', children };
}

function item(id: string): EditableTreeNode {
  return { id, label: id, kind: 'item', children: [] };
}

function intoDrop(parentId: string | null, dragged: EditableTreeNode): CdkDragDrop<DropListData> {
  return {
    item: { data: dragged },
    container: { data: { parentId, into: true } },
    dropPoint: { x: 0, y: 0 },
  } as unknown as CdkDragDrop<DropListData>;
}

function setup(tree: EditableTreeNode[]) {
  TestBed.configureTestingModule({ imports: [DndTree] });
  const fixture = TestBed.createComponent(DndTree);
  fixture.componentRef.setInput('nodes', tree);
  fixture.detectChanges();
  const emitted: EditableTreeNode[][] = [];
  fixture.componentInstance.nodesChange.subscribe((t) => emitted.push(t));
  return {
    fixture,
    emitted,
    toast: TestBed.inject(ToastService),
    i18n: TestBed.inject(I18nService),
    internals: fixture.componentInstance as unknown as DndTreeInternals,
  };
}

describe('DndTree drop placement', () => {
  it('appends a node dropped on a folder strip at the end of its children', () => {
    const tree = [item('x'), folder('f', [item('a'), item('b')])];
    const { emitted, internals } = setup(tree);

    internals.onDrop(intoDrop('f', tree[0]));

    expect(emitted.length).toBe(1);
    expect(emitted[0].map((n) => n.id)).toEqual(['f']);
    expect(emitted[0][0].children.map((n) => n.id)).toEqual(['a', 'b', 'x']);
  });

  it('moves a node re-dropped on its own folder strip to the end', () => {
    const tree = [folder('f', [item('a'), item('b')])];
    const { emitted, internals } = setup(tree);

    internals.onDrop(intoDrop('f', tree[0].children[0]));

    expect(emitted.length).toBe(1);
    expect(emitted[0][0].children.map((n) => n.id)).toEqual(['b', 'a']);
  });

  it('rejects a drop that would exceed the max depth with a toast and no mutation', () => {
    const tree = [folder('f1', [folder('f2', [folder('f3')])]), item('x')];
    const { emitted, toast, i18n, internals } = setup(tree);

    internals.onDrop(intoDrop('f3', tree[1]));

    expect(emitted.length).toBe(0);
    expect(toast.message()).toBe(i18n.t('dnd.moveRejected'));
    toast.dismiss();
  });
});

describe('DndTree keyboard moves', () => {
  it('toasts when a keyboard move is a no-op (already at the top)', () => {
    const tree = [item('a'), item('b')];
    const { emitted, toast, i18n, internals } = setup(tree);

    internals.moveUp(tree[0]);

    expect(emitted.length).toBe(0);
    expect(toast.message()).toBe(i18n.t('dnd.moveRejected'));
    toast.dismiss();
  });

  it('emits a reordered tree for a valid keyboard move', () => {
    const tree = [item('a'), item('b')];
    const { emitted, internals } = setup(tree);

    internals.moveDown(tree[0]);

    expect(emitted.length).toBe(1);
    expect(emitted[0].map((n) => n.id)).toEqual(['b', 'a']);
  });
});

describe('DndTree color picker', () => {
  it('emits colorPick with the clicked item node', () => {
    const tree = [item('a')];
    TestBed.configureTestingModule({ imports: [DndTree] });
    const fixture = TestBed.createComponent(DndTree);
    fixture.componentRef.setInput('nodes', tree);
    fixture.componentRef.setInput('showColorPicker', true);
    fixture.detectChanges();
    const picked: EditableTreeNode[] = [];
    fixture.componentInstance.colorPick.subscribe((n) => picked.push(n));

    const root = fixture.nativeElement as HTMLElement;
    const btn = root.querySelector(
      'button[title*="color" i], button[aria-label*="color" i]',
    ) as HTMLButtonElement | null;
    expect(btn).not.toBeNull();
    btn!.click();
    expect(picked.length).toBe(1);
    expect(picked[0].id).toBe('a');
  });

  it('omits the color button when showColorPicker is off and on folder rows', () => {
    const tree = [folder('f', [item('a')])];
    TestBed.configureTestingModule({ imports: [DndTree] });
    const fixture = TestBed.createComponent(DndTree);
    fixture.componentRef.setInput('nodes', tree);
    fixture.componentRef.setInput('showColorPicker', false);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('button[title*="color" i], button[aria-label*="color" i]').length)
      .toBe(0);
  });
});
