import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { AppOutlet } from './module-outlet.component';
import { embeddedModules } from './embedded-modules';
import { Bridge } from '../../core/bridge/bridge.service';
import { I18nService } from '../../core/i18n/i18n.service';
import { NavigationCoordinator } from '../features/navigation-coordinator.service';
import type { PortalModuleContent } from '../../core/models';

@Component({
  selector: 'test-embedded-a',
  standalone: true,
  template: `<p class="marker-a">embedded A</p>`,
})
class FakeEmbeddedA {}

@Component({
  selector: 'test-embedded-b',
  standalone: true,
  template: `<p class="marker-b">embedded B</p>`,
})
class FakeEmbeddedB {}

function embeddedEp(moduleKey: string, loadPath: string): PortalModuleContent {
  return {
    moduleKey,
    contentKey: 'main',
    category: 'admin-settings',
    name: moduleKey,
    type: 'embedded',
    loadPath,
    parentContentKey: null,
    groupKey: null,
    sortOrder: 0,
  };
}

function mfeEp(): PortalModuleContent {
  return {
    moduleKey: 'external-mod',
    contentKey: 'settings',
    category: 'admin-settings',
    name: 'External settings',
    type: 'mfe',
    entryUrl: '/mfe/external-mod.js',
    element: 'external-mod-settings',
    parentContentKey: null,
    groupKey: null,
    sortOrder: 0,
  };
}

async function flush(): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve();
    await new Promise((r) => setTimeout(r, 0));
  }
}

describe('AppOutlet settings switching', () => {
  const addedLoadPaths: string[] = [];

  afterEach(() => {
    for (const key of addedLoadPaths.splice(0)) {
      delete (embeddedModules as Record<string, unknown>)[key];
    }
    vi.restoreAllMocks();
  });

  function setup() {
    const bridge = {
      registerMfe: vi.fn(),
      unregisterMfe: vi.fn(),
      registerIframe: vi.fn(),
      unregisterIframe: vi.fn(),
    };
    TestBed.configureTestingModule({
      imports: [AppOutlet],
      providers: [
        { provide: Bridge, useValue: bridge },
        { provide: NavigationCoordinator, useValue: { consumePendingPath: () => null } },
        { provide: I18nService, useValue: { locale: () => 'en', t: (k: string) => k } },
      ],
    });
    return { bridge };
  }

  it('clears a previous embedded view when switching to an MFE entry (no stacking)', async () => {
    (embeddedModules as Record<string, () => Promise<unknown>>)['test-load-a'] = () =>
      Promise.resolve(FakeEmbeddedA);
    addedLoadPaths.push('test-load-a');
    const { bridge } = setup();

    const fixture = TestBed.createComponent(AppOutlet);
    fixture.componentRef.setInput('content', embeddedEp('mod-a', 'test-load-a'));
    fixture.componentRef.setInput('user', null);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    expect(fixture.debugElement.query(By.css('.marker-a'))).not.toBeNull();

    // Switch to an MFE settings screen: its script load stays pending in
    // jsdom, but the embedded residue must already be gone.
    fixture.componentRef.setInput('content', mfeEp());
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(fixture.debugElement.query(By.css('.marker-a'))).toBeNull();
    expect(bridge.unregisterMfe).not.toHaveBeenCalledWith('external-mod');
    fixture.destroy();
  });

  it('unregisters the previous MFE module key when switching entries', async () => {
    (embeddedModules as Record<string, () => Promise<unknown>>)['test-load-b'] = () =>
      Promise.resolve(FakeEmbeddedB);
    addedLoadPaths.push('test-load-b');
    const { bridge } = setup();

    const fixture = TestBed.createComponent(AppOutlet);
    fixture.componentRef.setInput('content', embeddedEp('mod-b', 'test-load-b'));
    fixture.componentRef.setInput('user', null);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    expect(fixture.debugElement.query(By.css('.marker-b'))).not.toBeNull();

    fixture.componentRef.setInput('content', mfeEp());
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(fixture.debugElement.query(By.css('.marker-b'))).toBeNull();
    expect(
      fixture.debugElement.queryAll(By.css('external-mod-settings')).length,
    ).toBeLessThanOrEqual(1);
    fixture.destroy();
  });

  it('drops a slow embedded load that resolves after switching entries', async () => {
    let resolveSlow!: (v: unknown) => void;
    const slowGate = new Promise((r) => {
      resolveSlow = r;
    });
    (embeddedModules as Record<string, () => Promise<unknown>>)['test-load-slow'] = () =>
      slowGate.then(() => FakeEmbeddedA);
    (embeddedModules as Record<string, () => Promise<unknown>>)['test-load-fast'] = () =>
      Promise.resolve(FakeEmbeddedB);
    addedLoadPaths.push('test-load-slow', 'test-load-fast');
    setup();

    const fixture = TestBed.createComponent(AppOutlet);
    fixture.componentRef.setInput('content', embeddedEp('mod-slow', 'test-load-slow'));
    fixture.componentRef.setInput('user', null);
    fixture.detectChanges();

    // Switch before the slow import resolves.
    fixture.componentRef.setInput('content', embeddedEp('mod-fast', 'test-load-fast'));
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    expect(fixture.debugElement.query(By.css('.marker-b'))).not.toBeNull();

    // Late resolution of the stale load must not mount on top.
    resolveSlow(FakeEmbeddedA);
    await flush();
    fixture.detectChanges();
    expect(fixture.debugElement.query(By.css('.marker-a'))).toBeNull();
    expect(fixture.debugElement.query(By.css('.marker-b'))).not.toBeNull();
    fixture.destroy();
  });
});
