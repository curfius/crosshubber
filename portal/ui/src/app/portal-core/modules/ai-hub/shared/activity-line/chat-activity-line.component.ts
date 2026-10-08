import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { I18nService } from '../../../../../core/i18n/i18n.service';
import { toolStatusTone, type ChatCitationRow, type ChatToolRow } from '../chat-tool-flow';

// Single-line activity display for the chat surfaces (phase 3): the current
// status, tool usage and agent calls collapse onto the status line, with a
// chevron expanding the full event history (tool rows + citations). Shared by
// the AI Hub chat page and the quick-chat flyout.

/** What the single line currently shows. */
interface ActivityLine {
  text: string;
  pulse: boolean;
}

@Component({
  selector: 'app-chat-activity-line',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './chat-activity-line.component.html',
})
export class ChatActivityLine {
  protected readonly i18n = inject(I18nService);

  readonly rows = input<ChatToolRow[]>([]);
  readonly citations = input<ChatCitationRow[]>([]);
  /** Free-form status text from the surface (connecting / done / error). */
  readonly status = input('');
  /** Tighter typography for the quick-chat flyout. */
  readonly compact = input(false);

  protected readonly expanded = signal(false);

  protected readonly line = computed<ActivityLine | null>(() => {
    const rows = this.rows();
    const pending = rows.find((r) => r.status === 'needs_confirmation');
    if (pending) {
      return {
        text: this.i18n.t('aihub.activity.pending', { tool: pending.tool }),
        pulse: true,
      };
    }
    const running = [...rows].reverse().find((r) => r.status === 'running');
    if (running) return { text: this.lineFor(running), pulse: true };
    const status = this.status();
    if (status) return { text: status, pulse: true };
    if (rows.length > 0) {
      return {
        text: this.i18n.t('aihub.activity.summary', { count: String(rows.length) }),
        pulse: false,
      };
    }
    return null;
  });

  protected readonly hasExpandable = computed(() => this.rows().length > 0);

  protected toggleExpanded(): void {
    this.expanded.update((v) => !v);
  }

  /** Phrases a tool/agent row by its kind (agent delegations read differently). */
  private lineFor(row: ChatToolRow): string {
    const tool = row.tool;
    const module = row.module ?? '';
    if (row.kind === 'agent') {
      const agentName =
        module && tool.startsWith(module + '_') ? tool.slice(module.length + 1) : tool;
      return this.i18n.t('aihub.activity.agent', { module, agent: agentName });
    }
    if (module) return this.i18n.t('aihub.activity.tool', { tool, module });
    return this.i18n.t('aihub.activity.tool-builtin', { tool });
  }

  protected toolStatusLabel(status: string): string {
    return this.i18n.t('agent.outcome.' + status.replace(/_/g, '-'));
  }

  protected readonly toolStatusTone = toolStatusTone;
}
