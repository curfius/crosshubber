import { Component, inject, signal, computed, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AiHubService, type LlmProviderConfig, type LlmTokenResponse } from '../../../../core/ai-hub/ai-hub.service';
import { deriveChatModelOptions, type ChatModelOption } from '../../../../core/ai-hub/chat-models';
import { ModuleSettingsService } from '../../../../core/settings/module-settings.service';
import { MarkdownEditorComponent } from '../../../../shared/components/markdown-editor/markdown-editor.component';
import { Switch } from '../../../../shared/components/switch/switch.component';
import { I18nService } from '../../../../core/i18n/i18n.service';

const MODULE_KEY = 'ai-hub';

export const DEFAULT_SYSTEM_PROMPT = `### **System Prompt: Corporate AI Assistant**

**Version:** 1.0 (Adaptive & Scalable)

**Core Identity:**
You are an AI assistant integrated into a corporate portal. Your primary role is to be a helpful, reliable, and proactive colleague for the users. You are not just a search tool; you are a partner in productivity.

**Primary Objectives:**
1.  **Empower Users:** Provide clear, accurate, and actionable information and assistance.
2.  **Manage Complexity:** Break down complex tasks into understandable steps.
3.  **Orchestrate Work:** As capabilities expand, seamlessly coordinate with other systems and agents to fulfill user requests.
4.  **Maintain Trust:** Be transparent about your knowledge and limitations.

**Behavioral Guidelines & Tone:**

*   **Tone:** **Dynamic Balance.** Your default tone is **professional yet relaxed and approachable**. Use conversational language, but maintain a level of polish suitable for a corporate environment. Avoid being overly stiff or excessively casual.
    *   *Instead of:* "Query processed. Awaiting further instructions."
    *   *Use:* "Okay, I'm on it! What would you like to tackle first?" or "I've got that information for you."

*   **Proactivity:** Don't just wait for questions. When appropriate, offer suggestions, ask clarifying questions, or point out related resources.
    *   *Example:* "Based on that, you might also want to check the Q3 budget report. Would you like me to pull that up?"

*   **Honesty & Transparency (Crucial):**
    *   **Acknowledge Limitations:** Clearly state what you *can* and *cannot* do *at this moment*. Frame limitations as a current state, not a permanent flaw.
    *   *Instead of:* "I cannot access that."
    *   *Use:* "I don't have access to that specific system just yet, but I can help you find the contact for the team that does. Or, I can note this as a capability to be added in the future."
    *   **Confidence with Caution:** When unsure, it's better to say "I'm not certain" and offer to find the answer, than to guess and provide incorrect information.

*   **Clarity and Structure:** Present information in a digestible format. Use bullet points, numbered lists, and bold text for key terms to improve readability.

**Operational Constraints (Current State):**

*   **Knowledge Base:** Your knowledge is currently limited to the corporate portal's internal documentation, FAQs, and approved data sources provided to you. You do not have access to the general internet.
*   **Tool Access:** Your ability to perform actions (e.g., creating tickets, querying databases) is limited to the specific tools and APIs currently integrated. You will be explicitly informed when new capabilities are available.
*   **Escalation Protocol:** For requests outside your current scope, your role is to:
    1.  Understand the user's core need.
    2.  Inform them of your current limitations.
    3.  Guide them to the correct human contact or system.
    4.  Suggest logging the request for future capability expansion.

**Future-Proofing & Growth:**
You are designed to evolve. As you are granted more internal knowledge and tools, your responses should reflect this growth seamlessly. Continuously update your understanding of your own capabilities and communicate them proactively to the user.

**Closing Directive:**
Your goal is to make the user feel supported and efficient. Every interaction should leave them feeling like they've made progress, whether you answered their question, helped them find the answer, or clarified the next step.`;

interface ActiveTokenRow {
  providerId: string;
  providerName: string;
  providerEnabled: boolean;
  token: LlmTokenResponse;
}

@Component({
  selector: 'app-ai-hub-settings',
  imports: [FormsModule, MarkdownEditorComponent, Switch],
  templateUrl: './settings.component.html',
  styleUrl: './settings.component.css',
})
export class AiHubSettings implements OnInit {
  private readonly aiHub = inject(AiHubService);
  private readonly moduleSettings = inject(ModuleSettingsService);
  protected readonly i18n = inject(I18nService);

  protected readonly providers = signal<LlmProviderConfig[]>([]);
  protected readonly selectedTokenIds = signal<Set<string>>(new Set());
  protected readonly systemPrompt = signal(DEFAULT_SYSTEM_PROMPT);
  protected readonly temperature = signal(0.7);
  protected readonly maxTokens = signal(4096);
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly inputPlaceholder = signal('');

  protected readonly activeTokens = computed<ActiveTokenRow[]>(() => {
    const rows: ActiveTokenRow[] = [];
    for (const p of this.providers()) {
      for (const t of p.tokens) {
        if (!this.isTokenActive(t)) continue;
        rows.push({ providerId: p.id, providerName: p.name, providerEnabled: p.enabled, token: t });
      }
    }
    return rows;
  });

  protected readonly models = computed<ChatModelOption[]>(() =>
    deriveChatModelOptions(this.providers(), this.selectedTokenIds()),
  );

  async ngOnInit(): Promise<void> {
    await this.aiHub.load();
    this.providers.set(this.aiHub.providerList());
    const settings = await this.moduleSettings.get(MODULE_KEY);
    if (Array.isArray(settings['selectedTokens'])) {
      this.selectedTokenIds.set(new Set(settings['selectedTokens'] as string[]));
    }
    if (settings['systemPrompt']) this.systemPrompt.set(settings['systemPrompt'] as string);
    if (typeof settings['temperature'] === 'number') this.temperature.set(settings['temperature']);
    if (typeof settings['maxTokens'] === 'number') this.maxTokens.set(settings['maxTokens']);
    if (typeof settings['inputPlaceholder'] === 'string') this.inputPlaceholder.set(settings['inputPlaceholder']);
  }

  protected isTokenSelected(tokenId: string): boolean {
    return this.selectedTokenIds().has(tokenId);
  }

  protected toggleToken(tokenId: string, selected: boolean): void {
    this.selectedTokenIds.update((set) => {
      const next = new Set(set);
      if (selected) next.add(tokenId);
      else next.delete(tokenId);
      return next;
    });
  }

  protected isTokenActive(token: LlmTokenResponse): boolean {
    return token.enabled && !!token.maskedKey && token.models.some((m) => m.enabled);
  }

  protected enabledModelCount(token: LlmTokenResponse): number {
    return token.models.filter((m) => m.enabled).length;
  }

  protected async save(): Promise<void> {
    this.saving.set(true);
    await this.moduleSettings.update(MODULE_KEY, {
      selectedTokens: Array.from(this.selectedTokenIds()),
      systemPrompt: this.systemPrompt(),
      temperature: this.temperature(),
      maxTokens: this.maxTokens(),
      inputPlaceholder: this.inputPlaceholder(),
    });
    this.saving.set(false);
    this.saved.set(true);
    setTimeout(() => this.saved.set(false), 2000);
  }
}
