import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RegistryApiError, RegistryService } from './module-registry.store';

/**
 * Regression guard for the GET+body fetch bug: every registry write must send an explicit
 * HTTP verb (Chrome rejects `fetch` calls whose GET/HEAD request carries a body).
 */
describe('RegistryService verbs', () => {
  let service: RegistryService;
  let fetchStub: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    service = new RegistryService();
    fetchStub = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({}), { status: 200 }),
    );
    vi.stubGlobal('fetch', fetchStub);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function lastInit(): RequestInit {
    return fetchStub.mock.calls.at(-1)![1] as RequestInit;
  }

  it('fetchManifestFromUrl sends POST with a JSON body', async () => {
    await service.fetchManifestFromUrl('http://solutions:8090');
    const init = lastInit();
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ url: 'http://solutions:8090' });
  });

  it('installManifest sends POST with the manifest envelope', async () => {
    const manifest = { manifestVersion: 1, key: 'x', name: 'X', baseUrl: 'http://x:1' };
    await service.installManifest(manifest as never);
    const init = lastInit();
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ manifest });
  });

  it('setModuleActive sends PATCH', async () => {
    await service.setModuleActive('x', true);
    const init = lastInit();
    expect(init.method).toBe('PATCH');
    expect(JSON.parse(init.body as string)).toEqual({ active: true });
  });

  it('saveDraft sends PUT', async () => {
    const manifest = { manifestVersion: 1, key: 'x', name: 'X', baseUrl: 'http://x:1' };
    await service.saveDraft('x', manifest as never);
    const init = lastInit();
    expect(init.method).toBe('PUT');
    expect(JSON.parse(init.body as string)).toEqual({ manifest });
  });

  it('no registry write ever issues a GET with a body', async () => {
    const manifest = { manifestVersion: 1, key: 'x', name: 'X', baseUrl: 'http://x:1' };
    await service.fetchManifestFromUrl('http://x:1');
    await service.installManifest(manifest as never);
    await service.saveModule({ key: 'x', name: 'X' } as never);
    await service.setModuleActive('x', false);
    await service.saveModuleContent({ moduleKey: 'x', contentKey: 'main', category: 'applications', name: 'M', type: 'iframe', url: 'http://x:1' } as never);
    await service.reorderModuleContents([1, 2]);
    await service.saveGroup({ groupKey: 'g', category: 'applications', name: 'G' });
    await service.reorderGroups(['g']);
    await service.saveDraft('x', manifest as never);
    await service.applyDraft('x', manifest as never);
    for (const call of fetchStub.mock.calls) {
      const init = (call[1] ?? {}) as RequestInit;
      const method = (init.method ?? 'GET').toUpperCase();
      if (method === 'GET' || method === 'HEAD') {
        expect(init.body, `GET/HEAD with body on ${call[0]}`).toBeUndefined();
      }
    }
  });
});

/**
 * The install wizard localizes on `code` and renders `issues[]`, so both must survive the shared
 * request path; the raw `{"error"}` text stays the message for every other call site.
 */
describe('RegistryApiError', () => {
  let service: RegistryService;
  let fetchStub: ReturnType<typeof vi.fn>;

  const SERVER_TEXT = 'could not fetch http://localhost:28092/: connect ECONNREFUSED';

  beforeEach(() => {
    service = new RegistryService();
    fetchStub = vi.fn();
    vi.stubGlobal('fetch', fetchStub);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  function respond(status: number, body: unknown): void {
    fetchStub.mockResolvedValue(new Response(JSON.stringify(body), { status }));
  }

  async function capture(promise: Promise<unknown>): Promise<unknown> {
    try {
      await promise;
      return undefined;
    } catch (err) {
      return err;
    }
  }

  it('carries status, message and code from a failed manifest fetch', async () => {
    respond(502, { error: SERVER_TEXT, code: 'unreachable' });

    const caught = await capture(service.fetchManifestFromUrl('http://localhost:28092/'));

    expect(caught).toBeInstanceOf(RegistryApiError);
    const err = caught as RegistryApiError;
    expect(err.status).toBe(502);
    expect(err.message).toBe(SERVER_TEXT);
    expect(err.code).toBe('unreachable');
    expect(err.issues).toBeUndefined();
  });

  it('carries the 422 validation issues for an invalid manifest', async () => {
    const issues = ['content.applications[0].url must be http(s)'];
    respond(422, { error: 'the manifest is not valid', code: 'invalid-manifest', issues });

    const caught = await capture(
      service.installManifest({ manifestVersion: 1, key: 'x', name: 'X', baseUrl: 'http://x:1' } as never),
    );

    const err = caught as RegistryApiError;
    expect(err).toBeInstanceOf(RegistryApiError);
    expect(err.status).toBe(422);
    expect(err.code).toBe('invalid-manifest');
    expect(err.issues).toEqual(issues);
  });

  it('falls back to the endpoint message when the error body is not JSON', async () => {
    fetchStub.mockResolvedValue(new Response('<html>502</html>', { status: 502 }));

    const caught = await capture(service.fetchManifestFromUrl('http://x:1'));

    const err = caught as RegistryApiError;
    expect(err).toBeInstanceOf(RegistryApiError);
    expect(err.message).toBe('fetch failed');
    expect(err.code).toBeUndefined();
    expect(err.issues).toBeUndefined();
  });

  it('resolves the manifest on 200', async () => {
    const manifest = { manifestVersion: 1, key: 'x', name: 'X', baseUrl: 'http://x:1' };
    respond(200, { manifest });

    await expect(service.fetchManifestFromUrl('http://x:1')).resolves.toEqual({ manifest });
  });
});
