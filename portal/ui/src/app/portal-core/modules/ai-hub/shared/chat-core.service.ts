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
  providerId: string;
  model: string;
  tokenId: string;
  messages: Array<{ role: 'user' | 'assistant'; content: string }>;
  systemPrompt?: string;
  temperature?: number;
  maxTokens?: number;
  /** Called for every content delta (leading newlines of the first chunk are stripped). */
  onContent: (chunk: string) => void;
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

  async createConversation(title: string): Promise<Conversation | null> {
    try {
      const res = await fetch('/api/ai-hub/conversations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title }),
      });
      if (!res.ok) return null;
      const data = await res.json() as { conversation: Conversation };
      return data.conversation;
    } catch {
      return null;
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

  async saveMessage(conversationId: string, role: 'user' | 'assistant', content: string): Promise<void> {
    try {
      await fetch(`/api/ai-hub/conversations/${encodeURIComponent(conversationId)}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ role, content }),
      });
    } catch { /* ignore */ }
  }

  // ── Streaming ────────────────────────────────────────────────────────

  /**
   * Streams a chat completion. Resolves when the stream ends; throws
   * `Error` with the upstream message when the request fails before
   * streaming starts.
   */
  async streamChat(params: StreamChatParams): Promise<void> {
    const res = await fetch('/api/ai-hub/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        providerId: params.providerId,
        model: params.model,
        tokenId: params.tokenId,
        messages: params.messages,
        systemPrompt: params.systemPrompt || undefined,
        temperature: params.temperature,
        maxTokens: params.maxTokens,
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
    let first = true;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';
        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed.startsWith('data: ')) continue;
          const data = trimmed.slice(6);
          if (data === '[DONE]') break;
          try {
            const parsed = JSON.parse(data) as { content?: string };
            if (parsed.content) {
              const chunk = first ? parsed.content.replace(/^\n+/, '') : parsed.content;
              first = false;
              if (chunk) params.onContent(chunk);
            }
          } catch { /* skip unparseable lines */ }
        }
      }
    } finally {
      reader.releaseLock();
    }
  }
}
