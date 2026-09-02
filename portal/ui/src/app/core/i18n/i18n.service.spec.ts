import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Bridge } from '../bridge/bridge.service';
import { I18nService, type I18nPortalConfig } from './i18n.service';

function makeConfig(overrides: Partial<I18nPortalConfig> = {}): I18nPortalConfig {
  return {
    languages: [
      { code: 'en-GB', name: 'English', nativeName: 'English (UK)', enabled: true, seeded: true, sortOrder: 10 },
      { code: 'pt-PT', name: 'Portuguese', nativeName: 'Português', enabled: true, seeded: true, sortOrder: 20 },
      { code: 'fr-FR', name: 'French', nativeName: 'Français', enabled: false, seeded: true, sortOrder: 30 },
    ],
    defaultLanguage: 'en-GB',
    fallbackLanguage: 'en-GB',
    overrides: {},
    contentVersion: 1,
    ...overrides,
  };
}

const BUNDLES: Record<string, Record<string, string>> = {
  'en-GB': { 'shell.title': 'Portal', 'common.save': 'Save' },
  'pt-PT': { 'common.save': 'Guardar' },
  'fr-FR': { 'common.save': 'Enregistrer' },
};

let config: I18nPortalConfig;
let broadcastLanguage: ReturnType<typeof vi.fn<(code: string) => void>> | null = null;

function bridgeMock(): { ready: { add: (cb: () => void) => void }; broadcastLanguage: (code: string) => void } {
  broadcastLanguage = vi.fn<(code: string) => void>();
  return { ready: { add: () => {} }, broadcastLanguage };
}

function fetchMock(url: string): Promise<{ ok: boolean; json: () => Promise<unknown> }> {
  if (url.startsWith('/api/i18n/config')) return Promise.resolve({ ok: true, json: async () => config });
  const code = url.split('/').pop() ?? '';
  const labels = BUNDLES[code];
  if (!labels) return Promise.resolve({ ok: false, json: async () => ({}) });
  return Promise.resolve({ ok: true, json: async () => ({ language: code, version: config.contentVersion, labels }) });
}

async function freshService(): Promise<I18nService> {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: Bridge, useValue: bridgeMock() }] });
  return TestBed.inject(I18nService);
}

describe('I18nService', () => {
  let fetchSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    localStorage.clear();
    history.replaceState(null, '', location.pathname);
    config = makeConfig();
    fetchSpy = vi.spyOn(window, 'fetch').mockImplementation(async (input) => fetchMock(String(input)) as never);
  });

  afterEach(() => {
    fetchSpy.mockRestore();
  });

  it('translates via the merged bundle and leaves missing keys raw', async () => {
    const svc = await freshService();
    await svc.init();
    expect(svc.locale()).toBe('en-GB');
    expect(svc.t('shell.title')).toBe('Portal');
    expect(svc.t('missing.key')).toBe('missing.key');
  });

  it('falls back to the default language for keys missing in the active language', async () => {
    localStorage.setItem('portal-language', 'pt-PT');
    const svc = await freshService();
    await svc.init();
    expect(svc.locale()).toBe('pt-PT');
    expect(svc.t('common.save')).toBe('Guardar');
    expect(svc.t('shell.title')).toBe('Portal');
  });

  it('interpolates {params} into translated labels', async () => {
    BUNDLES['en-GB']['greet'] = 'Hello, {name}!';
    try {
      const svc = await freshService();
      await svc.init();
      expect(svc.t('greet', { name: 'Ada' })).toBe('Hello, Ada!');
    } finally {
      delete BUNDLES['en-GB']['greet'];
    }
  });

  it('caches bundles in localStorage keyed by content version', async () => {
    const svc = await freshService();
    await svc.init();
    const cached = JSON.parse(localStorage.getItem('portal.i18n.labels.en-GB') ?? '{}');
    expect(cached.version).toBe(1);
    expect(cached.labels['shell.title']).toBe('Portal');
  });

  it('serves bundles from the localStorage cache when the content version matches', async () => {
    localStorage.setItem(
      'portal.i18n.labels.en-GB',
      JSON.stringify({ version: 1, labels: { 'cached.key': 'cached' } }),
    );
    const svc = await freshService();
    await svc.init();
    const labelCalls = fetchSpy.mock.calls.filter((call: unknown[]) => String(call[0]).includes('/api/i18n/labels/'));
    expect(labelCalls.length).toBe(0);
    expect(svc.t('cached.key')).toBe('cached');
  });

  it('setLanguage persists the choice, applies <html lang> and broadcasts; disabled languages are ignored', async () => {
    const svc = await freshService();
    await svc.init();
    svc.setLanguage('fr-FR');
    expect(localStorage.getItem('portal-language')).toBeNull();
    expect(broadcastLanguage).not.toHaveBeenCalled();
    svc.setLanguage('pt-PT');
    expect(localStorage.getItem('portal-language')).toBe('pt-PT');
    expect(svc.locale()).toBe('pt-PT');
    expect(document.documentElement.getAttribute('lang')).toBe('pt-PT');
    expect(broadcastLanguage).toHaveBeenCalledWith('pt-PT');  });
});
