import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';
import type {
  ModulePayload,
  ModuleType,
  PortalEntryPoint,
  EntryPointGroup,
  EntryCategory,
  EntryPointFormValue,
  PortalModuleManifest,
  InstallDiff,
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

export interface EntryPointOutput {
  id: number;
  moduleKey: string;
  entryKey: string;
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
  parentEntryKey?: string | null;
  groupKey?: string | null;
  sortOrder: number;
  roles?: string[];
  active: boolean;
  icon?: string;
  color?: string;
  multi: boolean;
}

@Injectable({ providedIn: 'root' })
export class RegistryService {
  readonly changed = new Subject<void>();

  // ── Modules ────────────────────────────────────────────────────────

  async listModules(): Promise<ModuleOutput[]> {
    const res = await fetch('/api/registry/modules');
    if (!res.ok) throw new Error('unauthorized');
    const data = (await res.json()) as { modules: ModuleOutput[] };
    return data.modules;
  }

  async saveModule(input: ModulePayload): Promise<ModuleOutput> {
    const res = await fetch('/api/registry/modules', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    });
    const data = (await res.json()) as { ok?: boolean; module?: ModuleOutput; error?: string };
    if (!res.ok) throw new Error(data.error || 'save failed');
    this.changed.next();
    return data.module!;
  }

  async removeModule(key: string): Promise<void> {
    const res = await fetch(`/api/registry/modules/${encodeURIComponent(key)}`, { method: 'DELETE' });
    const data = (await res.json()) as { ok?: boolean; error?: string };
    if (!res.ok) throw new Error(data.error || 'delete failed');
    this.changed.next();
  }

  async setModuleActive(key: string, active: boolean): Promise<void> {
    const res = await fetch(`/api/registry/modules/${encodeURIComponent(key)}/active`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ active }),
    });
    const data = (await res.json()) as { ok?: boolean; error?: string };
    if (!res.ok) throw new Error(data.error || 'update failed');
    this.changed.next();
  }

  async reorderModules(keys: string[]): Promise<void> {
    const res = await fetch('/api/registry/modules/reorder', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ keys }),
    });
    if (!res.ok) throw new Error('reorder failed');
    this.changed.next();
  }

  // ── Entry Points ───────────────────────────────────────────────────

  async listAllEntryPoints(): Promise<EntryPointOutput[]> {
    const res = await fetch('/api/registry/entry-points');
    if (!res.ok) throw new Error('unauthorized');
    const data = (await res.json()) as { entryPoints: EntryPointOutput[] };
    return data.entryPoints;
  }

  async listEntryPoints(moduleKey?: string): Promise<EntryPointOutput[]> {
    const url = moduleKey
      ? `/api/registry/entry-points?moduleKey=${encodeURIComponent(moduleKey)}`
      : '/api/registry/entry-points';
    const res = await fetch(url);
    if (!res.ok) throw new Error('unauthorized');
    const data = (await res.json()) as { entryPoints: EntryPointOutput[] };
    return data.entryPoints;
  }

  async saveEntryPoint(input: {
    moduleKey: string;
    entryKey: string;
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
    parentEntryKey?: string | null;
    groupKey?: string | null;
    sortOrder?: number;
    active?: boolean;
    color?: string;
    multi?: boolean;
  }): Promise<EntryPointOutput> {
    const res = await fetch('/api/registry/entry-points', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    });
    const data = (await res.json()) as { ok?: boolean; entryPoint?: EntryPointOutput; error?: string };
    if (!res.ok) throw new Error(data.error || 'save failed');
    this.changed.next();
    return data.entryPoint!;
  }

  async removeEntryPoint(id: number): Promise<void> {
    const res = await fetch(`/api/registry/entry-points/${id}`, { method: 'DELETE' });
    const data = (await res.json()) as { ok?: boolean; error?: string };
    if (!res.ok) throw new Error(data.error || 'delete failed');
    this.changed.next();
  }

  async reorderEntryPoints(ids: number[]): Promise<void> {
    const res = await fetch('/api/registry/entry-points/reorder', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ids }),
    });
    if (!res.ok) throw new Error('reorder failed');
    this.changed.next();
  }

  // ── Entry Point Groups ─────────────────────────────────────────────

  async listGroups(category?: string): Promise<EntryPointGroup[]> {
    const url = category
      ? `/api/registry/entry-point-groups?category=${encodeURIComponent(category)}`
      : '/api/registry/entry-point-groups';
    const res = await fetch(url);
    if (!res.ok) throw new Error('unauthorized');
    const data = (await res.json()) as { groups: EntryPointGroup[] };
    return data.groups;
  }

  async saveGroup(input: {
    groupKey: string;
    category: EntryCategory;
    name: string;
    parentKey?: string | null;
    sortOrder?: number;
    icon?: string;
  }): Promise<EntryPointGroup> {
    const res = await fetch('/api/registry/entry-point-groups', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(input),
    });
    const data = (await res.json()) as { ok?: boolean; group?: EntryPointGroup; error?: string };
    if (!res.ok) throw new Error(data.error || 'save failed');
    this.changed.next();
    return data.group!;
  }

  async removeGroup(groupKey: string): Promise<void> {
    const res = await fetch(`/api/registry/entry-point-groups/${encodeURIComponent(groupKey)}`, { method: 'DELETE' });
    const data = (await res.json()) as { ok?: boolean; error?: string };
    if (!res.ok) throw new Error(data.error || 'delete failed');
    this.changed.next();
  }

  async reorderGroups(keys: string[]): Promise<void> {
    const res = await fetch('/api/registry/entry-point-groups/reorder', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ keys }),
    });
    if (!res.ok) throw new Error('reorder failed');
    this.changed.next();
  }

  // ── Install Wizard ──────────────────────────────────────────────────

  async fetchManifestFromUrl(url: string): Promise<{ manifest: PortalModuleManifest }> {
    const res = await fetch('/api/registry/fetch', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url }),
    });
    const data = await res.json() as { manifest?: PortalModuleManifest; error?: string; issues?: string[] };
    if (!res.ok) throw new Error(data.error || 'fetch failed' + (data.issues ? ': ' + data.issues.join(', ') : ''));
    return { manifest: data.manifest! };
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
    const res = await fetch('/api/registry/install', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ manifest }),
    });
    const data = await res.json() as { ok?: boolean; moduleKey?: string; version?: string; error?: string };
    if (!res.ok) throw new Error(data.error || 'install failed');
    this.changed.next();
    return { ok: true, moduleKey: data.moduleKey!, version: data.version! };
  }

  async listVersions(moduleKey: string): Promise<VersionOutput[]> {
    const res = await fetch(`/api/registry/versions/${encodeURIComponent(moduleKey)}`);
    if (!res.ok) throw new Error('failed to load versions');
    const data = await res.json() as { versions: VersionOutput[] };
    return data.versions;
  }

  async rollback(moduleKey: string, versionId: number): Promise<{ draftId: number; version: string }> {
    const res = await fetch(`/api/registry/rollback/${encodeURIComponent(moduleKey)}/${versionId}`, {
      method: 'POST',
    });
    const data = await res.json() as { ok?: boolean; draftId?: number; version?: string; error?: string };
    if (!res.ok) throw new Error(data.error || 'rollback failed');
    this.changed.next();
    return { draftId: data.draftId!, version: data.version! };
  }

  async getActiveManifest(moduleKey: string): Promise<PortalModuleManifest | null> {
    const res = await fetch(`/api/registry/active-manifest/${encodeURIComponent(moduleKey)}`);
    if (!res.ok) return null;
    const data = await res.json() as { manifest: PortalModuleManifest | null };
    return data.manifest;
  }

  async getVersionManifest(moduleKey: string, versionId: number): Promise<PortalModuleManifest | null> {
    const res = await fetch(`/api/registry/version-manifest/${encodeURIComponent(moduleKey)}/${versionId}`);
    if (!res.ok) return null;
    const data = await res.json() as { manifest: PortalModuleManifest | null };
    return data.manifest;
  }

  // ── Draft Lifecycle ────────────────────────────────────────────────

  async createDraft(moduleKey: string): Promise<{ draftId: number; manifest: PortalModuleManifest }> {
    const res = await fetch(`/api/registry/draft/${encodeURIComponent(moduleKey)}`, { method: 'POST' });
    const data = await res.json() as { draftId?: number; manifest?: PortalModuleManifest; error?: string };
    if (!res.ok) throw new Error(data.error || 'create draft failed');
    return { draftId: data.draftId!, manifest: data.manifest! };
  }

  async saveDraft(moduleKey: string, manifest: PortalModuleManifest): Promise<{ version: string }> {
    const res = await fetch(`/api/registry/draft/${encodeURIComponent(moduleKey)}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ manifest }),
    });
    const data = await res.json() as { ok?: boolean; version?: string; error?: string };
    if (!res.ok) throw new Error(data.error || 'save draft failed');
    return { version: data.version! };
  }

  async applyDraft(moduleKey: string, manifest?: PortalModuleManifest): Promise<{ ok: boolean; moduleKey: string; version: string; shouldActivate: boolean; errors?: string[] }> {
    const res = await fetch(`/api/registry/draft/${encodeURIComponent(moduleKey)}/apply`, {
      method: 'POST',
      headers: manifest ? { 'Content-Type': 'application/json' } : undefined,
      body: manifest ? JSON.stringify({ manifest }) : undefined,
    });
    const data = await res.json() as { ok?: boolean; moduleKey?: string; version?: string; shouldActivate?: boolean; errors?: string[]; error?: string };
    if (!res.ok) throw new Error(data.error || 'apply draft failed');
    this.changed.next();
    return { ok: true, moduleKey: data.moduleKey!, version: data.version!, shouldActivate: data.shouldActivate!, errors: data.errors };
  }

  async discardDraft(moduleKey: string): Promise<void> {
    const res = await fetch(`/api/registry/draft/${encodeURIComponent(moduleKey)}`, { method: 'DELETE' });
    const data = await res.json() as { ok?: boolean; error?: string };
    if (!res.ok) throw new Error(data.error || 'discard draft failed');
    this.changed.next();
  }

  async getDraft(moduleKey: string): Promise<PortalModuleManifest | null> {
    const res = await fetch(`/api/registry/draft/${encodeURIComponent(moduleKey)}`);
    if (!res.ok) return null;
    const data = await res.json() as { manifest: PortalModuleManifest | null };
    return data.manifest;
  }

  async loadVersion(moduleKey: string, versionId: number): Promise<{ draftId: number; manifest: PortalModuleManifest }> {
    const res = await fetch(`/api/registry/load-version/${encodeURIComponent(moduleKey)}/${versionId}`, { method: 'POST' });
    const data = await res.json() as { draftId?: number; manifest?: PortalModuleManifest; error?: string };
    if (!res.ok) throw new Error(data.error || 'load version failed');
    return { draftId: data.draftId!, manifest: data.manifest! };
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

  loadPaths(): string[] {
    return [...EMBEDDED_LOAD_PATHS];
  }
}
