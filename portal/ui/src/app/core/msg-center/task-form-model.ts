import type { TaskFieldSpec } from '../../portal-core/modules/msg-center/task-panel.types';

/**
 * Pure form-model helpers behind the task panel (plan §9) — kept framework-free so the
 * validation semantics are unit-testable without constructing the component (whose import graph
 * pulls CDK private loaders that need the JIT compiler in vitest).
 */

export interface FieldValidationResult {
  error: string | null;
}

/** Instant per-field feedback mirroring the server allowlist (client hint only). */
export function fieldError(spec: TaskFieldSpec | undefined, value: unknown): string | null {
  if (value === undefined || value === null || value === '') return null;
  const schema = spec?.schema;
  if (!schema) return null;
  if (schema.type === 'number' || schema.type === 'integer') {
    // inputs are pre-coerced by inputValueFor — a non-number here means bad data
    if (typeof value !== 'number') {
      return schema.type === 'integer' ? 'integer required' : 'number required';
    }
    if (Number.isNaN(value)) return 'not a number';
    if (schema.minimum !== undefined && value < schema.minimum) return `≥ ${schema.minimum}`;
    if (schema.maximum !== undefined && value > schema.maximum) return `≤ ${schema.maximum}`;
    if (schema.type === 'integer' && !Number.isInteger(value)) return 'integer required';
    return null;
  }
  if (schema.type === 'boolean') {
    if (typeof value !== 'boolean') return 'boolean required';
    return null;
  }
  if (schema.type === 'string') {
    if (typeof value !== 'string') return 'string required';
    if (schema.minLength !== undefined && value.length < schema.minLength) {
      return `≥ ${schema.minLength} chars`;
    }
    if (schema.maxLength !== undefined && value.length > schema.maxLength) {
      return `≤ ${schema.maxLength} chars`;
    }
    if (schema.pattern && !value.match(new RegExp(schema.pattern))) return 'pattern mismatch';
    if (schema.format && !formatMatches(schema.format, value)) return `not a valid ${schema.format}`;
    if (schema.enum && !schema.enum.some((option) => sameScalar(option, value))) {
      return 'must be one of the enum options';
    }
  }
  return null;
}

/** Mirrors the server's scalarEquals: numbers compare numerically, the rest by string. */
function sameScalar(a: unknown, b: unknown): boolean {
  if (typeof a === 'number' && typeof b === 'number') return a === b;
  return String(a) === String(b);
}

/** Mirrors the server's formatMatches (date/email/uri per the v1 matrix). */
function formatMatches(format: string, value: string): boolean {
  try {
    switch (format) {
      case 'date':
        return /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(value));
      case 'email':
        return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value);
      default:
        return true;
    }
  } catch {
    return false;
  }
}

/** Required-field names that remain empty in the current draft. */
export function missingRequired(
  fields: TaskFieldSpec[] | undefined,
  values: Record<string, unknown>,
): string[] {
  if (!fields) return [];
  return fields
    .filter(
      (f) =>
        f.required &&
        (values[f.name] === undefined || values[f.name] === null || values[f.name] === ''),
    )
    .map((f) => f.name);
}

/** Coerce a DOM input event target into the scalar the server allowlist expects. */
export function inputValueFor(target: {
  type?: string;
  checked?: boolean;
  value?: string;
}): { ok: true; value: string | number | boolean | null } {
  const type = (target.type ?? 'text').toLowerCase();
  if (type === 'checkbox') {
    return { ok: true, value: target.checked === true };
  }
  if (type === 'number') {
    return {
      ok: true,
      value: target.value === '' || target.value == null ? null : Number(target.value),
    };
  }
  return { ok: true, value: target.value === '' || target.value == null ? null : target.value };
}
