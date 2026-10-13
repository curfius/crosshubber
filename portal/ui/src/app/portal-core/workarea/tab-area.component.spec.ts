import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { describe, it, expect, beforeAll, vi } from 'vitest';
import { AppArea } from './tab-area.component';
import { WorkbenchService } from '../../core/workspaces/workspaces.store';
import { MsgCenterStore } from '../../core/msg-center/msg-center.store';
import type { PortalModuleContent } from '../../core/models';

class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

function iframeEp(key: string): PortalModuleContent {
  return {
    moduleKey: key,
    contentKey: 'main',
    category: 'applications',
    name: key,
    type: 'iframe',
    url: `https://${key}.example.com/index.html`,
    parentContentKey: null,
    groupKey: null,
    sortOrder: 0,
  };
}

function embeddedEp(moduleKey: string, name: string): PortalModuleContent {
  return {
    moduleKey,
    contentKey: 'main',
    category: 'applications',
    name,
    type: 'embedded',
    loadPath: `${moduleKey}-main`,
    parentContentKey: null,
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
    wb.setModuleContents([iframeEp('alpha'), iframeEp('beta'), iframeEp('gamma')]);
    wb.openApp(wb.getModuleContents()[0]);
    wb.openApp(wb.getModuleContents()[1]);
    wb.openApp(wb.getModuleContents()[2]);
    const gid = Object.keys(wb.groups())[0];
    const tabs = wb.groups()[gid].tabs;

    const fixture = TestBed.createComponent(AppArea);
    fixture.componentRef.setInput('groupId', gid);
    fixture.componentRef.setInput('user', null);
    fixture.componentRef.setInput('primaryGroupId', gid);
    fixture.componentRef.setInput('editMode', false);
    fixture.componentRef.setInput('hideSingleTabToolbar', false);
    fixture.componentRef.setInput('allModuleContents', []);
    fixture.detectChanges();

    // All tabs stay mounted (keep-alive), not just the active one.
    expect(fixture.debugElement.queryAll(By.css('iframe')).length).toBe(3);

    // Only the active tab is visible and interactive; inactive are hidden + inert.
    const mountsHost = fixture.debugElement.query(By.css('div.relative.min-h-0'))
      .nativeElement as HTMLElement;
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

describe('AppArea tab badges and tools', () => {
  beforeAll(() => {
    vi.stubGlobal('ResizeObserver', ResizeObserverStub);
    Element.prototype.scrollIntoView = () => {};
  });

  function setupArea(contents: PortalModuleContent[], editMode = true) {
    TestBed.configureTestingModule({ imports: [AppArea] });
    const wb = TestBed.inject(WorkbenchService);
    wb.setModuleContents(contents);
    wb.setHomeApp('home:main');
    wb.ensureHomeTab();
    for (const ep of contents) {
      if (ep.moduleKey !== 'home') wb.openApp(ep, false);
    }
    const gid = wb.primaryGroupId()!;
    const fixture = TestBed.createComponent(AppArea);
    fixture.componentRef.setInput('groupId', gid);
    fixture.componentRef.setInput('user', null);
    fixture.componentRef.setInput('primaryGroupId', gid);
    fixture.componentRef.setInput('editMode', editMode);
    fixture.componentRef.setInput('hideSingleTabToolbar', false);
    fixture.componentRef.setInput('allModuleContents', []);
    fixture.detectChanges();
    return { wb, gid, fixture };
  }

  it('shows the unread badge on the message-center tab only', () => {
    const { fixture } = setupArea([
      embeddedEp('home', 'Home'),
      { ...embeddedEp('msgcenter', 'Inbox'), contentKey: 'main' },
      embeddedEp('docs', 'Docs'),
    ]);
    const mc = TestBed.inject(MsgCenterStore);
    mc.unread.set(4);
    fixture.detectChanges();
    const badges = fixture.debugElement.queryAll(By.css('span.rounded-full'));
    expect(badges.length).toBe(1);
    expect(badges[0].nativeElement.textContent.trim()).toBe('4');
    mc.unread.set(0);
    fixture.detectChanges();
    expect(fixture.debugElement.queryAll(By.css('span.rounded-full')).length).toBe(0);
    fixture.destroy();
  });

  it('shows a pop-out button on iframe tabs that opens the url in a new window', () => {
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);
    const { fixture } = setupArea([
      embeddedEp('home', 'Home'),
      iframeEp('wiki'),
      embeddedEp('docs', 'Docs'),
    ]);
    const popouts = fixture.debugElement.queryAll(By.css('button[title="tabs.popOut"]'));
    expect(popouts.length).toBe(1);
    popouts[0].nativeElement.click();
    expect(openSpy).toHaveBeenCalledWith(
      'https://wiki.example.com/index.html',
      '_blank',
      'noopener',
    );
    openSpy.mockRestore();
    fixture.destroy();
  });

  it('keeps hide-home available but disabled with only Home, and enables close-all in the main area', () => {
    const { wb, gid, fixture } = setupArea([
      embeddedEp('home', 'Home'),
      embeddedEp('docs', 'Docs'),
    ]);
    const toolbar = () => fixture.debugElement.query(By.css('nav div.flex.shrink-0'));
    expect(toolbar()).not.toBeNull();

    const hideHome = () =>
      fixture.debugElement.query(By.css('button[aria-pressed]'))
        ?.nativeElement as HTMLButtonElement;
    const closeAll = () =>
      fixture.debugElement.query(By.css('button[title="tabs.closeAllTabs"]'))
        ?.nativeElement as HTMLButtonElement;
    expect(hideHome()).not.toBeNull();
    expect(hideHome().disabled).toBe(false);
    expect(closeAll()).not.toBeNull();
    expect(closeAll().disabled).toBe(false);

    // Close the only non-home tab: both tools must disable (close keeps its
    // main-area "close all tabs" caption).
    const docsTab = wb.groups()[gid].tabs.find((t) => t.content.moduleKey === 'docs')!;
    wb.closeTab(gid, docsTab.id);
    fixture.detectChanges();
    expect(hideHome().disabled).toBe(true);
    expect(closeAll()).not.toBeNull();
    expect(closeAll().disabled).toBe(true);
    fixture.destroy();
  });
});
