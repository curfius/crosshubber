import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { describe, it, expect, beforeAll, vi } from 'vitest';
import { AppArea } from './tab-area.component';
import { WorkbenchService } from '../features/workspaces/workspaces.store';
import type { PortalEntryPoint } from '../../core/models';

class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

function iframeEp(key: string): PortalEntryPoint {
  return {
    moduleKey: key,
    entryKey: 'main',
    category: 'applications',
    name: key,
    type: 'iframe',
    url: `https://${key}.example.com/index.html`,
    parentEntryKey: null,
    groupKey: null,
    sortOrder: 0,
  };
}

describe('AppArea keep-alive tabs', () => {
  beforeAll(() => {
    vi.stubGlobal('ResizeObserver', ResizeObserverStub);
    Element.prototype.scrollIntoView = () => {};
  });

  it('renders every tab once, hides+inerts inactive ones, and keeps them mounted across switches', () => {
    TestBed.configureTestingModule({ imports: [AppArea] });
    const wb = TestBed.inject(WorkbenchService);
    wb.setEntryPoints([iframeEp('alpha'), iframeEp('beta'), iframeEp('gamma')]);
    wb.openApp(wb.getEntryPoints()[0]);
    wb.openApp(wb.getEntryPoints()[1]);
    wb.openApp(wb.getEntryPoints()[2]);
    const gid = Object.keys(wb.groups())[0];
    const tabs = wb.groups()[gid].tabs;

    const fixture = TestBed.createComponent(AppArea);
    fixture.componentRef.setInput('groupId', gid);
    fixture.componentRef.setInput('user', null);
    fixture.componentRef.setInput('primaryGroupId', gid);
    fixture.componentRef.setInput('editMode', false);
    fixture.componentRef.setInput('hideSingleTabToolbar', false);
    fixture.componentRef.setInput('allEntryPoints', []);
    fixture.detectChanges();

    // All tabs stay mounted (keep-alive), not just the active one.
    expect(fixture.debugElement.queryAll(By.css('iframe')).length).toBe(3);

    // Only the active tab is visible and interactive; inactive are hidden + inert.
    const mountsHost = fixture.debugElement.query(By.css('div.relative.min-h-0')).nativeElement as HTMLElement;
    const wrappers = Array.from(mountsHost.children) as HTMLElement[];
    expect(wrappers.length).toBe(3);
    wrappers.forEach((el, i) => {
      const isActive = tabs[i].id === wb.groups()[gid].activeId;
      expect(el.style.display).toBe(isActive ? '' : 'none');
      expect(el.hasAttribute('inert')).toBe(!isActive);
    });

    // Switching tabs keeps every tab mounted; visibility flips only.
    wb.activate(gid, tabs[0].id, false);
    fixture.detectChanges();
    expect(fixture.debugElement.queryAll(By.css('iframe')).length).toBe(3);
    const visible = wrappers.filter((el) => el.style.display !== 'none');
    expect(visible.length).toBe(1);
    expect(visible[0]).toBe(wrappers[0]);
    expect(wrappers[0].hasAttribute('inert')).toBe(false);
    expect(wrappers[1].hasAttribute('inert')).toBe(true);

    fixture.destroy();
  });
});
