import { marked } from 'marked';
import DOMPurify from 'dompurify';

// Markdown rendering for assistant chat bubbles. Output goes through
// [innerHTML], so every render is sanitized — chat content is LLM-generated
// and must never ship raw model output into the DOM. Same marked options as
// the shared markdown editor (GFM + line breaks), but always sanitized.

// Memoize by input: template calls happen on every change detection, and
// streaming re-renders unchanged messages constantly. Small LRU-ish cap so
// long conversations don't grow it without bound.
const CACHE_MAX = 200;
const cache = new Map<string, string>();

marked.setOptions({ gfm: true, breaks: true });

// Interop-robust sanitizer handle: bundlers and test runners disagree on
// whether the package's ESM default lands directly or nested under `default`.
type Sanitizer = { sanitize: (dirty: string, config?: unknown) => string };
function resolvePurifier(mod: unknown): Sanitizer {
  const direct = mod as { sanitize?: unknown; default?: unknown };
  if (typeof direct?.sanitize === 'function') return direct as unknown as Sanitizer;
  const nested = direct?.default as { sanitize?: unknown } | undefined;
  if (typeof nested?.sanitize === 'function') return nested as Sanitizer;
  throw new Error('dompurify did not resolve to a sanitizer');
}
const purifier = resolvePurifier(DOMPurify);

/** Renders markdown to sanitized HTML (memoized per content string). */
export function renderChatMarkdown(content: string): string {
  const cached = cache.get(content);
  if (cached !== undefined) return cached;

  let html: string;
  try {
    const parsed = marked.parse(content ?? '');
    html = typeof parsed === 'string' ? parsed : '';
  } catch {
    html = '';
  }
  const safe = purifier.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style'],
    FORBID_ATTR: ['style'],
  });
  cache.set(content, safe);
  if (cache.size > CACHE_MAX) {
    // Drop the oldest entries (first insertion order keys).
    for (const key of Array.from(cache.keys())) {
      cache.delete(key);
      if (cache.size <= CACHE_MAX) break;
    }
  }
  return safe;
}
