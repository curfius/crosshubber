import { Injectable } from '@angular/core';

/**
 * Minimal HTTP client for the Solutions MFE.
 *
 * Data path: the element runs inside the portal document, so it
 *  1. mints a portal-signed agent-call token (same-origin,
 *     `GET /api/agent/module-token/solutions`), then
 *  2. calls the module backend through the portal's MFE proxy
 *     (`/api/mfe/solutions/**` → module `baseUrl`), attaching
 *     `X-Portal-Agent`. No CORS, no module-origin knowledge.
 */

export interface ProjectSummary {
  id: string;
  name: string;
  /** Stage enum name as served by the module backend (e.g. "IMPLEMENTATION"). */
  stage: string;
  health: string | null;
  owner: string;
  budget: number | string | null;
}

export interface ClientSummary {
  id: string;
  name: string;
  industry: string | null;
  accountOwner: string | null;
}

export interface StageEvent {
  id: number;
  fromStage: string;
  toStage: string;
  actor: string;
  note: string | null;
  occurredAt: string;
}

export interface DocumentLink {
  id: number;
  documentRef: string;
  title: string;
  ragEnabled: boolean;
  addedBy: string;
}

export interface Note {
  id: number;
  author: string;
  body: string;
  createdAt: string;
}

export type Stage =
  | 'lead'
  | 'qualified'
  | 'proposal'
  | 'sent'
  | 'negotiation'
  | 'won'
  | 'implementation'
  | 'delivery'
  | 'closed'
  | 'lost';

export const PIPELINE_STAGES: Stage[] = [
  'lead',
  'qualified',
  'proposal',
  'sent',
  'negotiation',
  'won',
  'implementation',
  'delivery',
  'closed',
  'lost',
];

const PROXY_BASE = '/api/mfe/solutions';
const TOKEN_URL = '/api/agent/module-token/solutions';

@Injectable({ providedIn: 'root' })
export class ModuleClient {
  private token: string | null = null;

  private async mintToken(): Promise<string> {
    const res = await fetch(TOKEN_URL);
    if (!res.ok) {
      throw new Error('agent token unavailable (module not installed or session expired)');
    }
    const body = (await res.json()) as { token: string };
    return body.token;
  }

  private async authed(method: string, path: string, body: unknown): Promise<Response> {
    this.token ??= await this.mintToken();
    const send = (tok: string) =>
      fetch(`${PROXY_BASE}${path}`, {
        method,
        headers: {
          'X-Portal-Agent': tok,
          ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        },
        ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
      });
    let res = await send(this.token);
    // Portal tokens live 5 minutes — a stale one re-mints exactly once.
    if (res.status === 401) {
      this.token = await this.mintToken();
      res = await send(this.token!);
    }
    return res;
  }

  async get<T>(path: string): Promise<T> {
    const res = await this.authed('GET', path, undefined);
    if (!res.ok) throw new Error(`GET ${path} failed (${res.status})`);
    return (await res.json()) as T;
  }

  async post<T>(path: string, body: unknown): Promise<T> {
    const res = await this.authed('POST', path, body);
    if (!res.ok) throw new Error(`POST ${path} failed (${res.status})`);
    return (await res.json()) as T;
  }

  async patch<T>(path: string, body: unknown): Promise<T> {
    const res = await this.authed('PATCH', path, body);
    if (!res.ok) throw new Error(`PATCH ${path} failed (${res.status})`);
    return (await res.json()) as T;
  }

  async put<T>(path: string, body: unknown): Promise<T> {
    const res = await this.authed('PUT', path, body);
    if (!res.ok) throw new Error(`PUT ${path} failed (${res.status})`);
    return (await res.json()) as T;
  }
}
