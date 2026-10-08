import { ChangeDetectionStrategy, Component } from '@angular/core';
import { AiHubChat } from './chat/chat.component';

/**
 * AI Hub app — the assistant chat page. The former Chat/Providers tab bar is
 * gone: providers management lives on its own settings-category page
 * (`ai-hub-providers`), and AI Hub settings likewise. Per user decision,
 * removing the tab bar leaves this app rendering the chat directly.
 */
@Component({
  selector: 'app-ai-hub',
  imports: [AiHubChat],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './ai-hub.component.html',
  styleUrl: './ai-hub.component.css',
})
export class AiHub {}
