import { apiFetch } from '../http/api-fetch';

/**
 * Common client for scope/module-keyed settings documents:
 * `GET /api/<prefix>/<key>` and `PUT /api/<prefix>/<key>` with a
 * `{ settings }` JSON envelope. Error policy: log-and-return-empty
 * (settings screens must render even when the API is unavailable).
 */
export abstract class ScopedSettingsClient {
  protected abstract readonly logTag: string;

  protected abstract readonly urlPrefix: string;

  async get(scope: string): Promise<Record<string, unknown>> {
    try {
      const res = await apiFetch(`${this.urlPrefix}/${encodeURIComponent(scope)}`);
      const data = (await res.json()) as { settings: Record<string, unknown> };
      return data.settings ?? {};
    } catch (err) {
      console.error(`[${this.logTag}] failed to load "${scope}":`, err);
      return {};
    }
  }

  /** Atomic merge on the server; returns the merged document. */
  async update(
    scope: string,
    partial: Record<string, unknown>,
  ): Promise<Record<string, unknown>> {
    try {
      const res = await apiFetch(`${this.urlPrefix}/${encodeURIComponent(scope)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(partial),
      });
      const data = (await res.json()) as { settings: Record<string, unknown> };
      return data.settings ?? {};
    } catch (err) {
      console.error(`[${this.logTag}] failed to update "${scope}":`, err);
      return {};
    }
  }
}
