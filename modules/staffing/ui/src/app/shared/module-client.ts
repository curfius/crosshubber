import { Injectable } from '@angular/core';

/**
 * Minimal HTTP client for the Staffing MFE.
 *
 * Data path: the element runs inside the portal document, so it
 *  1. mints a portal-signed agent-call token (same-origin,
 *     `GET /api/agent/module-token/staffing`), then
 *  2. calls the module backend through the portal's MFE proxy
 *     (`/api/mfe/staffing/**` → module `baseUrl`), attaching
 *     `X-Portal-Agent`. No CORS, no module-origin knowledge.
 */

export interface Rfp {
  id: string;
  client: string;
  title: string;
  kind: string;
  status: string;
  deadline: string;
  requirements: Record<string, unknown>;
  specDocRef: string | null;
  createdBy: string;
  createdAt: string;
}

export interface Candidate {
  id: string;
  sourceRef: string;
  name: string;
  headline: string | null;
  skills: string[];
  seniority: string | null;
  languages: string[];
  availability: string | null;
  status: string;
  parsedAt: string;
}

export interface Shortlist {
  id: string;
  rfpId: string;
  candidateIds: string[];
  createdBy: string;
  createdAt: string;
}

export interface MatchResult {
  candidateId: string;
  name: string;
  score: number;
  rationale: string;
}

export interface MatchRun {
  id: string;
  rfpId: string;
  status: string;
  results: MatchResult[];
  createdAt: string;
}

export const RFP_STATUSES = ['new', 'analyzing', 'matched', 'shortlisted', 'submitted', 'archived'];

const PROXY_BASE = '/api/mfe/staffing';
const TOKEN_URL = '/api/agent/module-token/staffing';

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

  async put<T>(path: string, body: unknown): Promise<T> {
    const res = await this.authed('PUT', path, body);
    if (!res.ok) throw new Error(`PUT ${path} failed (${res.status})`);
    return (await res.json()) as T;
  }
}
