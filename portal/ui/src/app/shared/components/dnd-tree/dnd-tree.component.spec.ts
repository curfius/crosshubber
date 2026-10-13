import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
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
  moveOut(node: EditableTreeNode): void;
  moveIn(node: EditableTreeNode): void;
  canMoveIn(node: EditableTreeNode): boolean;
  performDelete(node: EditableTreeNode): void;
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

function rootStripDrop(dragged: EditableTreeNode): CdkDragDrop<DropListData> {
  return {
    item: { data: dragged },
    container: { data: { parentId: null, into: true, append: true } },
    dropPoint: { x: 0, y: 0 },
  } as unknown as CdkDragDrop<DropListData>;
}

function rowDrop(
  parentId: string | null,
  nodeId: string,
  targetKind: 'folder' | 'item',
  dragged: EditableTreeNode,
  shift = false,
): CdkDragDrop<DropListData> {
  return {
    item: { data: dragged },
    container: { data: { parentId, nodeId, targetKind } },
    dropPoint: { x: 0, y: 0 },
    event: { shiftKey: shift },
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

describe('DndTree folder-target drop semantics', () => {
  it('moves a node dropped on a section row INSIDE the section by default', () => {
    const tree = [item('other'), folder('f', [item('a')])];
    const { emitted, internals } = setup(tree);

    internals.onDrop(rowDrop(null, 'f', 'folder', tree[0]));

    expect(emitted.length).toBe(1);
    expect(emitted[0].map((n) => n.id)).toEqual(['f']);
    expect(emitted[0][0].children.map((n) => n.id)).toEqual(['a', 'other']);
  });

  it('places a node BELOW a section when dropping with Shift', () => {
    const tree = [folder('f', [item('a')]), item('x')];
    const { emitted, internals } = setup(tree);

    internals.onDrop(rowDrop(null, 'f', 'folder', tree[1], true));

    expect(emitted.length).toBe(1);
    expect(emitted[0].map((n) => n.id)).toEqual(['f', 'x']);
    expect(emitted[0][0].children.map((n) => n.id)).toEqual(['a']);
  });

  it('keeps midpoint before/after placement for item-row targets', () => {
    const tree = [folder('f'), item('a'), item('x')];
    const { emitted, internals } = setup(tree);

    internals.onDrop(rowDrop(null, 'a', 'item', tree[2]));

    expect(emitted.length).toBe(1);
    expect(emitted[0].map((n) => n.id)).toEqual(['f', 'x', 'a']);
  });

  it('rejects items dropped at the root level when itemsRequireFolder is on', () => {
    const tree = [folder('f', [item('a')])];
    TestBed.configureTestingModule({ imports: [DndTree] });
    const fixture = TestBed.createComponent(DndTree);
    fixture.componentRef.setInput('nodes', tree);
    fixture.componentRef.setInput('itemsRequireFolder', true);
    fixture.detectChanges();
    const emitted: EditableTreeNode[][] = [];
    fixture.componentInstance.nodesChange.subscribe((t) => emitted.push(t));
    const toast = TestBed.inject(ToastService);
    const i18n = TestBed.inject(I18nService);
    const internals = fixture.componentInstance as unknown as DndTreeInternals;

    internals.onDrop(rootStripDrop(tree[0].children[0]));

    expect(emitted.length).toBe(0);
    expect(toast.message()).toBe(i18n.t('dnd.rootNotAllowed'));
    toast.dismiss();
  });

  it('rejects moving an item out to the root level when itemsRequireFolder is on', () => {
    const tree = [folder('f', [item('a')])];
    TestBed.configureTestingModule({ imports: [DndTree] });
    const fixture = TestBed.createComponent(DndTree);
    fixture.componentRef.setInput('nodes', tree);
    fixture.componentRef.setInput('itemsRequireFolder', true);
    fixture.detectChanges();
    const emitted: EditableTreeNode[][] = [];
    fixture.componentInstance.nodesChange.subscribe((t) => emitted.push(t));
    const toast = TestBed.inject(ToastService);
    const i18n = TestBed.inject(I18nService);
    const internals = fixture.componentInstance as unknown as DndTreeInternals;

    internals.moveOut(tree[0].children[0]);

    expect(emitted.length).toBe(0);
    expect(toast.message()).toBe(i18n.t('dnd.rootNotAllowed'));
    toast.dismiss();
  });
});

describe('DndTree move-in', () => {
  it('offers and performs move-in for an item facing a 2nd-level section', () => {
    // x is a level-2 item; f2 is a 2nd-level section that precedes it.
    const tree = [folder('f1', [folder('f2', [item('a')]), item('x')])];
    const { emitted, internals } = setup(tree);

    expect(internals.canMoveIn(tree[0].children[1])).toBe(true);

    internals.moveIn(tree[0].children[1]);

    expect(emitted.length).toBe(1);
    const f2 = emitted[0][0].children[0];
    expect(f2.children.map((n) => n.id)).toEqual(['a', 'x']);
  });

  it('hides move-in when no previous sibling folder exists', () => {
    const tree = [folder('f1', [item('only')])];
    const { internals } = setup(tree);

    expect(internals.canMoveIn(tree[0].children[0])).toBe(false);
  });

  it('hides move-in when the move would exceed the depth cap', () => {
    const tree = [folder('f1', [folder('f2', [item('x'), folder('f3')])])];
    const { internals } = setup(tree);

    // Moving x into f3 would create a level-4 node (cap is 3).
    expect(internals.canMoveIn(tree[0].children[0].children[0])).toBe(false);
  });
});

describe('DndTree section deletion re-parenting', () => {
  it('surfaces children of a nested section to its parent level', () => {
    const tree = [folder('f1', [folder('f2', [item('a')])]), folder('f3')];
    const { emitted, internals } = setup(tree);

    internals.performDelete(tree[0].children[0]);

    expect(emitted.length).toBe(1);
    expect(emitted[0].map((n) => n.id)).toEqual(['f1', 'f3']);
    expect(emitted[0][0].children.map((n) => n.id)).toEqual(['a']);
  });

  it('re-parents item children into the nearest sibling folder instead of the root', () => {
    const tree = [folder('f1', [item('a')]), folder('f2', [item('b')])];
    const { emitted, internals } = setup(tree);

    internals.performDelete(tree[0]);

    expect(emitted.length).toBe(1);
    expect(emitted[0].map((n) => n.id)).toEqual(['f2']);
    expect(emitted[0][0].children.map((n) => n.id)).toEqual(['b', 'a']);
  });

  it('surfaces folder children to the root level', () => {
    const tree = [folder('f1', [folder('nested')]), folder('f3')];
    const { emitted, internals } = setup(tree);

    internals.performDelete(tree[0]);

    expect(emitted.length).toBe(1);
    expect(emitted[0].map((n) => n.id)).toEqual(['nested', 'f3']);
  });

  it('rejects deleting a section that would orphan its items (no sibling folder)', () => {
    const tree = [folder('f1', [item('a')])];
    const { emitted, toast, i18n, internals } = setup(tree);

    internals.performDelete(tree[0]);

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

    expect(
      fixture.nativeElement.querySelectorAll(
        'button[title*="color" i], button[aria-label*="color" i]',
      ).length,
    ).toBe(0);
  });
});

describe('DndTree section row expand/collapse', () => {
  function setupRow() {
    TestBed.configureTestingModule({ imports: [DndTree] });
    const fixture = TestBed.createComponent(DndTree);
    fixture.componentRef.setInput('nodes', [folder('f', [item('a')])]);
    fixture.detectChanges();
    return { fixture };
  }

  it('expands and collapses a section when its row is clicked', () => {
    const { fixture } = setupRow();
    const row = fixture.debugElement.query(By.css('.dnd-node-main'));
    expect(fixture.debugElement.query(By.css('[data-node-id="a"]'))).toBeNull();

    row.nativeElement.click();
    fixture.detectChanges();
    expect(fixture.debugElement.query(By.css('[data-node-id="a"]'))).not.toBeNull();

    row.nativeElement.click();
    fixture.detectChanges();
    expect(fixture.debugElement.query(By.css('[data-node-id="a"]'))).toBeNull();
    fixture.destroy();
  });

  it('starts a rename instead of toggling when the section label is clicked', () => {
    const { fixture } = setupRow();
    fixture.debugElement.query(By.css('.dnd-label')).nativeElement.click();
    fixture.detectChanges();

    expect(fixture.debugElement.query(By.css('.dnd-rename-input'))).not.toBeNull();
    expect(fixture.debugElement.query(By.css('[data-node-id="a"]'))).toBeNull();
    fixture.destroy();
  });

  it('does not toggle when a row action button is clicked', () => {
    const { fixture } = setupRow();
    // Rename pencil: keeps its own behaviour, must not expand the section.
    fixture.debugElement.query(By.css('button[aria-label="dnd.rename"]')).nativeElement.click();
    fixture.detectChanges();
    expect(fixture.debugElement.query(By.css('[data-node-id="a"]'))).toBeNull();
    expect(fixture.debugElement.query(By.css('.dnd-rename-input'))).not.toBeNull();
    fixture.destroy();
  });

  it('still emits itemClick when an item row is clicked', () => {
    TestBed.configureTestingModule({ imports: [DndTree] });
    const fixture = TestBed.createComponent(DndTree);
    fixture.componentRef.setInput('nodes', [item('a')]);
    fixture.detectChanges();
    const clicked: EditableTreeNode[] = [];
    fixture.componentInstance.itemClick.subscribe((n) => clicked.push(n));

    fixture.debugElement.query(By.css('.dnd-node-main')).nativeElement.click();
    expect(clicked.map((n) => n.id)).toEqual(['a']);
    fixture.destroy();
  });
});
