import { describe, expect, it } from 'vitest';
import { friendlyFetchError } from './fetch-error';
import { RegistryApiError } from './module-registry.store';

const URL_UNDER_TEST = 'http://localhost:28092/';
const SERVER_TEXT = 'could not fetch http://localhost:28092/: connect ECONNREFUSED';

/** The en-GB labels the i18n catalog seeds for this feature. */
const LABELS: Record<string, string> = {
  'registry.wizard.error.unreachable':
    'The portal server could not connect to {url} (connection refused).',
  'registry.wizard.error.timeout': 'Fetching {url} timed out after 5 seconds.',
  'registry.wizard.error.unknown-host':
    'The portal server could not resolve the host in {url}.',
  'registry.wizard.error.upstream-status':
    '{url} answered {detail} instead of a manifest.',
  'registry.wizard.error.too-large': 'The manifest at {url} is larger than 1 MB.',
  'registry.wizard.error.not-json': '{url} did not return valid JSON.',
  'registry.wizard.error.blocked':
    'This portal cannot fetch {url} (private addresses are blocked).',
  'registry.wizard.error.invalid-url': '{url} is not a valid http(s) URL.',
  'registry.wizard.error.failed': 'The portal could not fetch {url}.',
  'registry.wizard.error.invalid-manifest':
    'The manifest is not valid: fix the issues listed below.',
  'registry.wizard.error.reachableHint': 'HINT: use a URL the portal can reach.',
  'registry.wizard.error.serverDetail': 'Server message: {detail}',
};

/**
 * Mirrors `I18nService.t`: unknown keys echo back as themselves and `{param}` placeholders are
 * substituted — that echo is what `friendlyFetchError` detects to fall back to the raw server text.
 */
function t(key: string, params?: Record<string, string | number>): string {
  const template = LABELS[key] ?? key;
  if (!params) return template;
  return template.replace(
    /\{(\w+)\}/g,
    (match, name: string) =>
      (Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : match),
  );
}

function apiError(code: string, message = SERVER_TEXT, issues?: string[]): RegistryApiError {
  return new RegistryApiError(502, message, code, issues);
}

describe('friendlyFetchError', () => {
  it('localizes unreachable failures and adds the reachability hint', () => {
    const result = friendlyFetchError(apiError('unreachable'), t, URL_UNDER_TEST);

    expect(result.message).toBe(
      'The portal server could not connect to http://localhost:28092/ (connection refused).',
    );
    expect(result.hint).toBe('HINT: use a URL the portal can reach.');
    expect(result.detail).toBe(SERVER_TEXT);
    expect(result.issues).toBeUndefined();
  });

  it.each(['unreachable', 'timeout', 'unknown-host'])(
    'hints that the portal cannot reach the URL for code %s',
    (code) => {
      expect(friendlyFetchError(apiError(code), t, URL_UNDER_TEST).hint).toBe(
        'HINT: use a URL the portal can reach.',
      );
    },
  );

  it.each(['blocked', 'invalid-url', 'upstream-status', 'too-large', 'not-json', 'failed'])(
    'shows no reachability hint for code %s',
    (code) => {
      expect(friendlyFetchError(apiError(code), t, URL_UNDER_TEST).hint).toBeUndefined();
    },
  );

  it('inlines the upstream status detail instead of repeating it on a second line', () => {
    const result = friendlyFetchError(
      apiError('upstream-status', 'HTTP 502 Bad Gateway'),
      t,
      URL_UNDER_TEST,
    );

    expect(result.message).toBe(
      'http://localhost:28092/ answered HTTP 502 Bad Gateway instead of a manifest.',
    );
    expect(result.detail).toBeUndefined();
  });

  it('passes the 422 issues through untouched', () => {
    const issues = ['content.applications[0].url must be http(s)'];
    const result = friendlyFetchError(
      apiError('invalid-manifest', 'the manifest is not valid', issues),
      t,
      URL_UNDER_TEST,
    );

    expect(result.message).toBe('The manifest is not valid: fix the issues listed below.');
    expect(result.issues).toEqual(issues);
    expect(result.hint).toBeUndefined();
  });

  it('keeps the raw server text as detail whenever the label is different text', () => {
    const result = friendlyFetchError(apiError('failed'), t, URL_UNDER_TEST);

    expect(result.message).toBe('The portal could not fetch http://localhost:28092/.');
    expect(result.detail).toBe(SERVER_TEXT);
  });

  it('falls back to the raw server text when the code has no label', () => {
    const result = friendlyFetchError(apiError('no-such-code'), t, URL_UNDER_TEST);

    expect(result.message).toBe(SERVER_TEXT);
    expect(result.detail).toBeUndefined();
  });

  it('renders plain errors through the failed label and keeps their raw message', () => {
    const result = friendlyFetchError(new Error('manifestVersion must be 1'), t, URL_UNDER_TEST);

    expect(result.message).toBe('The portal could not fetch http://localhost:28092/.');
    expect(result.detail).toBe('manifestVersion must be 1');
    expect(result.hint).toBeUndefined();
  });

  it('stringifies thrown non-errors', () => {
    const result = friendlyFetchError('nope', t, URL_UNDER_TEST);

    expect(result.message).toBe('The portal could not fetch http://localhost:28092/.');
    expect(result.detail).toBe('nope');
  });

  it('never renders an empty message', () => {
    const result = friendlyFetchError(apiError('no-such-code', ''), t, URL_UNDER_TEST);

    expect(result.message).toBe('The portal could not fetch http://localhost:28092/.');
    expect(result.detail).toBeUndefined();
  });

  it('drops an empty issues array', () => {
    expect(friendlyFetchError(apiError('invalid-manifest', 'x', []), t, URL_UNDER_TEST).issues)
      .toBeUndefined();
  });
});
