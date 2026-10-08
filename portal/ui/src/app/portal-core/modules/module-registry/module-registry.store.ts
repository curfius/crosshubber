import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';
import type {
  ModulePayload,
  ModuleType,
  PortalModuleContent,
  NavigationGroup,
  EntryCategory,
  PortalModuleManifest,
  VersionOutput,
} from '../../../core/models';
import { EMBEDDED_LOAD_PATHS } from '../../workarea/embedded-modules';

export interface ModuleOutput {
  key: string;
  name: string;
  icon?: string;
  active: boolean;
  builtin: boolean;
  managedBy?: string;
  roles?: string[];
  baseUrl?: string | null;
  health?: string | null;
}

export interface ModuleContentOutput {
  id: number;
  moduleKey: string;
  contentKey: string;
  category: EntryCategory;
  name: string;
  description?: string;
  type: ModuleType;
  url?: string;
  sandbox?: string[];
  allow?: string;
  loadPath?: string;
  entryUrl?: string;
  element?: string;
  parentContentKey?: string | null;
  groupKey?: string | null;
  sortOrder: number;
  roles?: string[];
  active: boolean;
  icon?: string;
  color?: string;
  multi: boolean;
}

/** Envelope every registry endpoint answers with: the resource payload plus `{"error"}`/`{"code"}`. */
type Envelope = Record<string, unknown> & { error?: string; code?: string; issues?: string[] };

/**
 * Non-2xx registry failure. `message` is the server `{"error"}` text (what callers rendered
 * before); `code` and `issues` are the machine-readable extras the install wizard localizes on.
 */
export class RegistryApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly code?: string,
    readonly issues?: string[],
  ) {
    super(message);
    this.name = 'RegistryApiError';
  }
}

@Injectable({ providedIn: 'root' })
export class RegistryService {
  readonly changed = new Subject<void>();

  /**
   * Shared request path: fetch, parse JSON, throw a {@link RegistryApiError} carrying the server
   * `{"error":"..."}` message (or `fallback`) plus its `code`/`issues`, and emit `changed` so the
   * shell refreshes registry data.
   */
  private async request(
    url: string,
    init: RequestInit | undefined,
    fallback: string,
  ): Promise<Envelope> {
    const res = await fetch(url, init);
    const body = (await res.json().catch(() => ({}))) as Envelope;
    if (!res.ok) {
      throw new RegistryApiError(res.status, body.error ?? fallback, body.code, body.issues);
    }
    return body;
  }

  private moduleUrl(key: string): string {
    return `/api/registry/modules/${encodeURIComponent(key)}`;
  }

  private groupUrl(key: string): string {
    return `/api/navigation/groups/${encodeURIComponent(key)}`;
  }

  // ── Modules ────────────────────────────────────────────────────────

  async listModules(): Promise<ModuleOutput[]> {
    const body = await this.request('/api/registry/modules', undefined, 'unauthorized');
    return (body as { modules: ModuleOutput[] }).modules;
  }

  async saveModule(input: ModulePayload): Promise<ModuleOutput> {
    const body = await this.request('/api/registry/modules', this.jsonInit('POST', input), 'save failed');
    this.changed.next();
    return (body as { module: ModuleOutput }).module;
  }

  async removeModule(key: string): Promise<void> {
    await this.request(this.moduleUrl(key), { method: 'DELETE' }, 'delete failed');
    this.changed.next();
  }

  async setModuleActive(key: string, active: boolean): Promise<void> {
    await this.request(`${this.moduleUrl(key)}/active`, this.jsonInit('PATCH', { active }), 'update failed');
    this.changed.next();
  }

  // ── Module Content ───────────────────────────────────────────────────

  async listAllModuleContents(): Promise<ModuleContentOutput[]> {
    const body = await this.request('/api/registry/module-contents', undefined, 'unauthorized');
    return (body as { moduleContents: ModuleContentOutput[] }).moduleContents;
  }

  async listModuleContents(moduleKey?: string): Promise<ModuleContentOutput[]> {
    const url = moduleKey
      ? `/api/registry/module-contents?moduleKey=${encodeURIComponent(moduleKey)}`
      : '/api/registry/module-contents';
    const body = await this.request(url, undefined, 'unauthorized');
    return (body as { moduleContents: ModuleContentOutput[] }).moduleContents;
  }

  async saveModuleContent(input: {
    moduleKey: string;
    contentKey: string;
    category: EntryCategory;
    name: string;
    description?: string;
    type: ModuleType;
    url?: string;
    sandbox?: string[];
    allow?: string;
    loadPath?: string;
    entryUrl?: string;
    element?: string;
    parentContentKey?: string | null;
    groupKey?: string | null;
    sortOrder?: number;
    active?: boolean;
    color?: string;
    multi?: boolean;
  }): Promise<ModuleContentOutput> {
    const body = await this.request('/api/registry/module-contents', this.jsonInit('POST', input), 'save failed');
    this.changed.next();
    return (body as { moduleContent: ModuleContentOutput }).moduleContent;
  }

  async removeModuleContent(id: number): Promise<void> {
    await this.request(`/api/registry/module-contents/${id}`, { method: 'DELETE' }, 'delete failed');
    this.changed.next();
  }

  async reorderModuleContents(ids: number[]): Promise<void> {
    await this.request('/api/registry/module-contents/reorder', this.jsonInit('POST', { ids }), 'reorder failed');
    this.changed.next();
  }

  // ── Navigation Groups ─────────────────────────────────────────────

  async listGroups(category?: string): Promise<NavigationGroup[]> {
    const url = category
      ? `/api/navigation/groups?category=${encodeURIComponent(category)}`
      : '/api/navigation/groups';
    const body = await this.request(url, undefined, 'unauthorized');
    return (body as { groups: NavigationGroup[] }).groups;
  }

  async saveGroup(input: {
    groupKey: string;
    category: EntryCategory;
    name: string;
    parentKey?: string | null;
    sortOrder?: number;
    icon?: string;
  }): Promise<NavigationGroup> {
    const body = await this.request('/api/navigation/groups', this.jsonInit('POST', input), 'save failed');
    this.changed.next();
    return (body as { group: NavigationGroup[] } & { group: NavigationGroup }).group;
  }

  async removeGroup(groupKey: string): Promise<void> {
    await this.request(this.groupUrl(groupKey), { method: 'DELETE' }, 'delete failed');
    this.changed.next();
  }

  async reorderGroups(keys: string[]): Promise<void> {
    await this.request('/api/navigation/groups/reorder', this.jsonInit('POST', { keys }), 'reorder failed');
    this.changed.next();
  }

  // ── Install Wizard ──────────────────────────────────────────────────

  async fetchManifestFromUrl(url: string): Promise<{ manifest: PortalModuleManifest }> {
    const body = await this.request('/api/registry/fetch', this.jsonInit('POST', { url }), 'fetch failed');
    return { manifest: (body as { manifest: PortalModuleManifest }).manifest };
  }

  async fetchManifestFromJson(json: string): Promise<{ manifest: PortalModuleManifest }> {
    const raw = JSON.parse(json) as unknown;
    if (!raw || typeof raw !== 'object') throw new Error('invalid JSON: not an object');
    const m = raw as Record<string, unknown>;
    if (m['manifestVersion'] !== 1) throw new Error('manifestVersion must be 1');
    if (typeof m['key'] !== 'string' || !m['key']) throw new Error('key is required');
    if (typeof m['name'] !== 'string' || !m['name']) throw new Error('name is required');
    if (typeof m['baseUrl'] !== 'string' || !m['baseUrl']) throw new Error('baseUrl is required');
    try { new URL(String(m['baseUrl'])); } catch { throw new Error('baseUrl must be a valid URL'); }
    if (m['content'] && typeof m['content'] === 'object') {
      const c = m['content'] as Record<string, unknown>;
      for (const group of ['applications', 'features', 'adminSettings', 'userSettings']) {
        if (c[group] !== undefined && !Array.isArray(c[group])) {
          throw new Error(`content.${group} must be an array`);
        }
      }
    }
    return { manifest: raw as PortalModuleManifest };
  }

  async installManifest(manifest: PortalModuleManifest): Promise<{ ok: boolean; moduleKey: string; version: string }> {
    const body = await this.request('/api/registry/install', this.jsonInit('POST', { manifest }), 'install failed');
    this.changed.next();
    const payload = body as { moduleKey?: string; version?: string };
    return { ok: true, moduleKey: payload.moduleKey!, version: payload.version! };
  }

  async listVersions(moduleKey: string): Promise<VersionOutput[]> {
    const body = await this.request(
      `/api/registry/versions/${encodeURIComponent(moduleKey)}`, undefined, 'failed to load versions');
    return (body as { versions: VersionOutput[] }).versions;
  }

  async rollback(moduleKey: string, versionId: number): Promise<{ draftId: number; version: string }> {
    const body = await this.request(
      `/api/registry/rollback/${encodeURIComponent(moduleKey)}/${versionId}`,
      { method: 'POST' }, 'rollback failed');
    this.changed.next();
    return { draftId: (body as { draftId: number }).draftId, version: (body as { version: string }).version };
  }

  async getActiveManifest(moduleKey: string): Promise<PortalModuleManifest | null> {
    const res = await fetch(`/api/registry/active-manifest/${encodeURIComponent(moduleKey)}`);
    if (!res.ok) return null;
    const data = (await res.json()) as { manifest: PortalModuleManifest | null };
    return data.manifest;
  }

  async getVersionManifest(moduleKey: string, versionId: number): Promise<PortalModuleManifest | null> {
    const res = await fetch(`/api/registry/version-manifest/${encodeURIComponent(moduleKey)}/${versionId}`);
    if (!res.ok) return null;
    const data = (await res.json()) as { manifest: PortalModuleManifest | null };
    return data.manifest;
  }

  // ── Draft Lifecycle ────────────────────────────────────────────────

  async createDraft(moduleKey: string): Promise<{ draftId: number; manifest: PortalModuleManifest }> {
    const body = await this.request(
      `/api/registry/draft/${encodeURIComponent(moduleKey)}`, { method: 'POST' }, 'create draft failed');
    const payload = body as { draftId?: number; manifest?: PortalModuleManifest };
    return { draftId: payload.draftId!, manifest: payload.manifest! };
  }

  async saveDraft(moduleKey: string, manifest: PortalModuleManifest): Promise<{ version: string }> {
    const body = await this.request(
      `/api/registry/draft/${encodeURIComponent(moduleKey)}`, this.jsonInit('PUT', { manifest }), 'save draft failed');
    return { version: (body as { version: string }).version };
  }

  async applyDraft(moduleKey: string, manifest?: PortalModuleManifest): Promise<{ ok: boolean; moduleKey: string; version: string; shouldActivate: boolean; errors?: string[] }> {
    const body = await this.request(
      `/api/registry/draft/${encodeURIComponent(moduleKey)}/apply`,
      manifest ? this.jsonInit('POST', { manifest }) : { method: 'POST' },
      'apply draft failed');
    this.changed.next();
    const payload = body as { moduleKey?: string; version?: string; shouldActivate?: boolean; errors?: string[] };
    return { ok: true, moduleKey: payload.moduleKey!, version: payload.version!, shouldActivate: payload.shouldActivate!, errors: payload.errors };
  }

  async discardDraft(moduleKey: string): Promise<void> {
    await this.request(
      `/api/registry/draft/${encodeURIComponent(moduleKey)}`, { method: 'DELETE' }, 'discard draft failed');
    this.changed.next();
  }

  async getDraft(moduleKey: string): Promise<PortalModuleManifest | null> {
    const res = await fetch(`/api/registry/draft/${encodeURIComponent(moduleKey)}`);
    if (!res.ok) return null;
    const data = (await res.json()) as { manifest: PortalModuleManifest | null };
    return data.manifest;
  }

  async loadVersion(moduleKey: string, versionId: number): Promise<{ draftId: number; manifest: PortalModuleManifest }> {
    const body = await this.request(
      `/api/registry/load-version/${encodeURIComponent(moduleKey)}/${versionId}`,
      { method: 'POST' }, 'load version failed');
    const payload = body as { draftId?: number; manifest?: PortalModuleManifest };
    return { draftId: payload.draftId!, manifest: payload.manifest! };
  }

  async downloadVersion(moduleKey: string, versionId: number): Promise<void> {
    const res = await fetch(`/api/registry/version-download/${encodeURIComponent(moduleKey)}/${versionId}`);
    if (!res.ok) throw new Error('download failed');
    const blob = await res.blob();
    const cd = res.headers.get('Content-Disposition');
    let filename = `${moduleKey}-v${versionId}.json`;
    if (cd) {
      const m = cd.match(/filename="([^"]+)"/);
      if (m) filename = m[1];
    }
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
  }

  // ── Helpers ────────────────────────────────────────────────────────

  private jsonInit(method: 'POST' | 'PUT' | 'PATCH', body: unknown): RequestInit {
    return {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    };
  }

  loadPaths(): string[] {
    return [...EMBEDDED_LOAD_PATHS];
  }
}
