import { TestBed } from '@angular/core/testing';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { BrandingService } from './branding.service';

function mockFetchOnce(payload: unknown, ok = true): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      new Response(JSON.stringify(payload), {
        status: ok ? 200 : 500,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  );
}

describe('BrandingService', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    document.title = 'orig';
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('applies tenant branding and sets the document title', async () => {
    mockFetchOnce({ name: 'Acme', title: 'Acme Portal', logoUrl: 'https://cdn/logo.png' });
    const svc = TestBed.inject(BrandingService);
    await svc.load();
    expect(svc.branding().name).toBe('Acme');
    expect(svc.branding().title).toBe('Acme Portal');
    expect(svc.branding().logoUrl).toBe('https://cdn/logo.png');
    expect(document.title).toBe('Acme Portal');
  });

  it('falls back to the repo baseline when the request fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{"error":"boom"}', { status: 500 })));
    const svc = TestBed.inject(BrandingService);
    await svc.load();
    expect(svc.branding().name).toBe('Crosshubber');
    expect(svc.branding().title).toBe('Crosshubber Portal');
    expect(document.title).toBe('Crosshubber Portal');
  });

  it('blank fields fall back individually', async () => {
    mockFetchOnce({ name: '  ', title: '' });
    const svc = TestBed.inject(BrandingService);
    await svc.load();
    expect(svc.branding().name).toBe('Crosshubber');
    expect(svc.branding().title).toBe('Crosshubber Portal');
  });
});
