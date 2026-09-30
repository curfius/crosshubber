import { Injectable } from '@angular/core';
import { apiFetch } from '../http/api-fetch';

/** One row of the agent tool audit trail (`GET /api/ai-hub/agent-tool-calls`, AI plan C4). */
export interface AgentToolCallRow {
  id: number;
  occurredAt: string;
  userId: string;
  conversationId: string | null;
  moduleKey: string | null;
  toolId: string;
  toolName: string;
  argsSummary: string | null;
  /** ok | denied | error | needs_confirmation | confirmed | cap_reached */
  outcome: string;
  durationMs: number | null;
}

@Injectable({ providedIn: 'root' })
export class AgentAuditService {
  /**
   * Latest dispatches across all users, newest first (server caps at 200).
   * Throws `ApiError` on non-2xx — the caller decides how to surface failures.
   */
  async listRecent(): Promise<AgentToolCallRow[]> {
    const res = await apiFetch('/api/ai-hub/agent-tool-calls');
    const data = (await res.json()) as { toolCalls?: AgentToolCallRow[] };
    return data.toolCalls ?? [];
  }
}
