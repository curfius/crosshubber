import { signal } from '@angular/core';
import type { ChatStreamEvent } from './chat-core.service';

// Client-side state machine over the agent tool loop's typed SSE frames
// (AI plan C3; protocol in docs/agent-protocol.md). Builds the tool-activity
// rows rendered next to the chat input and holds the pending confirmation
// opened by a `confirmation_required` frame.
//
// Shared by the AI Hub chat surface and the quick-chat flyout. Pure — no HTTP
// and no template knowledge; components feed it events and render rows/pending.

export interface ChatToolRow {
  /** Monotonic id: frames before confirmation carry no shared call id. */
  seq: number;
  tool: string;
  module?: string | null;
  /** Tool flavour from the tool_call frame: builtin | remote | agent. */
  kind?: string;
  mutates?: boolean;
  /** running | needs_confirmation | ok | denied | error | confirmed | cap_reached | declined */
  status: string;
  callId?: string;
}

/** One cited document source (citation frame, AI plan G3). */
export interface ChatCitationRow {
  /** Monotonic id (shared counter with the tool rows). */
  seq: number;
  tool: string;
  title: string;
  ref?: string;
  snippet?: string;
}

export interface PendingToolConfirmation {
  tool: string;
  callId: string;
}

/** Bounded: the settings page owns the full audit trail (latest 200 rows). */
const MAX_ROWS = 50;
const MAX_CITATIONS = 50;
const CONFIRMED_PREFIX = 'confirmed:';

export class ChatToolFlow {
  readonly rows = signal<ChatToolRow[]>([]);
  readonly citations = signal<ChatCitationRow[]>([]);
  readonly pending = signal<PendingToolConfirmation | null>(null);
  private seq = 0;

  handleEvent(event: ChatStreamEvent): void {
    switch (event.type) {
      case 'tool_call':
        this.appendRow({
          tool: String(event.tool ?? ''),
          module: event.module ?? null,
          kind: typeof event.kind === 'string' ? event.kind : undefined,
          mutates: event.mutates ?? false,
          status: 'running',
        });
        break;
      case 'tool_result':
        this.applyResult(event);
        break;
      case 'citation':
        this.applyCitations(event);
        break;
      case 'confirmation_required':
        this.applyConfirmation(event);
        break;
      default:
        break; // unknown frame types are ignored per the protocol
    }
  }

  /** Closes the confirm dialog (the result row updates arrive as their own frame). */
  clearPending(): void {
    this.pending.set(null);
  }

  /**
   * User declined: close the dialog and mark the parked row. Nothing is sent —
   * the call stays parked server-side until its confirmation TTL expires.
   */
  decline(): void {
    const pc = this.pending();
    this.pending.set(null);
    if (pc) this.markRow((r) => r.callId === pc.callId, { status: 'declined' });
  }

  reset(): void {
    this.rows.set([]);
    this.citations.set([]);
    this.pending.set(null);
  }

  private applyResult(event: ChatStreamEvent): void {
    const tool = String(event.tool ?? '');
    const status = String(event.status ?? '');
    const callId = typeof event.callId === 'string' ? event.callId : undefined;
    if (tool.startsWith(CONFIRMED_PREFIX)) {
      // Confirmed-execution result (AiHubChatService.withConfirmedToolResult).
      const confirmedId = tool.slice(CONFIRMED_PREFIX.length);
      if (!this.markRow((r) => r.callId === confirmedId, { status })) {
        this.appendRow({ tool: confirmedId, status, callId: confirmedId });
      }
      this.pending.set(null);
      return;
    }
    const patch: Partial<ChatToolRow> = { status };
    if (callId) patch.callId = callId;
    if (!this.markRow((r) => r.tool === tool && r.status === 'running', patch)) {
      this.appendRow({ tool, status, callId });
    }
  }

  /** Citation frame (G3): collect the named sources, skipping items without a title. */
  private applyCitations(event: ChatStreamEvent): void {
    const tool = String(event.tool ?? '');
    const items = Array.isArray(event.citations) ? event.citations : [];
    for (const raw of items) {
      if (typeof raw !== 'object' || raw === null) continue;
      const item = raw as Record<string, unknown>;
      const titleValue = item['title'];
      const title = typeof titleValue === 'string' && titleValue.trim() ? titleValue : null;
      if (!title) continue;
      const ref = item['ref'];
      const snippet = item['snippet'];
      this.appendCitation({
        tool,
        title,
        ref: typeof ref === 'string' ? ref : undefined,
        snippet: typeof snippet === 'string' ? snippet : undefined,
      });
    }
  }

  private appendCitation(row: Omit<ChatCitationRow, 'seq'>): void {
    const seq = ++this.seq;
    this.citations.update((rows) => [...rows, { seq, ...row }].slice(-MAX_CITATIONS));
  }

  private applyConfirmation(event: ChatStreamEvent): void {
    const tool = String(event.tool ?? '');
    const callId = typeof event.callId === 'string' ? event.callId : '';
    if (!callId) return;
    this.pending.set({ tool, callId });
    // The preceding tool_result normally marked the row already; mark defensively.
    this.markRow((r) => r.callId === callId || (r.tool === tool && r.status === 'running'), {
      status: 'needs_confirmation',
      callId,
    });
  }

  private appendRow(row: Omit<ChatToolRow, 'seq'>): void {
    const seq = ++this.seq;
    this.rows.update((rows) => [...rows, { seq, ...row }].slice(-MAX_ROWS));
  }

  /** Patches the first matching row; returns whether one matched. */
  private markRow(match: (row: ChatToolRow) => boolean, patch: Partial<ChatToolRow>): boolean {
    let found = false;
    this.rows.update((rows) =>
      rows.map((row) => {
        if (found || !match(row)) return row;
        found = true;
        return { ...row, ...patch };
      }),
    );
    return found;
  }
}

/** Badge class for a row status (design-system `ds-badge` variants — no raw colors). */
export function toolStatusTone(status: string): string {
  switch (status) {
    case 'running':
      return 'ds-badge ds-badge-info';
    case 'ok':
    case 'confirmed':
      return 'ds-badge ds-badge-success';
    case 'denied':
    case 'error':
      return 'ds-badge ds-badge-danger';
    case 'needs_confirmation':
    case 'cap_reached':
      return 'ds-badge ds-badge-warning';
    default:
      return 'ds-badge ds-badge-default'; // declined
  }
}

/**
 * Resolves once `busy()` returns false. A confirmation may arrive mid-stream;
 * the follow-up turn must wait for the current stream to finish (the chat state
 * is single-flight).
 */
export async function waitUntilIdle(busy: () => boolean, pollMs = 100): Promise<void> {
  while (busy()) await new Promise<void>((resolve) => setTimeout(resolve, pollMs));
}
