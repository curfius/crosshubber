import { Injectable } from '@angular/core';

// Shared chat core for the AI Hub chat surface and the quick-chat flyout
// (extracted from the ~80% duplicated implementations). Owns conversation
// persistence and the normalized SSE streaming protocol
// (`data: {"content": "..."}` frames, terminated by `data: [DONE]`; typed
// tool frames per docs/agent-protocol.md).

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  timestamp: number;
}

export interface Conversation {
  id: string;
  title: string;
  created_at: string;
  updated_at: string;
  pinned: boolean;
}

/** Partial conversation metadata update (rename / pin, phase 4). */
export interface ConversationPatch {
  title?: string;
  pinned?: boolean;
}

// ── Session context pack (client portion; AI plan A2) ────────────────

export interface ClientContextLocation {
  tabKey?: string | null;
  appKey?: string | null;
  modulePath?: string | null;
}

export interface ClientContextOpenTab {
  key: string;
  title?: string | null;
}

export interface ClientContext {
  location?: ClientContextLocation | null;
  openTabs?: ClientContextOpenTab[] | null;
  workspace?: string | null;
}

// ── Typed tool frames (docs/agent-protocol.md) ────────────────────────

/** Unknown frames/fields must be ignored by consumers. */
export interface ChatStreamEvent {
  type: string;
  tool?: string;
  module?: string | null;
  /** Tool flavour from the tool_call frame: builtin | remote | agent. */
  kind?: string;
  mutates?: boolean;
  status?: string;
  callId?: string;
  /** citation frame payload (AI plan G3): [{title, ref?, snippet?}] */
  citations?: unknown[];
  [key: string]: unknown;
}

export interface StreamChatParams {
  /** Existing conversation id; omitted to let the server create one. */
  conversationId?: string | null;
  message: string;
  /** Client-supplied navigation context (identity fields are server-authoritative). */
  context?: ClientContext;
  /** Confirms a pending mutating tool call (AI plan B6). */
  toolConfirmation?: { callId: string };
  /** Called for every content delta (leading blank lines are stripped before the first visible content). */
  onContent: (chunk: string) => void;
  /** Called for every typed frame (tool_call/tool_result/confirmation_required/…). */
  onEvent?: (event: ChatStreamEvent) => void;
}

export interface StreamChatResult {
  /** Conversation the exchange belongs to (created server-side when absent). */
  conversationId: string | null;
}

@Injectable({ providedIn: 'root' })
export class ChatCoreService {
  // ── Conversations ────────────────────────────────────────────────────

  async listConversations(): Promise<Conversation[]> {
    try {
      const res = await fetch('/api/ai-hub/conversations');
      if (!res.ok) return [];
      const data = (await res.json()) as { conversations: Conversation[] };
      return data.conversations;
    } catch {
      return [];
    }
  }

  async deleteConversation(id: string): Promise<void> {
    try {
      await fetch(`/api/ai-hub/conversations/${encodeURIComponent(id)}`, { method: 'DELETE' });
    } catch {
      /* ignore */
    }
  }

  /** Renames and/or pins a conversation. Returns the updated DTO, or null on failure. */
  async updateConversation(id: string, patch: ConversationPatch): Promise<Conversation | null> {
    try {
      const res = await fetch(`/api/ai-hub/conversations/${encodeURIComponent(id)}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(patch),
      });
      if (!res.ok) return null;
      return (await res.json()) as Conversation;
    } catch {
      return null;
    }
  }

  async getMessages(conversationId: string): Promise<ChatMessage[]> {
    try {
      const res = await fetch(
        `/api/ai-hub/conversations/${encodeURIComponent(conversationId)}/messages`,
      );
      if (!res.ok) return [];
      const data = (await res.json()) as {
        messages: Array<{ role: string; content: string; created_at: string }>;
      };
      return data.messages.map((m) => ({
        role: m.role as 'user' | 'assistant',
        // Stored assistant output may open with the blank lines models emit
        // around tool calls — drop them so replays don't lead with empty rows.
        content: m.role === 'assistant' ? m.content.replace(/^\n+/, '') : m.content,
        timestamp: new Date(m.created_at).getTime(),
      }));
    } catch {
      return [];
    }
  }

  // ── Streaming ────────────────────────────────────────────────────────

  /**
   * Streams a chat completion. The provider, model, token and generation parameters
   * come from the server-side `ai-hub` module settings; the request carries the user's
   * message plus the optional session context. Resolves when the stream ends; throws
   * `Error` with the upstream message when the request fails before streaming starts.
   */
  async streamChat(params: StreamChatParams): Promise<StreamChatResult> {
    const res = await fetch('/api/ai-hub/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        conversationId: params.conversationId || undefined,
        message: params.message,
        context: params.context ?? undefined,
        toolConfirmation: params.toolConfirmation ?? undefined,
      }),
    });
    if (!res.ok) {
      let message = '';
      try {
        const err = (await res.json()) as { error?: string };
        message = err.error ?? '';
      } catch {
        /* ignore */
      }
      throw new Error(message);
    }

    const reader = res.body?.getReader();
    if (!reader) throw new Error('no response body');
    const decoder = new TextDecoder();
    let buffer = '';
    // first = no visible content emitted yet (leading blank lines still pending).
    const state = { first: true };
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';
        if (this.consumeFrames(lines, params, state)) break;
      }
      // flush remaining buffer
      this.consumeFrames(buffer.split('\n'), params, state);
    } finally {
      reader.releaseLock();
    }
    return { conversationId: res.headers.get('X-Conversation-Id') };
  }

  /**
   * Consumes complete SSE lines and forwards content deltas + typed frames. Handles both
   * `data:{...}` (Spring's SSE writer omits the space after the colon) and `data: {...}`.
   * Returns true when the stream was terminated by `[DONE]`.
   */
  private consumeFrames(
    lines: string[],
    params: StreamChatParams,
    state: { first: boolean },
  ): boolean {
    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed.startsWith('data:')) continue;
      const data = trimmed.slice(5).replace(/^ /, '');
      if (data === '[DONE]') return true;
      let parsed: { content?: string; error?: string; type?: string };
      try {
        parsed = JSON.parse(data) as { content?: string; error?: string; type?: string };
      } catch {
        continue; /* skip unparseable lines */
      }
      if (parsed.error) throw new Error(parsed.error);
      if (typeof parsed.type === 'string') {
        params.onEvent?.(parsed as ChatStreamEvent);
        continue;
      }
      if (parsed.content) {
        let chunk = parsed.content;
        if (state.first) {
          // Swallow deltas that are only newlines until real content arrives —
          // models commonly open with blank lines around tool calls, and the
          // newlines can be split across consecutive chunks.
          chunk = chunk.replace(/^\n+/, '');
          if (!chunk) continue;
          state.first = false;
        }
        if (chunk) params.onContent(chunk);
      }
    }
    return false;
  }
}

/** Session-panel ordering (phase 4): pinned conversations first, then most recently updated. */
export function sortConversations(conversations: Conversation[]): Conversation[] {
  return [...conversations].sort(
    (a, b) =>
      Number(b.pinned ?? false) - Number(a.pinned ?? false) ||
      (b.updated_at ?? '').localeCompare(a.updated_at ?? ''),
  );
}
