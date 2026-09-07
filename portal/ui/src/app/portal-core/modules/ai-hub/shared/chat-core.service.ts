import { Injectable } from '@angular/core';

// Shared chat core for the AI Hub chat surface and the quick-chat flyout
// (extracted from the ~80% duplicated implementations). Owns conversation
// persistence and the normalized SSE streaming protocol
// (`data: {"content": "..."}` frames, terminated by `data: [DONE]`).

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
}

export interface StreamChatParams {
  /** Existing conversation id; omitted to let the server create one. */
  conversationId?: string | null;
  message: string;
  /** Called for every content delta (leading newlines of the first chunk are stripped). */
  onContent: (chunk: string) => void;
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
      const data = await res.json() as { conversations: Conversation[] };
      return data.conversations;
    } catch {
      return [];
    }
  }

  async deleteConversation(id: string): Promise<void> {
    try {
      await fetch(`/api/ai-hub/conversations/${encodeURIComponent(id)}`, { method: 'DELETE' });
    } catch { /* ignore */ }
  }

  async getMessages(conversationId: string): Promise<ChatMessage[]> {
    try {
      const res = await fetch(`/api/ai-hub/conversations/${encodeURIComponent(conversationId)}/messages`);
      if (!res.ok) return [];
      const data = await res.json() as { messages: Array<{ role: string; content: string; created_at: string }> };
      return data.messages.map((m) => ({
        role: m.role as 'user' | 'assistant',
        content: m.content,
        timestamp: new Date(m.created_at).getTime(),
      }));
    } catch {
      return [];
    }
  }

  // ── Streaming ────────────────────────────────────────────────────────

  /**
   * Streams a chat completion. The provider, model, token, system prompt and
   * generation parameters all come from the server-side `ai-hub` module
   * settings; the request only carries the user's message. Resolves when the
   * stream ends; throws `Error` with the upstream message when the request
   * fails before streaming starts.
   */
  async streamChat(params: StreamChatParams): Promise<StreamChatResult> {
    const res = await fetch('/api/ai-hub/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        conversationId: params.conversationId || undefined,
        message: params.message,
      }),
    });
    if (!res.ok) {
      let message = '';
      try {
        const err = await res.json() as { error?: string };
        message = err.error ?? '';
      } catch { /* ignore */ }
      throw new Error(message);
    }

    const reader = res.body?.getReader();
    if (!reader) throw new Error('no response body');
    const decoder = new TextDecoder();
    let buffer = '';
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
   * Consumes complete SSE lines and forwards content deltas. Handles both
   * `data:{...}` (Spring's SSE writer omits the space after the colon) and
   * `data: {...}`. Returns true when the stream was terminated by `[DONE]`.
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
      let parsed: { content?: string; error?: string };
      try {
        parsed = JSON.parse(data) as { content?: string; error?: string };
      } catch { continue; /* skip unparseable lines */ }
      if (parsed.error) throw new Error(parsed.error);
      if (parsed.content) {
        const chunk = state.first ? parsed.content.replace(/^\n+/, '') : parsed.content;
        state.first = false;
        if (chunk) params.onContent(chunk);
      }
    }
    return false;
  }
}
