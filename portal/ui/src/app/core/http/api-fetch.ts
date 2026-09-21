/**
 * Shared fetch wrapper with an explicit error policy: non-2xx responses and network
 * failures throw `ApiError` instead of being silently swallowed. Callers decide how to
 * handle failures (throw, log-and-default, ...) — the wrapper only guarantees that
 * `!res.ok` can never slip through unnoticed.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: string,
  ) {
    super(`API ${status}: ${body}`);
    this.name = 'ApiError';
  }
}

export async function apiFetch(url: string, init?: RequestInit): Promise<Response> {
  let res: Response;
  try {
    res = init === undefined ? await fetch(url) : await fetch(url, init);
  } catch (err) {
    throw err instanceof ApiError ? err : new ApiError(0, String(err));
  }
  if (!res.ok) {
    throw new ApiError(res.status, await res.text());
  }
  return res;
}

/** Parses a JSON error body defensively (used for `{"error":"..."}` envelopes). */
export async function errorBody(res: Response): Promise<string> {
  const parsed = (await res.json().catch(() => ({}))) as { error?: string };
  return parsed.error ?? String(res.status);
}
