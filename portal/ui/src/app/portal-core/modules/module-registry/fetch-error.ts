import { RegistryApiError } from './module-registry.store';

/** Structured install-wizard error: localized message plus optional hint, raw detail, issues. */
export interface InstallError {
  message: string;
  hint?: string;
  detail?: string;
  issues?: string[];
}

/** Codes meaning "the portal server cannot see that URL" — worth the reachability hint. */
const SERVER_CANNOT_SEE_YOUR_URL = ['unreachable', 'timeout', 'unknown-host'];

type Translate = (key: string, params?: Record<string, string | number>) => string;

/**
 * Turns a failed manifest fetch/install into what the wizard renders: a localized message keyed on
 * the server `code`, the reachability hint when the cause fits, the raw server text as a detail
 * line, and the 422 `issues[]`. Missing labels and errors without a code fall back to the raw
 * server text, so nothing ever renders as an i18n key.
 */
export function friendlyFetchError(err: unknown, t: Translate, url: string): InstallError {
  const code = err instanceof RegistryApiError ? err.code : 'failed';
  const serverText = err instanceof RegistryApiError ? err.message : rawMessage(err);
  const issues = err instanceof RegistryApiError ? err.issues : undefined;

  const label = code ? `registry.wizard.error.${code}` : '';
  const translated = label ? t(label, { url, detail: serverText }) : serverText;
  const message =
    (translated === label ? serverText : translated).trim() || t('registry.wizard.error.failed', { url });

  return {
    message,
    hint:
      code && SERVER_CANNOT_SEE_YOUR_URL.includes(code)
        ? t('registry.wizard.error.reachableHint')
        : undefined,
    detail: message.includes(serverText) ? undefined : serverText,
    issues: issues?.length ? issues : undefined,
  };
}

function rawMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
