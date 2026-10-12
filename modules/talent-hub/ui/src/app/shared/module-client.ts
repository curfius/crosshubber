import { Injectable } from '@angular/core';

/**
 * Minimal HTTP client for the Talent Hub MFE.
 *
 * Data path: the element runs inside the portal document, so it
 *  1. mints a portal-signed agent-call token (same-origin,
 *     `GET /api/agent/module-token/talent-hub`), then
 *  2. calls the module backend through the portal's MFE proxy
 *     (`/api/mfe/talent-hub/**` → module `baseUrl`), attaching
 *     `X-Portal-Agent`. No CORS, no module-origin knowledge.
 */

const PROXY_BASE = '/api/mfe/talent-hub';
const TOKEN_URL = '/api/agent/module-token/talent-hub';

// ── Domain shapes (mirror of the module DTO contract) ──────────────────

/** Portal identity passed to `el.mount({user, language})` (module-outlet contract). */
export interface PortalUser {
  sub: string;
  name?: string | null;
  roles: string[];
}

export type PhaseValue =
  | 'draft'
  | 'approved'
  | 'sourcing'
  | 'offer_extended'
  | 'accepted'
  | 'pending_onboarding'
  | 'closed'
  | 'rejected'
  | 'archived';

/** Derived + manual phases in canonical (board) order. */
export const PHASES: PhaseValue[] = [
  'draft',
  'approved',
  'sourcing',
  'offer_extended',
  'accepted',
  'pending_onboarding',
  'closed',
  'rejected',
  'archived',
];

export const MANUAL_TERMINAL_PHASES: PhaseValue[] = ['closed', 'rejected', 'archived'];

export type StageValue =
  | 'pooled'
  | 'assessed'
  | 'submitted'
  | 'interviewing'
  | 'offer'
  | 'accepted'
  | 'onboarding'
  | 'onboarded'
  | 'rejected'
  | 'withdrawn'
  | 'cancelled';

/** Process stages in canonical (board) order. */
export const STAGES: StageValue[] = [
  'pooled',
  'assessed',
  'submitted',
  'interviewing',
  'offer',
  'accepted',
  'onboarding',
  'onboarded',
  'rejected',
  'withdrawn',
  'cancelled',
];

/** End states; `cancelled` is only ever set by the close/reject bulk-cancel. */
export const TERMINAL_STAGES: StageValue[] = [
  'onboarded',
  'rejected',
  'withdrawn',
  'cancelled',
];

/** Mirror of the server ProcessStage transition map (validated server-side regardless). */
export const STAGE_TRANSITIONS: Record<StageValue, StageValue[]> = {
  pooled: ['assessed', 'withdrawn'],
  assessed: ['submitted', 'withdrawn'],
  submitted: ['interviewing', 'rejected', 'withdrawn'],
  interviewing: ['offer', 'rejected', 'withdrawn'],
  offer: ['accepted', 'rejected', 'withdrawn'],
  accepted: ['onboarding', 'withdrawn'],
  onboarding: ['onboarded', 'withdrawn'],
  onboarded: [],
  rejected: [],
  withdrawn: [],
  cancelled: [],
};

export interface Phase {
  id: string;
  rfpId: string;
  from: string | null;
  to: string;
  trigger: 'manual' | 'derived';
  actor: string;
  note: string | null;
  at: string;
}

export interface Client {
  id: string;
  name: string;
  industry: string | null;
  accountOwner: string | null;
  contacts: Record<string, unknown>[];
  notes: string | null;
}

export interface Rfp {
  id: string;
  clientId: string;
  title: string;
  kind: 'rfp' | 'rfq';
  phase: PhaseValue;
  serviceType: 'tm' | 'direct';
  source: 'manual' | 'email';
  deadline: string | null;
  description: string | null;
  requirements: Record<string, unknown>;
  analysis: Record<string, unknown> | null;
  documentRefs: string[];
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface Position {
  id: string;
  rfpId: string;
  title: string;
  headcount: number;
  serviceType: 'tm' | 'direct' | null;
  requirements: Record<string, unknown>;
  status: 'open' | 'filling' | 'filled' | 'cancelled';
  orderIndex: number;
}

export interface StageHistoryEntry {
  stage: string;
  at: string;
  by: string;
  note?: string | null;
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
  kind: 'internal' | 'external';
  contact: Record<string, unknown>;
  assignment: Record<string, unknown> | null;
  expectations: Record<string, unknown> | null;
  consent: Record<string, unknown> | null;
  lastEnrichedAt: string | null;
}

// ── Role helpers (server enforces regardless; UI only hides) ───────────

export function hasRole(user: PortalUser | null, ...accepted: string[]): boolean {
  return user != null && user.roles.some((r) => accepted.includes(r));
}

/** Manager-only actions: approve, close, reject, archive, capture. */
export function canManage(user: PortalUser | null): boolean {
  return hasRole(user, 'staffing-manager');
}

/** Recruiter-or-better actions: processes, matching, submissions, talent writes. */
export function canRecruit(user: PortalUser | null): boolean {
  return hasRole(user, 'staffing-recruiter', 'staffing-manager', 'staffing-admin');
}

// ── Client ─────────────────────────────────────────────────────────────

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

  async patch<T>(path: string, body: unknown): Promise<T> {
    const res = await this.authed('PATCH', path, body);
    if (!res.ok) throw new Error(`PATCH ${path} failed (${res.status})`);
    return (await res.json()) as T;
  }

  /** Uploads a file (multipart); returns the stored document ref. */
  async upload<T>(path: string, file: File): Promise<T> {
    this.token ??= await this.mintToken();
    const form = new FormData();
    form.append('file', file);
    const send = (tok: string) =>
      fetch(`${PROXY_BASE}${path}`, {
        method: 'POST',
        headers: { 'X-Portal-Agent': tok },
        body: form,
      });
    let res = await send(this.token);
    if (res.status === 401) {
      this.token = await this.mintToken();
      res = await send(this.token!);
    }
    if (!res.ok) throw new Error(`upload ${path} failed (${res.status})`);
    return (await res.json()) as T;
  }
}
