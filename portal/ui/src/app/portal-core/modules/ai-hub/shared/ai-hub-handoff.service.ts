import { Injectable, signal } from '@angular/core';

// Quick-chat → assistant-page handoff (phase 4): the flyout's expand button
// parks the conversation id here and opens the AI Hub app; the chat surface
// watches `pending()` and loads that conversation. Root-scoped so the signal
// survives across the two component lifecycles.

@Injectable({ providedIn: 'root' })
export class AiHubHandoffService {
  private readonly pendingConversationId = signal<string | null>(null);

  readonly pending = this.pendingConversationId.asReadonly();

  /** Parks a conversation id for the main assistant window (null = open empty). */
  requestOpen(conversationId: string | null): void {
    this.pendingConversationId.set(conversationId);
  }

  /** Reads and clears the parked id (single consumption). */
  consume(): string | null {
    const id = this.pendingConversationId();
    if (id !== null) this.pendingConversationId.set(null);
    return id;
  }
}
