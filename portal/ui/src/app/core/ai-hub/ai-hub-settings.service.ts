import { Injectable } from '@angular/core';
import { ScopedSettingsClient } from '../settings/scoped-settings-client';

const SETTINGS_SCOPE = 'settings';

/**
 * AI Hub settings document client — the server stores it in `ai_hub_settings`
 * and serves `GET/PUT /api/ai-hub/settings`. Error policy: log-and-return-empty
 * (inherited from ScopedSettingsClient).
 */
@Injectable({ providedIn: 'root' })
export class AiHubSettingsService extends ScopedSettingsClient {
  protected readonly logTag = 'ai-hub-settings';
  protected readonly urlPrefix = '/api/ai-hub';

  /** Full AI Hub settings document. */
  load(): Promise<Record<string, unknown>> {
    return super.get(SETTINGS_SCOPE);
  }

  /** Merges a partial into the AI Hub settings document; returns the merged document. */
  save(partial: Record<string, unknown>): Promise<Record<string, unknown>> {
    return super.update(SETTINGS_SCOPE, partial);
  }
}
