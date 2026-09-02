import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { UserSettingsService } from './user-settings.service';

describe('UserSettingsService', () => {
  let svc: UserSettingsService;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    svc = new UserSettingsService();
    fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('get returns the stored settings document', async () => {
    fetchMock.mockResolvedValue({ ok: true, json: async () => ({ settings: { theme: 'nord' } }) });
    await expect(svc.get('general')).resolves.toEqual({ theme: 'nord' });
    expect(fetchMock).toHaveBeenCalledWith('/api/user-settings/general');
  });

  it('get returns {} on server errors', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 500 });
    await expect(svc.get('general')).resolves.toEqual({});
  });

  it('get returns {} on network failures', async () => {
    fetchMock.mockRejectedValue(new Error('offline'));
    await expect(svc.get('general')).resolves.toEqual({});
  });

  it('getAll returns every scope', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => ({ settings: { general: { theme: 'nord' }, notes: { x: 1 } } }),
    });
    await expect(svc.getAll()).resolves.toEqual({ general: { theme: 'nord' }, notes: { x: 1 } });
    expect(fetchMock).toHaveBeenCalledWith('/api/user-settings');
  });

  it('update PUTs a JSON merge body to the scope endpoint', async () => {
    fetchMock.mockResolvedValue({ ok: true, json: async () => ({ settings: { theme: 'nord', language: 'fr-FR' } }) });
    const merged = await svc.update('general', { language: 'fr-FR' });
    expect(merged).toEqual({ theme: 'nord', language: 'fr-FR' });
    expect(fetchMock).toHaveBeenCalledWith('/api/user-settings/general', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ language: 'fr-FR' }),
    });
  });

  it('update returns {} on 4xx responses', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 400, text: async () => 'bad scope' });
    await expect(svc.update('INVALID!', { theme: 'x' })).resolves.toEqual({});
  });
});
